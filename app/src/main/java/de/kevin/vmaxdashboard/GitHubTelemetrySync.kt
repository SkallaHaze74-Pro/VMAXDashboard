package de.kevin.vmaxdashboard

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.database.ContentObserver
import android.net.ConnectivityManager
import android.net.Network
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.security.KeyStore
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class GitHubSyncSnapshot(
    val enabled: Boolean,
    val tokenConfigured: Boolean,
    val pendingBundles: Int,
    val uploadedBundles: Int,
    val lastStatus: String
)

internal val REQUIRED_MEASUREMENT_FILES = listOf(
    "BLE_Rohdaten.csv",
    "Live_Telemetrie.csv",
    "Ereignisse.csv",
    "Zusammenfassung.txt",
    "Automatische_Analyse.txt",
    "Lernprofil.json"
)

internal val OPTIONAL_MEASUREMENT_FILES = listOf(
    DIAGNOSTIC_READ_CSV_FILE,
    DIAGNOSTIC_READ_SUMMARY_FILE,
    DIAGNOSTIC_READ_MANIFEST_FILE
)

/** A measurement is complete with its core files; present diagnostics travel with it atomically. */
internal fun measurementFilesToQueue(availableNames: Set<String>): List<String> {
    if (!REQUIRED_MEASUREMENT_FILES.all(availableNames::contains)) return emptyList()
    val availableDiagnostics = OPTIONAL_MEASUREMENT_FILES.filter(availableNames::contains)
    // A complete diagnostic triple travels atomically. If optional export failed
    // after writing only part of it, never let those leftovers block the six core
    // ride files or leak a torn diagnostic dataset to the public branch.
    if (availableDiagnostics.isNotEmpty() && availableDiagnostics.size != OPTIONAL_MEASUREMENT_FILES.size) {
        return REQUIRED_MEASUREMENT_FILES
    }
    return REQUIRED_MEASUREMENT_FILES + availableDiagnostics
}

internal fun isCompleteMeasurementQueueFileSet(fileNames: Set<String>): Boolean {
    val optionalPresent = OPTIONAL_MEASUREMENT_FILES.count(fileNames::contains)
    if (!REQUIRED_MEASUREMENT_FILES.all(fileNames::contains)) return false
    if (optionalPresent != 0 && optionalPresent != OPTIONAL_MEASUREMENT_FILES.size) return false
    val expected = REQUIRED_MEASUREMENT_FILES.toSet() +
        (if (optionalPresent == OPTIONAL_MEASUREMENT_FILES.size) OPTIONAL_MEASUREMENT_FILES else emptyList()) +
        setOf("manifest.json", ".meta.json")
    return fileNames == expected
}

internal fun shouldQueueMeasurementCandidate(
    alreadyProcessed: Boolean,
    completeQueueAlreadyExists: Boolean
): Boolean = !alreadyProcessed && !completeQueueAlreadyExists

internal fun hasExpectedMeasurementFileHeader(name: String, content: String): Boolean {
    if (content.isBlank()) return false
    val firstLine = content.lineSequence().firstOrNull()?.removePrefix("\uFEFF").orEmpty()
    return when (name) {
        "BLE_Rohdaten.csv", "Live_Telemetrie.csv", "Ereignisse.csv" ->
            firstLine.startsWith("relative_ms;timestamp_ms;")
        DIAGNOSTIC_READ_CSV_FILE -> firstLine.startsWith("timestamp_ms;scan_id;")
        "Zusammenfassung.txt" -> firstLine.startsWith("VMAX Dashboard Messfahrt")
        DIAGNOSTIC_READ_SUMMARY_FILE -> firstLine.startsWith("VMAX BT638 Deep READ")
        "Lernprofil.json", DIAGNOSTIC_READ_MANIFEST_FILE, "manifest.json", ".meta.json" ->
            runCatching { JSONObject(content) }.isSuccess
        "Automatische_Analyse.txt" -> true
        else -> false
    }
}

private val DIAGNOSTIC_IDENTITY_MANIFEST_KEYS = setOf(
    "device", "device_name", "devicename", "serial", "serial_number", "serialnumber",
    "bluetooth_address", "bluetoothaddress"
)

private fun redactDiagnosticManifestNode(node: Any?) {
    when (node) {
        is JSONObject -> {
            val keys = node.keys().asSequence().toList()
            keys.forEach { key ->
                val value = node.opt(key)
                val canonicalKey = key.lowercase()
                if (canonicalKey in DIAGNOSTIC_IDENTITY_MANIFEST_KEYS && value != null && value != JSONObject.NULL) {
                    val exact = value.toString()
                    node.put(
                        key,
                        if (canonicalKey in setOf("device", "device_name", "devicename")) {
                            diagnosticPublicDeviceLabel(exact)
                        } else {
                            diagnosticPublicIdentityLabel(exact)
                        }
                    )
                } else {
                    redactDiagnosticManifestNode(value)
                }
            }
        }
        is JSONArray -> (0 until node.length()).forEach { index ->
            redactDiagnosticManifestNode(node.opt(index))
        }
    }
}

/** Fail closed and hash identity fields in legacy or current diagnostic manifests. */
internal fun redactDiagnosticReadManifestForPublic(json: String): String {
    val manifest = JSONObject(json)
    redactDiagnosticManifestNode(manifest)
    manifest.put("public_identity_redacted", true)
    manifest.put(
        "public_hash_privacy",
        "unsalted SHA-256 pseudonym; same payload is linkable across public uploads"
    )
    return manifest.toString(2)
}

private fun redactLearningSessionKey(value: String): String {
    val separator = value.lastIndexOf('@')
    if (separator <= 0) return diagnosticPublicDeviceLabel(value)
    val identity = value.substring(0, separator)
    val session = value.substring(separator)
    return diagnosticPublicDeviceLabel(identity) + session
}

/** The learning schema stores the BLE device name in model and sessionKeys. */
internal fun redactLearningProfileForPublic(json: String): String {
    val root = JSONObject(json)
    val candidates = root.optJSONArray("candidates")
    if (candidates != null) {
        for (index in 0 until candidates.length()) {
            val candidate = candidates.optJSONObject(index) ?: continue
            if (candidate.has("model") && !candidate.isNull("model")) {
                candidate.put("model", diagnosticPublicDeviceLabel(candidate.optString("model")))
            }
            val sessionKeys = candidate.optJSONArray("sessionKeys") ?: continue
            for (sessionIndex in 0 until sessionKeys.length()) {
                val exact = sessionKeys.optString(sessionIndex)
                if (exact.isNotBlank()) sessionKeys.put(sessionIndex, redactLearningSessionKey(exact))
            }
        }
    }
    root.put("public_identity_redacted", true)
    root.put(
        "public_hash_privacy",
        "unsalted SHA-256 pseudonym; same identity is linkable across public uploads"
    )
    return root.toString(2)
}

/**
 * Last fail-closed privacy boundary before public GitHub upload. Applying it a
 * second time is intentional: queues created by an older app version may still
 * contain exact identity bytes.
 */
internal fun publicGitHubUploadBytes(name: String, bytes: ByteArray): ByteArray {
    val text = when (name) {
        "BLE_Rohdaten.csv",
        DIAGNOSTIC_READ_CSV_FILE,
        DIAGNOSTIC_READ_SUMMARY_FILE,
        "Zusammenfassung.txt",
        DIAGNOSTIC_READ_MANIFEST_FILE,
        "manifest.json",
        "Lernprofil.json" -> decodePublicUploadUtf8(bytes)
        else -> return bytes
    }
    val publicText = when (name) {
        "BLE_Rohdaten.csv" -> redactRawTelemetryCsvForPublic(text)
        DIAGNOSTIC_READ_CSV_FILE -> redactDiagnosticReadCsvForPublic(text)
        DIAGNOSTIC_READ_SUMMARY_FILE, "Zusammenfassung.txt" ->
            redactDiagnosticReadSummaryForPublic(text)
        DIAGNOSTIC_READ_MANIFEST_FILE, "manifest.json" ->
            redactDiagnosticReadManifestForPublic(text)
        "Lernprofil.json" -> redactLearningProfileForPublic(text)
        else -> error("unreachable public upload artifact: $name")
    }
    return publicText.toByteArray(Charsets.UTF_8)
}

private fun decodePublicUploadUtf8(bytes: ByteArray): String = try {
    Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes))
        .toString()
} catch (error: CharacterCodingException) {
    throw IllegalArgumentException("Public upload artifact is not valid UTF-8", error)
}

internal data class TelemetryCsvMetrics(
    val schemaVersion: Int = 1,
    val liveRows: Int = 0,
    val maxSpeedKmh: Double? = null,
    val maxDirectPowerW: Double? = null,
    val maxElectricalPowerW: Double? = null
)

private const val MAX_MANIFEST_SPEED_KMH = 100.0
private const val MAX_MANIFEST_DIRECT_POWER_W = 30_000.0

private fun csvFiniteDouble(cells: List<String>, index: Int?): Double? {
    if (index == null) return null
    return cells.getOrNull(index)
        ?.trim()
        ?.toDoubleOrNull()
        ?.takeIf { it.isFinite() }
}

internal fun telemetryCsvMetrics(lines: Sequence<String>): TelemetryCsvMetrics {
    val iterator = lines.iterator()
    if (!iterator.hasNext()) return TelemetryCsvMetrics()
    val header = iterator.next().split(';').mapIndexed { index, name ->
        name.trim().removePrefix("\uFEFF").lowercase() to index
    }.toMap()
    val schemaVersion = if (
        setOf("power_w", "electrical_power_w", "power_provenance", "source_channel").all(header::containsKey)
    ) 2 else 1
    val speedIndex = header["speed_kmh_candidate"] ?: header["speed_kmh"]
    val directPowerIndex = header["power_w"]
    val electricalPowerIndex = header["electrical_power_w"]
    val powerProvenanceIndex = header["power_provenance"]
    val legacyDirectRawIndex = header["motor_load_raw_be"]
    val sourceIndex = header["source_channel"]
    var rows = 0
    var maxSpeed: Double? = null
    var maxDirectPower: Double? = null
    var maxElectricalPower: Double? = null

    while (iterator.hasNext()) {
        val line = iterator.next()
        if (line.isBlank()) continue
        val cells = line.split(';')
        rows++
        val source = sourceIndex?.let { cells.getOrNull(it) }?.trim()?.uppercase()
        if (source == "1505") {
            csvFiniteDouble(cells, speedIndex)
                ?.takeIf { it in 0.0..MAX_MANIFEST_SPEED_KMH }
                ?.let {
                    maxSpeed = maxOf(maxSpeed ?: it, it)
                }
        }
        if (source == "1509") {
            val directPower = csvFiniteDouble(cells, directPowerIndex)
                ?.takeIf { it in 0.0..MAX_MANIFEST_DIRECT_POWER_W }
            val isConfirmedDirectPower = if (schemaVersion == 2) {
                powerProvenanceIndex
                    ?.let { cells.getOrNull(it) }
                    ?.trim()
                    ?.lowercase() == "1509_direct"
            } else {
                val legacyDirectRaw = csvFiniteDouble(cells, legacyDirectRawIndex)
                directPower != null && legacyDirectRaw != null && directPower == legacyDirectRaw
            }
            if (isConfirmedDirectPower && directPower != null) {
                maxDirectPower = maxOf(maxDirectPower ?: directPower, directPower)
            }
            if (schemaVersion == 2) {
                csvFiniteDouble(cells, electricalPowerIndex)?.let {
                    maxElectricalPower = maxOf(maxElectricalPower ?: it, it)
                }
            }
        }
    }
    return TelemetryCsvMetrics(
        schemaVersion = schemaVersion,
        liveRows = rows,
        maxSpeedKmh = maxSpeed,
        maxDirectPowerW = maxDirectPower,
        maxElectricalPowerW = maxElectricalPower
    )
}

class GitHubTelemetrySync private constructor(context: Context) {
    companion object {
        private const val PREFS = "vmax_github_sync"
        private const val TOKEN_KEY_ALIAS = "vmax_github_sync_token"
        private const val DATA_BRANCH = "telemetry-data"
        private const val BASE_BRANCH = "main"
        private const val OWNER = "SkallaHaze74-Pro"
        private const val REPO = "VMAXDashboard"
        private const val ROOT = "fahrdaten"

        @Volatile private var instance: GitHubTelemetrySync? = null

        fun get(context: Context): GitHubTelemetrySync =
            instance ?: synchronized(this) {
                instance ?: GitHubTelemetrySync(context.applicationContext).also { instance = it }
            }
    }

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val queueRoot = File(appContext.filesDir, "github_upload_queue").apply { mkdirs() }
    private val executor = Executors.newSingleThreadExecutor()
    private val started = AtomicBoolean(false)
    private val connectivityManager =
        appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val contentObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            scheduleScanAndFlush()
        }

        override fun onChange(selfChange: Boolean, uri: Uri?) {
            scheduleScanAndFlush()
        }
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            scheduleScanAndFlush()
        }
    }

    fun start() {
        if (!started.compareAndSet(false, true)) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appContext.contentResolver.registerContentObserver(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                true,
                contentObserver
            )
        }
        runCatching { connectivityManager.registerDefaultNetworkCallback(networkCallback) }
        scheduleScanAndFlush()
    }

    fun setEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("enabled", enabled).apply()
        setStatus(if (enabled) "GitHub Auto-Upload aktiv" else "GitHub Auto-Upload aus")
        if (enabled) scheduleScanAndFlush()
    }

    fun saveToken(token: String) {
        val clean = token.trim()
        require(clean.isNotBlank()) { "Token darf nicht leer sein" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateTokenKey())
        val encrypted = cipher.doFinal(clean.toByteArray(Charsets.UTF_8))
        prefs.edit()
            .putString("token_iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString("token_data", Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .putBoolean("enabled", true)
            .apply()
        setStatus("Token sicher gespeichert – Upload wird gestartet")
        scheduleScanAndFlush()
    }

    fun clearToken() {
        prefs.edit()
            .remove("token_iv")
            .remove("token_data")
            .putBoolean("enabled", false)
            .apply()
        setStatus("GitHub-Token entfernt")
    }

    fun retryNow() {
        setStatus("GitHub Sync wird erneut geprüft …")
        scheduleScanAndFlush()
    }

    fun snapshot(): GitHubSyncSnapshot = GitHubSyncSnapshot(
        enabled = prefs.getBoolean("enabled", false),
        tokenConfigured = tokenOrNull() != null,
        pendingBundles = (queueRoot.listFiles()?.count {
            it.isDirectory && !it.name.startsWith(".")
        } ?: 0) +
            DiagnosticReadArchive.get(appContext).pendingCount(),
        uploadedBundles = prefs.getInt("uploaded_count", 0),
        lastStatus = prefs.getString("status", "Noch nicht eingerichtet").orEmpty()
    )

    private fun scheduleScanAndFlush() {
        executor.execute {
            if (!prefs.getBoolean("enabled", false)) return@execute
            runCatching { scanForCompletedMeasurements() }
                .onFailure { setStatus("Lokaler Scan: ${safeMessage(it)}") }
            flushPending()
            DiagnosticReadArchive.get(appContext).retryPending()
        }
    }

    private fun scanForCompletedMeasurements() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            scanMediaStoreDownloads()
        } else {
            scanLegacyFiles()
        }
    }

    private fun scanMediaStoreDownloads() {
        val resolver = appContext.contentResolver
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.RELATIVE_PATH,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.IS_PENDING
        )
        val selection = "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? AND " +
            "${MediaStore.MediaColumns.IS_PENDING}=0"
        val args = arrayOf("${Environment.DIRECTORY_DOWNLOADS}/VMAXDashboard/Messfahrt_%/")
        val grouped = linkedMapOf<String, MutableMap<String, Uri>>()

        resolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            args,
            "${MediaStore.MediaColumns.DATE_MODIFIED} DESC"
        )?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val nameIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val pathIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)
            val sizeIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            while (cursor.moveToNext()) {
                val path = cursor.getString(pathIndex) ?: continue
                val name = cursor.getString(nameIndex) ?: continue
                if (cursor.getLong(sizeIndex) <= 0L) continue
                if (name !in REQUIRED_MEASUREMENT_FILES && name !in OPTIONAL_MEASUREMENT_FILES) continue
                val id = cursor.getLong(idIndex)
                val uri = ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id)
                grouped.getOrPut(path) { linkedMapOf() }[name] = uri
            }
        }

        grouped.entries
            .filter { (path, files) ->
                val folderName = path.trimEnd('/').substringAfterLast('/')
                val sourceKey = "mediastore:$path"
                measurementFilesToQueue(files.keys).isNotEmpty() &&
                    folderName.isNotBlank() &&
                    shouldQueueMeasurementCandidate(
                        alreadyProcessed = isProcessed(sourceKey),
                        completeQueueAlreadyExists = isCompleteMeasurementQueueFolder(
                            File(queueRoot, safeFolderName(folderName))
                        )
                    )
            }
            .take(20)
            .forEach { (path, files) ->
                queueMeasurement(path, files, resolver)
            }
    }

    private fun scanLegacyFiles() {
        val base = appContext.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: return
        val vmaxRoot = File(base, "VMAXDashboard")
        vmaxRoot.listFiles()
            ?.filter { it.isDirectory && it.name.startsWith("Messfahrt_") }
            ?.sortedByDescending(File::lastModified)
            ?.filter { folder ->
                val available = folder.listFiles()
                    ?.filter(File::isFile)
                    ?.map(File::getName)
                    ?.toSet()
                    .orEmpty()
                measurementFilesToQueue(available).isNotEmpty() &&
                    shouldQueueMeasurementCandidate(
                        alreadyProcessed = isProcessed("legacy:${folder.absolutePath}"),
                        completeQueueAlreadyExists = isCompleteMeasurementQueueFolder(
                            File(queueRoot, safeFolderName(folder.name))
                        )
                    )
            }
            ?.take(20)
            ?.forEach { folder ->
                val available = folder.listFiles()
                    ?.filter(File::isFile)
                    ?.map(File::getName)
                    ?.toSet()
                    .orEmpty()
                if (measurementFilesToQueue(available).isEmpty()) return@forEach
                queueLegacyMeasurement(folder)
            }
    }

    private fun queueMeasurement(
        sourcePath: String,
        files: Map<String, Uri>,
        resolver: ContentResolver
    ) {
        val folderName = sourcePath.trimEnd('/').substringAfterLast('/')
        if (folderName.isBlank()) return
        val sourceKey = "mediastore:$sourcePath"
        if (isProcessed(sourceKey)) return
        val queueFolder = File(queueRoot, safeFolderName(folderName))
        if (!prepareMeasurementQueueTarget(queueFolder)) return

        val staging = File(queueRoot, ".${safeFolderName(folderName)}.tmp")
        staging.deleteRecursively()
        staging.mkdirs()
        try {
            measurementFilesToQueue(files.keys).forEach { name ->
                val uri = files.getValue(name)
                resolver.openInputStream(uri)?.use { input ->
                    val bytes = input.readBytes()
                    val publicBytes = publicGitHubUploadBytes(name, bytes)
                    check(hasExpectedMeasurementFileHeader(name, String(publicBytes, Charsets.UTF_8))) {
                        "$name ist leer oder hat kein erwartetes Format"
                    }
                    File(staging, name).writeBytes(publicBytes)
                } ?: error("$name konnte nicht gelesen werden")
            }
            finishQueueBundle(staging, queueFolder, folderName, sourceKey)
        } catch (error: Exception) {
            staging.deleteRecursively()
            throw error
        }
    }

    private fun queueLegacyMeasurement(sourceFolder: File) {
        val folderName = sourceFolder.name
        val sourceKey = "legacy:${sourceFolder.absolutePath}"
        if (isProcessed(sourceKey)) return
        val queueFolder = File(queueRoot, safeFolderName(folderName))
        if (!prepareMeasurementQueueTarget(queueFolder)) return

        val staging = File(queueRoot, ".${safeFolderName(folderName)}.tmp")
        staging.deleteRecursively()
        staging.mkdirs()
        try {
            val available = sourceFolder.listFiles()?.filter(File::isFile)?.map(File::getName)?.toSet().orEmpty()
            val selectedFiles = measurementFilesToQueue(available)
            if (selectedFiles.isEmpty()) {
                staging.deleteRecursively()
                return
            }
            selectedFiles.forEach { name ->
                val source = File(sourceFolder, name)
                val destination = File(staging, name)
                destination.writeBytes(publicGitHubUploadBytes(name, source.readBytes()))
                check(hasExpectedMeasurementFileHeader(name, destination.readText())) {
                    "$name ist leer oder hat kein erwartetes Format"
                }
            }
            finishQueueBundle(staging, queueFolder, folderName, sourceKey)
        } catch (error: Exception) {
            staging.deleteRecursively()
            throw error
        }
    }

    private fun finishQueueBundle(staging: File, queueFolder: File, folderName: String, sourceKey: String) {
        File(staging, "manifest.json").writeText(buildManifest(staging, folderName).toString(2))
        File(staging, ".meta.json").writeText(
            JSONObject()
                .put("sourceKey", sourceKey)
                .put("remoteFolder", remoteFolder(folderName))
                .toString()
        )
        val stagedNames = staging.listFiles()?.filter(File::isFile)?.map(File::getName)?.toSet().orEmpty()
        check(isCompleteMeasurementQueueFileSet(stagedNames)) { "Messfahrt-Staging ist unvollständig" }
        stagedNames.forEach { name ->
            check(hasExpectedMeasurementFileHeader(name, File(staging, name).readText())) {
                "Messfahrt-Staging enthält ungültige Datei: $name"
            }
        }
        check(!queueFolder.exists()) { "Messfahrt-Queueziel existiert bereits unvollständig" }
        check(staging.renameTo(queueFolder)) { "Messfahrt-Staging konnte nicht atomar übernommen werden" }
        setStatus("Messfahrt für GitHub vorgemerkt: $folderName")
    }

    private fun isCompleteMeasurementQueueFolder(folder: File): Boolean = runCatching {
        if (!folder.isDirectory || folder.name.startsWith(".")) return@runCatching false
        val names = folder.listFiles()?.filter(File::isFile)?.map(File::getName)?.toSet().orEmpty()
        isCompleteMeasurementQueueFileSet(names) && names.all { name ->
            hasExpectedMeasurementFileHeader(name, File(folder, name).readText())
        }
    }.getOrDefault(false)

    /**
     * A torn app-private queue must never block rebuilding from the still intact
     * Downloads source. Preserve it under a hidden quarantine name, then build a
     * fresh atomic directory; flush/pending counters deliberately ignore hidden dirs.
     */
    private fun prepareMeasurementQueueTarget(queueFolder: File): Boolean {
        if (!queueFolder.exists()) return true
        if (isCompleteMeasurementQueueFolder(queueFolder)) return false
        var suffix = 0
        var quarantine: File
        do {
            val extra = if (suffix == 0) "" else "-$suffix"
            quarantine = File(
                queueRoot,
                ".invalid-${queueFolder.name}-${System.currentTimeMillis()}$extra"
            )
            suffix++
        } while (quarantine.exists())
        check(queueFolder.renameTo(quarantine)) {
            "Ungültige Messfahrt-Queue konnte nicht quarantänisiert werden"
        }
        setStatus("Ungültige Queue wird aus Originaldaten neu aufgebaut: ${queueFolder.name}")
        return true
    }

    private fun buildManifest(folder: File, folderName: String): JSONObject {
        val summary = File(folder, "Zusammenfassung.txt").takeIf(File::isFile)?.readText().orEmpty()
        val liveFile = File(folder, "Live_Telemetrie.csv")
        val metrics = if (liveFile.isFile) {
            liveFile.useLines { lines -> telemetryCsvMetrics(lines) }
        } else {
            TelemetryCsvMetrics()
        }

        val summaryValues = summary.lineSequence()
            .mapNotNull { line ->
                val split = line.indexOf(':')
                if (split <= 0) null else line.substring(0, split).trim() to line.substring(split + 1).trim()
            }
            .toMap()

        val packageInfo = appContext.packageManager.getPackageInfo(appContext.packageName, 0)
        return JSONObject()
            .put("schema", "vmax-github-telemetry-v1")
            .put("measurement", folderName)
            .put("device", summaryValues["Gerät"].orEmpty())
            .put("start_ms", summaryValues["Start"]?.toLongOrNull() ?: JSONObject.NULL)
            .put("end_ms", summaryValues["Ende"]?.toLongOrNull() ?: JSONObject.NULL)
            .put("duration_ms", summaryValues["Dauer_ms"]?.toLongOrNull() ?: JSONObject.NULL)
            .put("ble_packets", summaryValues["BLE_Pakete"]?.toLongOrNull() ?: JSONObject.NULL)
            .put("markers", summaryValues["Marker"]?.toIntOrNull() ?: JSONObject.NULL)
            .put("channels", summaryValues["Kanäle"].orEmpty())
            .put("live_rows", metrics.liveRows)
            .put("max_speed_kmh", metrics.maxSpeedKmh ?: JSONObject.NULL)
            // max_power_w stays as a backwards-compatible alias, but now has a
            // single direct-power meaning and is evaluated only on fresh 1509 rows.
            .put("max_power_w", metrics.maxDirectPowerW ?: JSONObject.NULL)
            .put("max_direct_power_w", metrics.maxDirectPowerW ?: JSONObject.NULL)
            .put("max_electrical_power_w", metrics.maxElectricalPowerW ?: JSONObject.NULL)
            .put("telemetry_csv_schema", metrics.schemaVersion)
            .put("received_notification_packets", summaryValues["BLE_Empfangen"]?.toIntOrNull() ?: JSONObject.NULL)
            .put("accepted_notification_packets", summaryValues["BLE_Akzeptiert"]?.toIntOrNull() ?: JSONObject.NULL)
            .put("rejected_read_packets", summaryValues["READ_Verworfen"]?.toIntOrNull() ?: JSONObject.NULL)
            .put("rejected_hybrid_packets", summaryValues["Hybrid_Verworfen"]?.toIntOrNull() ?: JSONObject.NULL)
            .put("connection_epochs", summaryValues["Verbindungsepochen"]?.toIntOrNull() ?: JSONObject.NULL)
            .put("deep_read_dump", File(folder, DIAGNOSTIC_READ_CSV_FILE).isFile)
            .put("deep_read_scans", summaryValues["Deep_READ_Scans"]?.toIntOrNull() ?: 0)
            .put("deep_read_responses", summaryValues["Deep_READ_Antworten"]?.toIntOrNull() ?: 0)
            .put("decoder_ai_analysis", true)
            .put("learning_profile", true)
            .put("app_version", packageInfo.versionName.orEmpty())
            .put("created_at_ms", System.currentTimeMillis())
    }

    private fun flushPending() {
        if (!prefs.getBoolean("enabled", false)) return
        val token = tokenOrNull() ?: run {
            setStatus("GitHub Sync aktiv, aber Token fehlt")
            return
        }
        val pending = queueRoot.listFiles()
            ?.filter { it.isDirectory && !it.name.startsWith(".") }
            ?.sortedBy(File::lastModified)
            .orEmpty()
        if (pending.isEmpty()) {
            setStatus("Messfahrt-Queue leer • Deep READ wird separat synchronisiert")
            return
        }

        val uploader = GitHubContentsUploader(token, OWNER, REPO, DATA_BRANCH, BASE_BRANCH)
        for (folder in pending) {
            val metaFile = File(folder, ".meta.json")
            val fileNames = folder.listFiles()?.filter(File::isFile)?.map(File::getName)?.toSet().orEmpty()
            val queueIsValid = runCatching {
                isCompleteMeasurementQueueFileSet(fileNames) &&
                    fileNames.all { name ->
                        hasExpectedMeasurementFileHeader(name, File(folder, name).readText())
                    }
            }.getOrDefault(false)
            if (!queueIsValid) {
                setStatus("Upload wartet: unvollständige/ungültige Queue ${folder.name}")
                continue
            }
            try {
                val meta = JSONObject(metaFile.readText())
                val sourceKey = meta.getString("sourceKey")
                val remoteFolder = meta.getString("remoteFolder")
                setStatus("GitHub Upload: ${folder.name}")
                uploader.ensureDataBranch()
                val uploadFiles = folder.listFiles()
                    ?.filter { it.isFile && it.name != ".meta.json" && it.name in fileNames }
                    ?.sortedWith(compareBy<File> { it.name == "manifest.json" }.thenBy { it.name })
                    .orEmpty()
                uploadFiles.forEach { file ->
                    val publicBytes = publicGitHubUploadBytes(file.name, file.readBytes())
                    uploader.uploadIfMissing("$remoteFolder/${file.name}", publicBytes, folder.name)
                }
                markProcessed(sourceKey)
                folder.deleteRecursively()
                prefs.edit().putInt("uploaded_count", prefs.getInt("uploaded_count", 0) + 1).apply()
                setStatus("✓ GitHub aktuell: ${folder.name}")
            } catch (error: Exception) {
                setStatus("Upload wartet: ${safeMessage(error)}")
                return
            }
        }
    }

    private fun remoteFolder(folderName: String): String {
        val date = folderName.removePrefix("Messfahrt_").take(10).takeIf { it.matches(Regex("\\d{4}-\\d{2}-\\d{2}")) }
            ?: "unbekanntes-datum"
        return "$ROOT/$date/$folderName"
    }

    private fun tokenOrNull(): String? {
        val ivText = prefs.getString("token_iv", null) ?: return null
        val dataText = prefs.getString("token_data", null) ?: return null
        return runCatching {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                getOrCreateTokenKey(),
                GCMParameterSpec(128, Base64.decode(ivText, Base64.NO_WRAP))
            )
            String(cipher.doFinal(Base64.decode(dataText, Base64.NO_WRAP)), Charsets.UTF_8)
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    private fun getOrCreateTokenKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(TOKEN_KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                TOKEN_KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    private fun isProcessed(sourceKey: String): Boolean =
        prefs.getStringSet("processed", emptySet()).orEmpty().contains(sourceKey)

    private fun markProcessed(sourceKey: String) {
        val current = prefs.getStringSet("processed", emptySet()).orEmpty().toMutableList()
        current.remove(sourceKey)
        current.add(sourceKey)
        val bounded = current.takeLast(250).toSet()
        prefs.edit().putStringSet("processed", bounded).apply()
    }

    private fun setStatus(status: String) {
        prefs.edit().putString("status", status.take(220)).apply()
    }

    private fun safeFolderName(value: String): String = value.replace(Regex("[^A-Za-z0-9._-]"), "_")

    private fun safeMessage(error: Throwable): String =
        (error.message ?: error::class.java.simpleName).replace(Regex("gh[pousr]_[A-Za-z0-9_]+"), "<token>").take(180)
}

private class GitHubContentsUploader(
    private val token: String,
    private val owner: String,
    private val repo: String,
    private val branch: String,
    private val baseBranch: String
) {
    private val apiBase = "https://api.github.com/repos/$owner/$repo"

    fun ensureDataBranch() {
        val current = request("GET", "$apiBase/git/ref/heads/${encodeSegment(branch)}")
        if (current.code in 200..299) return
        if (current.code != 404) throw IOException("Branch-Prüfung fehlgeschlagen (${current.code})")

        val base = request("GET", "$apiBase/git/ref/heads/${encodeSegment(baseBranch)}")
        if (base.code !in 200..299) throw IOException("Basis-Branch nicht lesbar (${base.code})")
        val sha = JSONObject(base.body).getJSONObject("object").getString("sha")
        val create = request(
            "POST",
            "$apiBase/git/refs",
            JSONObject().put("ref", "refs/heads/$branch").put("sha", sha).toString()
        )
        if (create.code !in 200..299 && create.code != 422) {
            throw IOException("Daten-Branch konnte nicht erstellt werden (${create.code})")
        }
    }

    fun uploadIfMissing(remotePath: String, bytes: ByteArray, measurementName: String) {
        val url = "$apiBase/contents/${encodePath(remotePath)}?ref=${encodeSegment(branch)}"
        val existing = request("GET", url)
        if (existing.code in 200..299) return
        if (existing.code != 404) throw IOException("Dateiprüfung fehlgeschlagen (${existing.code})")

        val payload = JSONObject()
            .put("message", "Fahrdaten $measurementName: ${remotePath.substringAfterLast('/')}")
            .put("content", Base64.encodeToString(bytes, Base64.NO_WRAP))
            .put("branch", branch)
            .toString()
        val upload = request("PUT", "$apiBase/contents/${encodePath(remotePath)}", payload)
        if (upload.code !in 200..299) {
            throw IOException("GitHub Upload fehlgeschlagen (${upload.code}): ${extractMessage(upload.body)}")
        }
    }

    private fun request(method: String, url: String, jsonBody: String? = null): HttpResult {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            setRequestProperty("User-Agent", "VMAXDashboard-Android")
            if (jsonBody != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
        }
        try {
            if (jsonBody != null) {
                connection.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(jsonBody) }
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            return HttpResult(code, body)
        } finally {
            connection.disconnect()
        }
    }

    private fun encodePath(path: String): String = path.split('/').joinToString("/") { encodeSegment(it) }

    private fun encodeSegment(value: String): String =
        URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")

    private fun extractMessage(body: String): String =
        runCatching { JSONObject(body).optString("message") }.getOrDefault("Unbekannter GitHub-Fehler").take(160)

    private data class HttpResult(val code: Int, val body: String)
}

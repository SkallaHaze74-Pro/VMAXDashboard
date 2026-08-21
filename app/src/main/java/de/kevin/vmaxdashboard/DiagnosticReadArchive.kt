package de.kevin.vmaxdashboard

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.KeyStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** One exact GATT READ response. It is diagnostic evidence, never live telemetry. */
internal data class DiagnosticReadRecord(
    val timestampMs: Long,
    val serviceUuid: String,
    val characteristicUuid: String,
    val shortId: String,
    val status: Int,
    val length: Int,
    val hex: String,
    val evidence: String,
    val meaning: String
)

/**
 * Persists a complete read-only GATT inventory independently from measurement telemetry.
 * No BluetoothGatt/write handle is reachable from this class.
 */
internal class DiagnosticReadArchive private constructor(context: Context) {
    companion object {
        private const val PREFS = "vmax_github_sync"
        private const val TOKEN_KEY_ALIAS = "vmax_github_sync_token"
        private const val DATA_BRANCH = "telemetry-data"
        private const val BASE_BRANCH = "main"
        private const val OWNER = "SkallaHaze74-Pro"
        private const val REPO = "VMAXDashboard"
        private const val REMOTE_ROOT = "diagnostics"

        @Volatile private var instance: DiagnosticReadArchive? = null

        fun get(context: Context): DiagnosticReadArchive =
            instance ?: synchronized(this) {
                instance ?: DiagnosticReadArchive(context.applicationContext).also { instance = it }
            }
    }

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val queueRoot = File(appContext.filesDir, "diagnostic_read_queue").apply { mkdirs() }
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val started = AtomicBoolean(false)

    fun start() {
        if (!started.compareAndSet(false, true)) return
        executor.execute { flushPending() }
    }

    fun saveAndPublish(
        records: List<DiagnosticReadRecord>,
        deviceName: String,
        scanStartedAt: Long,
        scanFinishedAt: Long,
        onStatus: (String) -> Unit = {}
    ) {
        if (records.isEmpty()) {
            mainHandler.post { onStatus("READ-Scan fertig • keine READ-Antworten gespeichert") }
            return
        }
        val frozen = records.toList()
        executor.execute {
            val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.GERMANY)
                .format(Date(scanStartedAt))
            val folderName = "DeepRead_$stamp"
            val localFolder = "VMAXDashboard/$folderName"
            val csv = buildCsv(frozen)
            val summary = buildSummary(frozen, deviceName, scanStartedAt, scanFinishedAt)
            val manifest = buildManifest(frozen, deviceName, folderName, scanStartedAt, scanFinishedAt)

            try {
                writeDownloadFile(localFolder, "Gatt_READ_Diagnose.csv", "text/csv", csv)
                writeDownloadFile(localFolder, "Gatt_READ_Summary.txt", "text/plain", summary)
                writeDownloadFile(localFolder, "manifest.json", "application/json", manifest.toString(2))
            } catch (error: Throwable) {
                mainHandler.post {
                    onStatus("READ-Dump konnte lokal nicht gespeichert werden: ${safeMessage(error)}")
                }
                return@execute
            }

            val queueFolder = File(queueRoot, folderName)
            queueFolder.deleteRecursively()
            queueFolder.mkdirs()
            File(queueFolder, "Gatt_READ_Diagnose.csv").writeText(csv)
            File(queueFolder, "Gatt_READ_Summary.txt").writeText(summary)
            File(queueFolder, "manifest.json").writeText(manifest.toString(2))
            val remoteDate = stamp.take(10)
            File(queueFolder, ".meta.json").writeText(
                JSONObject()
                    .put("remoteFolder", "$REMOTE_ROOT/$remoteDate/$folderName")
                    .toString()
            )

            val uploadResult = flushPending()
            mainHandler.post {
                onStatus(
                    when (uploadResult) {
                        UploadResult.UPLOADED -> "✓ Deep READ gespeichert + GitHub • ${frozen.size} Antworten"
                        UploadResult.LOCAL_ONLY -> "✓ Deep READ lokal gespeichert • ${frozen.size} Antworten"
                        UploadResult.PENDING -> "✓ Deep READ lokal gespeichert • GitHub-Upload wartet"
                    }
                )
            }
        }
    }

    private fun buildCsv(records: List<DiagnosticReadRecord>): String = buildString {
        appendLine("timestamp_ms;service_uuid;characteristic_uuid;short_id;status;length;hex;evidence;meaning")
        records.forEach { record ->
            appendLine(
                listOf(
                    record.timestampMs.toString(),
                    record.serviceUuid,
                    record.characteristicUuid,
                    record.shortId,
                    record.status.toString(),
                    record.length.toString(),
                    record.hex,
                    record.evidence,
                    record.meaning
                ).joinToString(";") { csvCell(it) }
            )
        }
    }

    private fun buildSummary(
        records: List<DiagnosticReadRecord>,
        deviceName: String,
        startedAt: Long,
        finishedAt: Long
    ): String {
        val successful = records.count { it.status == android.bluetooth.BluetoothGatt.GATT_SUCCESS }
        val nonEmpty = records.count { it.hex.isNotBlank() }
        val uniqueCharacteristics = records.map { "${it.serviceUuid}/${it.characteristicUuid}" }.toSet().size
        return buildString {
            appendLine("VMAX BT638 Deep READ")
            appendLine("Gerät: $deviceName")
            appendLine("Start: $startedAt")
            appendLine("Ende: $finishedAt")
            appendLine("Dauer_ms: ${finishedAt - startedAt}")
            appendLine("READ_Antworten: ${records.size}")
            appendLine("READ_Erfolgreich: $successful")
            appendLine("READ_Mit_Payload: $nonEmpty")
            appendLine("Characteristics: $uniqueCharacteristics")
            appendLine("Modus: STRICT_READ_ONLY")
            appendLine("BluetoothAdresse: nicht gespeichert")
            appendLine("Hinweis: READ-Daten werden niemals als Live-Telemetrie oder automatische Decoder-Bestätigung behandelt.")
        }
    }

    private fun buildManifest(
        records: List<DiagnosticReadRecord>,
        deviceName: String,
        folderName: String,
        startedAt: Long,
        finishedAt: Long
    ): JSONObject {
        val packageInfo = appContext.packageManager.getPackageInfo(appContext.packageName, 0)
        return JSONObject()
            .put("schema", "vmax-bt638-deep-read-v1")
            .put("diagnostic", folderName)
            .put("device", deviceName)
            .put("start_ms", startedAt)
            .put("end_ms", finishedAt)
            .put("read_responses", records.size)
            .put("read_success", records.count { it.status == android.bluetooth.BluetoothGatt.GATT_SUCCESS })
            .put("read_payloads", records.count { it.hex.isNotBlank() })
            .put("read_only", true)
            .put("bluetooth_address_included", false)
            .put("app_version", packageInfo.versionName.orEmpty())
            .put("created_at_ms", System.currentTimeMillis())
    }

    private fun flushPending(): UploadResult {
        if (!prefs.getBoolean("enabled", false)) return UploadResult.LOCAL_ONLY
        val token = tokenOrNull() ?: return UploadResult.LOCAL_ONLY
        val pending = queueRoot.listFiles()?.filter { it.isDirectory }?.sortedBy(File::lastModified).orEmpty()
        if (pending.isEmpty()) return UploadResult.UPLOADED
        val uploader = DiagnosticGitHubUploader(token, OWNER, REPO, DATA_BRANCH, BASE_BRANCH)
        return try {
            uploader.ensureDataBranch()
            pending.forEach { folder ->
                val meta = File(folder, ".meta.json").takeIf(File::isFile)
                    ?.let { JSONObject(it.readText()) } ?: return@forEach
                val remoteFolder = meta.getString("remoteFolder")
                // Manifest goes last, so the workflow path filter sees only a complete bundle.
                listOf("Gatt_READ_Diagnose.csv", "Gatt_READ_Summary.txt", "manifest.json")
                    .forEach { name ->
                        val file = File(folder, name)
                        if (file.isFile) uploader.uploadIfMissing("$remoteFolder/$name", file.readBytes(), folder.name)
                    }
                folder.deleteRecursively()
            }
            UploadResult.UPLOADED
        } catch (_: Throwable) {
            UploadResult.PENDING
        }
    }

    private fun tokenOrNull(): String? {
        val ivText = prefs.getString("token_iv", null) ?: return null
        val dataText = prefs.getString("token_data", null) ?: return null
        return runCatching {
            val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            val key = keyStore.getKey(TOKEN_KEY_ALIAS, null) as? SecretKey ?: return@runCatching null
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                key,
                GCMParameterSpec(128, Base64.decode(ivText, Base64.NO_WRAP))
            )
            String(cipher.doFinal(Base64.decode(dataText, Base64.NO_WRAP)), Charsets.UTF_8)
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    private fun writeDownloadFile(relativeFolder: String, fileName: String, mimeType: String, content: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, mimeType)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + relativeFolder)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val resolver = appContext.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("Datei $fileName konnte nicht angelegt werden")
            resolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(content) }
                ?: error("Datei $fileName konnte nicht geschrieben werden")
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } else {
            val base = appContext.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
                ?: error("Speicher nicht verfügbar")
            val folder = File(base, relativeFolder).apply { mkdirs() }
            File(folder, fileName).writeText(content)
        }
    }

    private fun csvCell(value: String): String {
        if (';' !in value && '\n' !in value && '\r' !in value && '"' !in value) return value
        return "\"${value.replace("\"", "\"\"")}\""
    }

    private fun safeMessage(error: Throwable): String =
        (error.message ?: error::class.java.simpleName)
            .replace(Regex("gh[pousr]_[A-Za-z0-9_]+"), "<token>")
            .replace(Regex("[\\r\\n]+"), " ")
            .take(180)

    private enum class UploadResult { UPLOADED, LOCAL_ONLY, PENDING }
}

private class DiagnosticGitHubUploader(
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

    fun uploadIfMissing(remotePath: String, bytes: ByteArray, diagnosticName: String) {
        val existing = request(
            "GET",
            "$apiBase/contents/${encodePath(remotePath)}?ref=${encodeSegment(branch)}"
        )
        if (existing.code in 200..299) return
        if (existing.code != 404) throw IOException("Dateiprüfung fehlgeschlagen (${existing.code})")
        val payload = JSONObject()
            .put("message", "BT638 Deep READ $diagnosticName: ${remotePath.substringAfterLast('/')}")
            .put("content", Base64.encodeToString(bytes, Base64.NO_WRAP))
            .put("branch", branch)
            .toString()
        val upload = request("PUT", "$apiBase/contents/${encodePath(remotePath)}", payload)
        if (upload.code !in 200..299) {
            throw IOException("GitHub Upload fehlgeschlagen (${upload.code})")
        }
    }

    private fun request(method: String, url: String, body: String? = null): HttpResult {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            setRequestProperty("User-Agent", "VMAXDashboard-DeepRead")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
        }
        try {
            if (body != null) connection.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(body) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            return HttpResult(code, response)
        } finally {
            connection.disconnect()
        }
    }

    private fun encodePath(path: String): String = path.split('/').joinToString("/") { encodeSegment(it) }
    private fun encodeSegment(value: String): String =
        URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")

    private data class HttpResult(val code: Int, val body: String)
}

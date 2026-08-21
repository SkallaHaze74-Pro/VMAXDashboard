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
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal val REQUIRED_STANDALONE_DIAGNOSTIC_FILES = setOf(
    DIAGNOSTIC_READ_CSV_FILE,
    DIAGNOSTIC_READ_SUMMARY_FILE,
    "manifest.json",
    ".meta.json"
)

internal fun isCompleteStandaloneDiagnosticQueue(fileNames: Set<String>): Boolean =
    REQUIRED_STANDALONE_DIAGNOSTIC_FILES.all(fileNames::contains)

private const val STAGED_LOCAL_CSV = "local_read.csv"
private const val STAGED_LOCAL_SUMMARY = "local_summary.txt"
private const val STAGED_LOCAL_MANIFEST = "local_manifest.json"
private const val STAGED_PUBLIC_CSV = "public_read.csv"
private const val STAGED_PUBLIC_SUMMARY = "public_summary.txt"
private const val STAGED_PUBLIC_MANIFEST = "public_manifest.json"
private const val STAGED_META = ".stage.json"

internal val REQUIRED_DURABLE_DIAGNOSTIC_STAGE_FILES = setOf(
    STAGED_LOCAL_CSV,
    STAGED_LOCAL_SUMMARY,
    STAGED_LOCAL_MANIFEST,
    STAGED_PUBLIC_CSV,
    STAGED_PUBLIC_SUMMARY,
    STAGED_PUBLIC_MANIFEST,
    STAGED_META
)

internal fun isCompleteDurableDiagnosticStage(fileNames: Set<String>): Boolean =
    REQUIRED_DURABLE_DIAGNOSTIC_STAGE_FILES.all(fileNames::contains)

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
    private val durableStageRoot = File(appContext.filesDir, "diagnostic_read_exact_staging").apply { mkdirs() }
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val started = AtomicBoolean(false)

    fun start() {
        if (!started.compareAndSet(false, true)) return
        executor.execute {
            promoteCompleteHiddenStages()
            recoverDurableStages()
            flushPending()
        }
    }

    fun retryPending() {
        executor.execute {
            promoteCompleteHiddenStages()
            recoverDurableStages()
            flushPending()
        }
    }

    fun pendingCount(): Int =
        (queueRoot.listFiles()?.count { it.isDirectory && !it.name.startsWith(".") } ?: 0) +
            (durableStageRoot.listFiles()?.count { it.isDirectory && !it.name.startsWith(".") } ?: 0)

    fun saveAndPublish(
        bundle: DiagnosticReadBundle,
        onStatus: (String) -> Unit = {}
    ) {
        if (bundle.records.isEmpty()) {
            mainHandler.post { onStatus("READ-Scan fertig • keine READ-Antworten gespeichert") }
            return
        }
        val frozen = bundle.copy(records = bundle.records.toList())
        val durableStage = runCatching { stageBundleDurably(frozen) }
            .getOrElse { error ->
                mainHandler.post {
                    onStatus("READ-Dump konnte nicht atomar vorgemerkt werden: ${safeMessage(error)}")
                }
                return
            }
        executor.execute {
            val callbacks = runCatching {
                JSONObject(File(durableStage, STAGED_META).readText()).optInt("callbacks")
            }.getOrDefault(diagnosticReadCounts(frozen.records).callbacks)
            val processed = runCatching { processDurableStage(durableStage) }
            if (processed.isFailure) {
                val error = processed.exceptionOrNull() ?: IllegalStateException("Unbekannter Stage-Fehler")
                mainHandler.post {
                    onStatus(
                        "READ-Dump bleibt atomar vorgemerkt; Export wartet: ${safeMessage(error)}"
                    )
                }
                return@execute
            }
            val uploadResult = flushPending()
            mainHandler.post {
                onStatus(
                    when (uploadResult) {
                        UploadResult.UPLOADED -> "✓ Deep READ gespeichert + GitHub • $callbacks Callbacks"
                        UploadResult.LOCAL_ONLY -> "✓ Deep READ lokal gespeichert • $callbacks Callbacks"
                        UploadResult.PENDING -> "✓ Deep READ lokal gespeichert • GitHub-Upload wartet"
                    }
                )
            }
        }
    }

    @Synchronized
    private fun stageBundleDurably(bundle: DiagnosticReadBundle): File {
        val folderName = diagnosticReadFolderName(bundle)
        val target = File(durableStageRoot, folderName)
        if (target.isDirectory && isCompleteDurableStage(target)) return target
        check(!target.exists()) { "Durables Diagnoseziel existiert unvollständig: $folderName" }
        val staging = File(durableStageRoot, ".$folderName.staging-${System.nanoTime()}")
        check(staging.mkdirs()) { "Durables Diagnose-Staging konnte nicht angelegt werden" }
        val records = diagnosticRecordsForBundles(listOf(bundle))
        val remoteDate = folderName.removePrefix("DeepRead_").take(10)
        try {
            File(staging, STAGED_LOCAL_CSV).writeText(
                buildDiagnosticReadCsv(records, DiagnosticReadRepresentation.LOCAL_EXACT)
            )
            File(staging, STAGED_LOCAL_SUMMARY).writeText(
                buildDiagnosticReadSummary(listOf(bundle), DiagnosticReadRepresentation.LOCAL_EXACT)
            )
            File(staging, STAGED_LOCAL_MANIFEST).writeText(
                buildManifest(bundle, folderName, DiagnosticReadRepresentation.LOCAL_EXACT).toString(2)
            )
            File(staging, STAGED_PUBLIC_CSV).writeText(
                buildDiagnosticReadCsv(records, DiagnosticReadRepresentation.PUBLIC_REDACTED)
            )
            File(staging, STAGED_PUBLIC_SUMMARY).writeText(
                buildDiagnosticReadSummary(listOf(bundle), DiagnosticReadRepresentation.PUBLIC_REDACTED)
            )
            File(staging, STAGED_PUBLIC_MANIFEST).writeText(
                buildManifest(bundle, folderName, DiagnosticReadRepresentation.PUBLIC_REDACTED).toString(2)
            )
            File(staging, STAGED_META).writeText(
                JSONObject()
                    .put("folderName", folderName)
                    .put("localFolder", "VMAXDashboard/$folderName")
                    .put("remoteDate", remoteDate)
                    .put("scanId", bundle.scanId)
                    .put("callbacks", diagnosticReadCounts(records).callbacks)
                    .toString()
            )
            check(isCompleteDurableStage(staging)) { "Durables Diagnose-Staging ist unvollständig" }
            check(staging.renameTo(target)) { "Durables Diagnose-Staging konnte nicht atomar übernommen werden" }
            return target
        } catch (error: Throwable) {
            staging.deleteRecursively()
            throw error
        }
    }

    private fun recoverDurableStages() {
        durableStageRoot.listFiles()
            ?.filter { it.isDirectory && !it.name.startsWith(".") }
            ?.sortedBy(File::lastModified)
            .orEmpty()
            .forEach { stage -> runCatching { processDurableStage(stage) } }
    }

    /** Recovers a process death after all files were written but before directory rename. */
    private fun promoteCompleteHiddenStages() {
        durableStageRoot.listFiles()
            ?.filter { it.isDirectory && it.name.startsWith(".") && isCompleteDurableStage(it) }
            .orEmpty()
            .forEach { staging ->
                runCatching {
                    val meta = JSONObject(File(staging, STAGED_META).readText())
                    val target = File(durableStageRoot, meta.getString("folderName"))
                    when {
                        target.isDirectory && isCompleteDurableStage(target) -> staging.deleteRecursively()
                        !target.exists() -> check(staging.renameTo(target)) {
                            "Vollständiges Diagnose-Staging konnte nicht wiederhergestellt werden"
                        }
                    }
                }
            }
    }

    private fun processDurableStage(stage: File) {
        check(isCompleteDurableStage(stage)) { "Durables Diagnose-Staging ist unvollständig" }
        val meta = JSONObject(File(stage, STAGED_META).readText())
        val folderName = meta.getString("folderName")
        val localFolder = meta.getString("localFolder")
        val scanId = meta.getString("scanId")
        val queueFolder = queueFolderFor(scanId, folderName)

        // App-private exact staging remains the recovery source until all local
        // files and the complete public queue directory exist.
        writeDownloadFile(
            localFolder,
            DIAGNOSTIC_READ_CSV_FILE,
            "text/csv",
            File(stage, STAGED_LOCAL_CSV).readText()
        )
        writeDownloadFile(
            localFolder,
            DIAGNOSTIC_READ_SUMMARY_FILE,
            "text/plain",
            File(stage, STAGED_LOCAL_SUMMARY).readText()
        )
        writeDownloadFile(
            localFolder,
            "manifest.json",
            "application/json",
            File(stage, STAGED_LOCAL_MANIFEST).readText()
        )
        writeQueueAtomically(
            target = queueFolder,
            files = mapOf(
                DIAGNOSTIC_READ_CSV_FILE to File(stage, STAGED_PUBLIC_CSV).readText(),
                DIAGNOSTIC_READ_SUMMARY_FILE to File(stage, STAGED_PUBLIC_SUMMARY).readText(),
                "manifest.json" to File(stage, STAGED_PUBLIC_MANIFEST).readText(),
                ".meta.json" to JSONObject()
                    .put(
                        "remoteFolder",
                        "$REMOTE_ROOT/${meta.getString("remoteDate")}/${queueFolder.name}"
                    )
                    .put("scanId", scanId)
                    .toString()
            )
        )
        check(isCompleteQueueFolder(queueFolder)) { "Öffentliche Diagnosequeue ist unvollständig" }
        check(stage.deleteRecursively()) { "Durables Diagnose-Staging konnte nicht bereinigt werden" }
    }

    private fun isCompleteDurableStage(folder: File): Boolean =
        isCompleteDurableDiagnosticStage(
            folder.listFiles()?.filter(File::isFile)?.map(File::getName)?.toSet().orEmpty()
        )

    private fun buildManifest(
        bundle: DiagnosticReadBundle,
        folderName: String,
        representation: DiagnosticReadRepresentation
    ): JSONObject {
        val packageInfo = appContext.packageManager.getPackageInfo(appContext.packageName, 0)
        val records = diagnosticRecordsForBundles(listOf(bundle))
        val counts = diagnosticReadCounts(records)
        val public = representation == DiagnosticReadRepresentation.PUBLIC_REDACTED
        val deviceIdentity = bundle.deviceName.ifBlank { "BT638" }
        return JSONObject()
            .put("schema", "vmax-bt638-deep-read-v3")
            .put("diagnostic", folderName)
            .put("scan_id", bundle.scanId)
            .put(
                "device",
                if (public) diagnosticPublicDeviceLabel(deviceIdentity) else deviceIdentity
            )
            .put("start_ms", bundle.scanStartedAt)
            .put("end_ms", bundle.scanFinishedAt)
            .put("connection_epoch", bundle.connectionEpoch)
            .put("completed", bundle.completed)
            .put("completion_outcome", bundle.completionOutcome.name)
            .put("read_attempts", counts.attempts)
            .put("read_callbacks", counts.callbacks)
            // Backwards-compatible alias now has the strict callback meaning.
            .put("read_responses", counts.callbacks)
            .put("read_success", counts.successes)
            .put("read_payload_callbacks", counts.payloadCallbacks)
            .put("read_valid_payloads", counts.validPayloads)
            .put("advertisement_payloads", counts.observationPayloads)
            .put("observations", counts.observations)
            .put("read_only", true)
            .put("bluetooth_address_included", false)
            .put("public_identity_redacted", public)
            .put("public_payload_redaction", "identity/free-form/advertisement SHA-256")
            .put(
                "public_hash_privacy",
                "unsalted SHA-256 pseudonym; same payload is linkable across public uploads"
            )
            .put("app_version", packageInfo.versionName.orEmpty())
            .put("created_at_ms", System.currentTimeMillis())
    }

    /** Never delete an unrelated pending scan when a filename collision occurs. */
    private fun queueFolderFor(scanId: String, preferredName: String): File {
        val preferred = File(queueRoot, preferredName)
        if (!preferred.exists()) return preferred
        val existingScanId = runCatching {
            File(preferred, ".meta.json").takeIf(File::isFile)
                ?.let { JSONObject(it.readText()).optString("scanId") }
        }.getOrNull()
        if (existingScanId == scanId && isCompleteQueueFolder(preferred)) return preferred

        val suffix = diagnosticPayloadSha256(scanId).take(12)
        repeat(100) { index ->
            val extra = if (index == 0) "" else "_$index"
            val collisionSafe = File(queueRoot, "${preferredName}_${suffix}$extra")
            if (!collisionSafe.exists()) return collisionSafe
            val collisionScanId = runCatching {
                File(collisionSafe, ".meta.json").takeIf(File::isFile)
                    ?.let { JSONObject(it.readText()).optString("scanId") }
            }.getOrNull()
            if (collisionScanId == scanId && isCompleteQueueFolder(collisionSafe)) {
                return collisionSafe
            }
        }
        error("READ-Dump-Ordnerkollision für $scanId")
    }

    private fun writeQueueAtomically(target: File, files: Map<String, String>) {
        if (target.isDirectory && isCompleteQueueFolder(target)) return
        check(!target.exists()) { "Diagnose-Queue-Ziel existiert unvollständig: ${target.name}" }
        val staging = File(queueRoot, ".${target.name}.staging-${System.nanoTime()}")
        check(staging.mkdirs()) { "Diagnose-Staging konnte nicht angelegt werden" }
        try {
            files.forEach { (name, content) -> File(staging, name).writeText(content) }
            check(isCompleteQueueFolder(staging)) { "Diagnose-Staging ist unvollständig" }
            check(staging.renameTo(target)) { "Diagnose-Staging konnte nicht atomar übernommen werden" }
        } catch (error: Throwable) {
            staging.deleteRecursively()
            throw error
        }
    }

    private fun isCompleteQueueFolder(folder: File): Boolean =
        isCompleteStandaloneDiagnosticQueue(
            folder.listFiles()?.filter(File::isFile)?.map(File::getName)?.toSet().orEmpty()
        )

    private fun flushPending(): UploadResult {
        if (!prefs.getBoolean("enabled", false)) return UploadResult.LOCAL_ONLY
        val token = tokenOrNull() ?: return UploadResult.LOCAL_ONLY
        val pending = queueRoot.listFiles()
            ?.filter { it.isDirectory && !it.name.startsWith(".") }
            ?.sortedBy(File::lastModified)
            .orEmpty()
        if (pending.isEmpty()) return UploadResult.UPLOADED
        val uploader = DiagnosticGitHubUploader(token, OWNER, REPO, DATA_BRANCH, BASE_BRANCH)
        var incompleteFound = false
        return try {
            uploader.ensureDataBranch()
            pending.forEach { folder ->
                if (!isCompleteQueueFolder(folder)) {
                    incompleteFound = true
                    return@forEach
                }
                val meta = File(folder, ".meta.json").takeIf(File::isFile)
                    ?.let { JSONObject(it.readText()) } ?: return@forEach
                val remoteFolder = meta.getString("remoteFolder")
                // Manifest goes last, so the workflow path filter sees only a complete bundle.
                listOf(DIAGNOSTIC_READ_CSV_FILE, DIAGNOSTIC_READ_SUMMARY_FILE, "manifest.json")
                    .forEach { name ->
                        val file = File(folder, name)
                        if (file.isFile) {
                            val publicBytes = publicGitHubUploadBytes(name, file.readBytes())
                            uploader.uploadIfMissing("$remoteFolder/$name", publicBytes, folder.name)
                        }
                    }
                folder.deleteRecursively()
            }
            if (incompleteFound) UploadResult.PENDING else UploadResult.UPLOADED
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
            try {
                resolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(content) }
                    ?: error("Datei $fileName konnte nicht geschrieben werden")
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                check(resolver.update(uri, values, null, null) == 1) {
                    "Datei $fileName konnte nicht veröffentlicht werden"
                }
            } catch (error: Throwable) {
                runCatching { resolver.delete(uri, null, null) }
                throw error
            }
        } else {
            val base = appContext.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
                ?: error("Speicher nicht verfügbar")
            val folder = File(base, relativeFolder).apply { mkdirs() }
            File(folder, fileName).writeText(content)
        }
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

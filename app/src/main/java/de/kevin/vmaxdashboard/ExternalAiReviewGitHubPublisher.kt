package de.kevin.vmaxdashboard

import android.content.Context
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.KeyStore
import java.util.concurrent.Executors
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal fun buildExternalAiReviewJson(
    answer: ExternalAiAnswer,
    evidenceFingerprint: String,
    reason: String,
    generatedAtMs: Long
): JSONObject = JSONObject()
    .put("schema", "vmax-external-ai-review-v1")
    .put("advisoryOnly", true)
    .put("generatedAtMs", generatedAtMs)
    .put("reason", reason.take(200))
    .put("evidenceFingerprint", evidenceFingerprint.take(500))
    .put("provider", answer.provider.take(160))
    .put("model", answer.model.take(160))
    .put("fallbackUsed", answer.fallbackUsed)
    .put("providerCount", answer.providerCount)
    .put("text", prepareExternalAiReviewForPublication(answer.text))

/**
 * Mirrors the latest local Gemini/GLM review to telemetry-data so it can be
 * inspected from GitHub together with the deterministic decoder evidence.
 *
 * Only the already-sanitized review result, provider/model metadata and the
 * evidence fingerprint are uploaded. Provider API keys are never read here.
 */
class ExternalAiReviewGitHubPublisher private constructor(context: Context) {
    companion object {
        private const val GITHUB_PREFS = "vmax_github_sync"
        private const val REVIEW_PREFS = "vmax_external_ai_auto_review"
        private const val TOKEN_KEY_ALIAS = "vmax_github_sync_token"
        private const val OWNER = "SkallaHaze74-Pro"
        private const val REPO = "VMAXDashboard"
        private const val BRANCH = "telemetry-data"
        private const val REMOTE_PATH = "decoder-ai/app_provider_review_latest.json"

        @Volatile private var instance: ExternalAiReviewGitHubPublisher? = null

        fun get(context: Context): ExternalAiReviewGitHubPublisher =
            instance ?: synchronized(this) {
                instance ?: ExternalAiReviewGitHubPublisher(context.applicationContext)
                    .also { instance = it }
            }
    }

    private val appContext = context.applicationContext
    private val githubPrefs = appContext.getSharedPreferences(GITHUB_PREFS, Context.MODE_PRIVATE)
    private val reviewPrefs = appContext.getSharedPreferences(REVIEW_PREFS, Context.MODE_PRIVATE)
    private val executor = Executors.newSingleThreadExecutor()

    fun publishLatest(
        answer: ExternalAiAnswer,
        evidenceFingerprint: String,
        reason: String,
        generatedAtMs: Long
    ) {
        executor.execute {
            // Prevent a new GitHub commit on every app start / 60-second poll for
            // exactly the same completed local review.
            if (generatedAtMs > 0L && reviewPrefs.getLong("github_published_review_run_at", 0L) == generatedAtMs) {
                return@execute
            }
            if (!githubPrefs.getBoolean("enabled", false)) {
                setPublishStatus("KI-Review lokal gespeichert • GitHub-Sync ist aus")
                return@execute
            }
            val token = tokenOrNull() ?: run {
                setPublishStatus("KI-Review lokal gespeichert • GitHub-Token fehlt")
                return@execute
            }

            runCatching {
                val json = buildExternalAiReviewJson(
                    answer = answer,
                    evidenceFingerprint = evidenceFingerprint,
                    reason = reason,
                    generatedAtMs = generatedAtMs
                )
                upsert(token, json.toString(2).toByteArray(Charsets.UTF_8))
            }
                .onSuccess {
                    reviewPrefs.edit()
                        .putString("github_publish_status", "✓ KI-Review mit GitHub geteilt")
                        .putLong("github_publish_at", System.currentTimeMillis())
                        .putLong("github_published_review_run_at", generatedAtMs)
                        .apply()
                }
                .onFailure { error ->
                    setPublishStatus("KI-Review GitHub wartet: ${safeMessage(error)}")
                }
        }
    }

    private fun upsert(token: String, bytes: ByteArray) {
        val apiBase = "https://api.github.com/repos/$OWNER/$REPO"
        val encodedPath = REMOTE_PATH.split('/').joinToString("/") { encodeSegment(it) }
        val lookupUrl = "$apiBase/contents/$encodedPath?ref=${encodeSegment(BRANCH)}"
        val existing = request(token, "GET", lookupUrl)

        val payload = JSONObject()
            .put("message", "Decoder AI: lokale Gemini/GLM-Zweitprüfung aktualisieren")
            .put("content", Base64.encodeToString(bytes, Base64.NO_WRAP))
            .put("branch", BRANCH)

        when (existing.code) {
            in 200..299 -> {
                val sha = JSONObject(existing.body).optString("sha")
                if (sha.isBlank()) throw IOException("GitHub-Datei hat keine SHA")
                payload.put("sha", sha)
            }
            404 -> Unit
            else -> throw IOException("KI-Review Dateiprüfung fehlgeschlagen (${existing.code})")
        }

        val upload = request(token, "PUT", "$apiBase/contents/$encodedPath", payload.toString())
        if (upload.code !in 200..299) {
            val message = runCatching { JSONObject(upload.body).optString("message") }
                .getOrDefault("")
                .take(160)
            throw IOException("KI-Review Upload fehlgeschlagen (${upload.code})${if (message.isBlank()) "" else ": $message"}")
        }
    }

    private fun request(token: String, method: String, url: String, body: String? = null): HttpResult {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            setRequestProperty("User-Agent", "VMAXDashboard-ExternalAI-Review")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
        }
        try {
            if (body != null) {
                connection.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(body) }
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val responseBody = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            return HttpResult(code, responseBody)
        } finally {
            connection.disconnect()
        }
    }

    private fun tokenOrNull(): String? {
        val ivText = githubPrefs.getString("token_iv", null) ?: return null
        val dataText = githubPrefs.getString("token_data", null) ?: return null
        return runCatching {
            val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            val key = keyStore.getKey(TOKEN_KEY_ALIAS, null) as? SecretKey ?: return null
            require(key.algorithm == KeyProperties.KEY_ALGORITHM_AES)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                key,
                GCMParameterSpec(128, Base64.decode(ivText, Base64.NO_WRAP))
            )
            String(cipher.doFinal(Base64.decode(dataText, Base64.NO_WRAP)), Charsets.UTF_8)
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    private fun setPublishStatus(message: String) {
        reviewPrefs.edit()
            .putString("github_publish_status", message.take(240))
            .putLong("github_publish_at", System.currentTimeMillis())
            .apply()
    }

    private fun encodeSegment(value: String): String =
        URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")

    private fun safeMessage(error: Throwable): String =
        (error.message ?: error::class.java.simpleName)
            .replace(Regex("gh[pousr]_[A-Za-z0-9_]+"), "<token>")
            .take(170)

    private data class HttpResult(val code: Int, val body: String)
}

package de.kevin.vmaxdashboard

import android.content.Context
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class DecoderAiCloudStatus(
    val status: String,
    val lastRefreshAt: Long
)

class DecoderAiCloudSync private constructor(context: Context) {
    companion object {
        private const val PREFS = "vmax_github_sync"
        private const val TOKEN_KEY_ALIAS = "vmax_github_sync_token"
        private const val PROFILE_URL =
            "https://api.github.com/repos/SkallaHaze74-Pro/VMAXDashboard/contents/decoder-ai/decoder_profile.json?ref=telemetry-data"

        @Volatile private var instance: DecoderAiCloudSync? = null

        fun get(context: Context): DecoderAiCloudSync =
            instance ?: synchronized(this) {
                instance ?: DecoderAiCloudSync(context.applicationContext).also { instance = it }
            }
    }

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val adaptiveStore = AdaptiveDecoderProfileStore.get(appContext)
    private val executor = Executors.newSingleThreadScheduledExecutor()
    private val started = AtomicBoolean(false)

    fun start() {
        if (!started.compareAndSet(false, true)) return
        executor.execute(::refreshInternal)
        executor.scheduleWithFixedDelay(::refreshInternal, 60L, 60L, TimeUnit.SECONDS)
    }

    fun refreshNow() {
        start()
        executor.execute(::refreshInternal)
    }

    fun status(): DecoderAiCloudStatus = DecoderAiCloudStatus(
        status = prefs.getString("decoder_ai_status", "KI-Profil noch nicht synchronisiert").orEmpty(),
        lastRefreshAt = prefs.getLong("decoder_ai_last_refresh", 0L)
    )

    private fun refreshInternal() {
        if (!prefs.getBoolean("enabled", false)) return
        val token = tokenOrNull() ?: run {
            setStatus("Decoder AI wartet auf GitHub-Token")
            return
        }
        val connection = (URL(PROFILE_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            setRequestProperty("User-Agent", "VMAXDashboard-DecoderAI")
        }
        try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            when (code) {
                in 200..299 -> {
                    val github = JSONObject(body)
                    val encoded = github.optString("content")
                    if (encoded.isBlank()) error("Decoder-Profil ist leer")
                    val profileText = String(Base64.decode(encoded, Base64.DEFAULT), Charsets.UTF_8)
                    val snapshot = adaptiveStore.installCloudProfile(profileText)
                    setStatus(
                        "✓ Decoder AI ${snapshot.revision.ifBlank { "aktuell" }} • " +
                            "${snapshot.confirmedRuleCount}/${snapshot.ruleCount} Regeln bestätigt"
                    )
                    ExternalAiAutoReviewCoordinator.get(appContext)
                        .requestNow("Decoder-Profil aktualisiert")
                }
                404 -> setStatus("Decoder AI wartet auf die erste abgeschlossene GitHub-Auswertung")
                else -> setStatus("Decoder AI GitHub-Fehler $code")
            }
        } catch (error: Exception) {
            setStatus("Decoder AI wartet: ${safeMessage(error)}")
        } finally {
            connection.disconnect()
        }
    }

    private fun tokenOrNull(): String? {
        val ivText = prefs.getString("token_iv", null) ?: return null
        val dataText = prefs.getString("token_data", null) ?: return null
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

    private fun setStatus(message: String) {
        prefs.edit()
            .putString("decoder_ai_status", message.take(220))
            .putLong("decoder_ai_last_refresh", System.currentTimeMillis())
            .apply()
    }

    private fun safeMessage(error: Throwable): String =
        (error.message ?: error::class.java.simpleName)
            .replace(Regex("gh[pousr]_[A-Za-z0-9_]+"), "<token>")
            .take(160)
}

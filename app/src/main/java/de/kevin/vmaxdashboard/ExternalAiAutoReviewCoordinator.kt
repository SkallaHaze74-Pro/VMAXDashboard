package de.kevin.vmaxdashboard

import android.content.Context
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

data class ExternalAiAutoReviewSnapshot(
    val enabled: Boolean,
    val running: Boolean,
    val status: String,
    val lastProvider: String,
    val lastModel: String,
    val lastResult: String,
    val lastRunAt: Long,
    val nextRetryAt: Long
)

/**
 * Runs the external decoder second-opinion automatically when useful project
 * evidence changes. It never receives BluetoothGatt and cannot write to the scooter.
 */
class ExternalAiAutoReviewCoordinator private constructor(context: Context) {
    companion object {
        private const val PREFS = "vmax_external_ai_auto_review"
        private const val CHECK_INTERVAL_SECONDS = 60L
        private const val RATE_LIMIT_RETRY_MS = 15L * 60L * 1000L
        private const val TRANSIENT_RETRY_MS = 2L * 60L * 1000L
        private const val MAX_STORED_RESULT_CHARS = 18_000
        private const val DEFAULT_PROMPT =
            "Prüfe den aktuellen Decoder-Status automatisch. Welche Evidenz ist belastbar, wo gibt es Widersprüche und welcher nächste Messfahrt-Test bringt am meisten Erkenntnis?"

        @Volatile private var instance: ExternalAiAutoReviewCoordinator? = null

        fun get(context: Context): ExternalAiAutoReviewCoordinator =
            instance ?: synchronized(this) {
                instance ?: ExternalAiAutoReviewCoordinator(context.applicationContext)
                    .also { instance = it }
            }
    }

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val secrets = ExternalAiSecretsStore(appContext)
    private val client = ExternalAiClient(secrets)
    private val sync = GitHubTelemetrySync.get(appContext)
    private val adaptiveStore = AdaptiveDecoderProfileStore.get(appContext)
    private val reviewPublisher = ExternalAiReviewGitHubPublisher.get(appContext)
    private val executor = Executors.newSingleThreadScheduledExecutor()
    private val started = AtomicBoolean(false)
    private val running = AtomicBoolean(false)

    fun start() {
        if (!started.compareAndSet(false, true)) return
        executor.execute { runIfNeeded(force = false, reason = "App-Start") }
        executor.scheduleWithFixedDelay(
            { runIfNeeded(force = false, reason = "Auto") },
            CHECK_INTERVAL_SECONDS,
            CHECK_INTERVAL_SECONDS,
            TimeUnit.SECONDS
        )
    }

    fun requestNow(reason: String = "Manuell angestoßen") {
        start()
        executor.execute { runIfNeeded(force = true, reason = reason) }
    }

    fun setEnabled(value: Boolean) {
        prefs.edit().putBoolean("enabled", value).apply()
        if (value) requestNow("Automatik aktiviert")
        else setStatus("Automatische Gemini/GLM-Prüfung ist aus")
    }

    fun snapshot(): ExternalAiAutoReviewSnapshot = ExternalAiAutoReviewSnapshot(
        enabled = prefs.getBoolean("enabled", true),
        running = running.get(),
        status = prefs.getString("status", "Automatische KI-Prüfung wartet auf Daten oder API-Key").orEmpty(),
        lastProvider = prefs.getString("last_provider", "").orEmpty(),
        lastModel = prefs.getString("last_model", "").orEmpty(),
        lastResult = prefs.getString("last_result", "").orEmpty(),
        lastRunAt = prefs.getLong("last_run_at", 0L),
        nextRetryAt = prefs.getLong("next_retry_at", 0L)
    )

    private fun runIfNeeded(force: Boolean, reason: String) {
        if (!prefs.getBoolean("enabled", true)) return
        val keyStatus = secrets.status()
        if (!keyStatus.geminiConfigured && !keyStatus.glmConfigured) {
            setStatus("Automatische KI-Prüfung wartet auf Gemini- oder GLM-Key")
            return
        }

        val now = System.currentTimeMillis()
        val nextRetryAt = prefs.getLong("next_retry_at", 0L)
        if (!force && nextRetryAt > now) return
        if (!running.compareAndSet(false, true)) return

        try {
            val syncSnapshot = sync.snapshot()
            val profile = adaptiveStore.snapshot()
            val fingerprint = fingerprint(syncSnapshot, profile)
            val lastSuccessFingerprint = prefs.getString("last_success_fingerprint", "").orEmpty()
            if (!force && fingerprint == lastSuccessFingerprint) return

            setStatus("Automatische KI-Prüfung läuft • $reason")
            val context = ExternalAiPromptFactory.decoderContext(syncSnapshot, profile)
            val answer = client.askBlocking(
                mode = ExternalAiMode.AUTO,
                prompt = DEFAULT_PROMPT,
                context = context
            )

            prefs.edit()
                .putString("last_success_fingerprint", fingerprint)
                .putString("last_provider", answer.provider)
                .putString("last_model", answer.model)
                .putString("last_result", answer.text.take(MAX_STORED_RESULT_CHARS))
                .putLong("last_run_at", now)
                .putLong("next_retry_at", 0L)
                .putString(
                    "status",
                    "✓ Automatisch geprüft • ${answer.provider} • ${answer.model}" +
                        if (answer.fallbackUsed) " • Fallback aktiv" else ""
                )
                .apply()

            // Mirrors only the sanitized review output + metadata. Provider keys
            // never leave ExternalAiSecretsStore and are not part of this payload.
            reviewPublisher.publishLatest(
                answer = answer,
                evidenceFingerprint = fingerprint,
                reason = reason,
                generatedAtMs = now
            )
        } catch (error: Throwable) {
            val message = (error.message ?: error::class.java.simpleName)
                .replace(Regex("[\\r\\n]+"), " ")
                .take(360)
            val retryDelay = when {
                "429" in message || "Quota" in message || "Rate-Limit" in message -> RATE_LIMIT_RETRY_MS
                "502" in message || "503" in message || "Zeitüberschreitung" in message -> TRANSIENT_RETRY_MS
                else -> TRANSIENT_RETRY_MS
            }
            prefs.edit()
                .putString("status", "Automatische KI-Prüfung wartet: $message")
                .putLong("last_run_at", now)
                .putLong("next_retry_at", now + retryDelay)
                .apply()
        } finally {
            running.set(false)
        }
    }

    private fun fingerprint(
        syncSnapshot: GitHubSyncSnapshot,
        profile: AdaptiveProfileSnapshot
    ): String = buildString {
        append(profile.revision)
        append('|')
        append(profile.generatedAtMs)
        append('|')
        append(profile.confirmedRuleCount)
        append('|')
        append(profile.ruleCount)
        append('|')
        append(syncSnapshot.uploadedBundles)
    }

    private fun setStatus(message: String) {
        prefs.edit().putString("status", message.take(400)).apply()
    }
}

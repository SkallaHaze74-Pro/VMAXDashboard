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
    val lastAttemptAt: Long,
    val lastAttemptError: String,
    val lastResultMatchesCurrentEvidence: Boolean,
    val nextRetryAt: Long
)

internal fun shouldPromoteExternalAiAnswer(mode: ExternalAiMode, providerCount: Int): Boolean =
    mode != ExternalAiMode.PRO_DUO || providerCount >= 2

private data class ExternalAiReviewInput(
    val mode: ExternalAiMode,
    val prompt: String,
    val context: String,
    val fingerprint: String
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
        executor.execute {
            publishStoredReviewIfAvailable()
            runIfNeeded(force = false, reason = "App-Start")
        }
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

    fun snapshot(): ExternalAiAutoReviewSnapshot {
        val storedFingerprint = prefs.getString("last_success_fingerprint", "").orEmpty()
        val currentFingerprint = currentReviewInput().fingerprint
        val rawStoredText = prefs.getString("last_result", "").orEmpty()
        val storedText = validStoredReviewOrNull()
        val storedMode = storedReviewMode()
        val storedProviderCount = storedProviderCount()
        val storedReviewIsPromotable = storedText != null &&
            shouldPromoteExternalAiAnswer(storedMode, storedProviderCount)
        return ExternalAiAutoReviewSnapshot(
            enabled = prefs.getBoolean("enabled", true),
            running = running.get(),
            status = if (rawStoredText.isNotBlank() && storedText == null) {
                "Gespeicherte unvollständige KI-Analyse verworfen • vollständige Prüfung wird erneuert"
            } else {
                prefs.getString("status", "Automatische KI-Prüfung wartet auf Daten oder API-Key").orEmpty()
            },
            lastProvider = if (storedReviewIsPromotable) {
                prefs.getString("last_provider", "").orEmpty()
            } else "",
            lastModel = if (storedReviewIsPromotable) {
                prefs.getString("last_model", "").orEmpty()
            } else "",
            lastResult = if (storedReviewIsPromotable) storedText.orEmpty() else "",
            lastRunAt = if (storedReviewIsPromotable) prefs.getLong("last_run_at", 0L) else 0L,
            lastAttemptAt = prefs.getLong("last_attempt_at", 0L),
            lastAttemptError = prefs.getString("last_attempt_error", "").orEmpty(),
            lastResultMatchesCurrentEvidence = storedReviewIsPromotable &&
                storedFingerprint.isNotBlank() &&
                storedFingerprint == currentFingerprint,
            nextRetryAt = prefs.getLong("next_retry_at", 0L)
        )
    }

    private fun publishStoredReviewIfAvailable() {
        val text = validStoredReviewOrNull() ?: return
        val provider = prefs.getString("last_provider", "").orEmpty()
        val model = prefs.getString("last_model", "").orEmpty()
        val runAt = prefs.getLong("last_run_at", 0L)
        val evidenceFingerprint = prefs.getString("last_success_fingerprint", "").orEmpty()
        val storedMode = storedReviewMode()
        val providerCount = storedProviderCount()
        if (
            provider.isBlank() ||
            model.isBlank() ||
            runAt <= 0L ||
            !shouldPromoteExternalAiAnswer(storedMode, providerCount)
        ) return
        if (evidenceFingerprint.isBlank() ||
            evidenceFingerprint != currentReviewInput().fingerprint
        ) return

        reviewPublisher.publishLatest(
            answer = ExternalAiAnswer(
                mode = storedMode,
                provider = provider,
                model = model,
                text = text,
                fallbackUsed = prefs.getBoolean("last_fallback_used", false),
                providerCount = providerCount
            ),
            evidenceFingerprint = evidenceFingerprint,
            reason = "Gespeicherte Analyse nach App-Start",
            generatedAtMs = runAt
        )
    }

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

        var attemptedFingerprint = ""
        try {
            val syncSnapshot = sync.snapshot()
            val profile = adaptiveStore.snapshot()
            val reviewInput = buildReviewInput(syncSnapshot, profile, keyStatus)
            attemptedFingerprint = reviewInput.fingerprint
            val lastSuccessFingerprint = prefs.getString("last_success_fingerprint", "").orEmpty()
            val hasMatchingCompleteReview = reviewInput.fingerprint == lastSuccessFingerprint &&
                validStoredReviewOrNull() != null &&
                shouldPromoteExternalAiAnswer(storedReviewMode(), storedProviderCount())
            if (!force && hasMatchingCompleteReview) return

            setStatus("Automatische KI-Prüfung läuft • $reason")
            val answer = client.askBlocking(
                mode = reviewInput.mode,
                prompt = reviewInput.prompt,
                context = reviewInput.context
            )
            val completedAt = System.currentTimeMillis()
            val validatedAnswer = answer.copy(text = requireCompleteExternalAiReview(answer.text))

            if (!shouldPromoteExternalAiAnswer(reviewInput.mode, validatedAnswer.providerCount)) {
                storePartialDuoAttempt(validatedAnswer, reviewInput.fingerprint, completedAt)
                return
            }

            prefs.edit()
                .putString("last_success_fingerprint", reviewInput.fingerprint)
                .putString("last_provider", validatedAnswer.provider)
                .putString("last_model", validatedAnswer.model)
                .putString("last_result", validatedAnswer.text)
                .putString("last_mode", reviewInput.mode.name)
                .putInt("last_provider_count", validatedAnswer.providerCount)
                .putBoolean("last_fallback_used", validatedAnswer.fallbackUsed)
                .putLong("last_run_at", completedAt)
                .putLong("last_attempt_at", completedAt)
                .remove("last_attempt_error")
                .putLong("next_retry_at", 0L)
                .putString(
                    "status",
                    "✓ Automatisch geprüft • ${validatedAnswer.provider} • ${validatedAnswer.model}" +
                        if (validatedAnswer.fallbackUsed) " • Fallback aktiv" else ""
                )
                .apply()

            // Mirrors only the sanitized review output + metadata. Provider keys
            // never leave ExternalAiSecretsStore and are not part of this payload.
            reviewPublisher.publishLatest(
                answer = validatedAnswer,
                evidenceFingerprint = reviewInput.fingerprint,
                reason = reason,
                generatedAtMs = completedAt
            )
        } catch (error: Throwable) {
            val failedAt = System.currentTimeMillis()
            val message = (error.message ?: error::class.java.simpleName)
                .replace(Regex("[\\r\\n]+"), " ")
                .take(360)
            val lower = message.lowercase()
            val retryDelay = when {
                "429" in message || "quota" in lower || "rate-limit" in lower -> RATE_LIMIT_RETRY_MS
                "408" in message || "500" in message || "502" in message ||
                    "503" in message || "504" in message || "timeout" in lower ||
                    "zeitüberschreitung" in lower || "high demand" in lower -> TRANSIENT_RETRY_MS
                else -> TRANSIENT_RETRY_MS
            }
            val hasLastGoodReview = validStoredReviewOrNull() != null &&
                shouldPromoteExternalAiAnswer(storedReviewMode(), storedProviderCount())
            val lastGoodMatchesAttempt = attemptedFingerprint.isNotBlank() &&
                attemptedFingerprint == prefs.getString("last_success_fingerprint", "").orEmpty()
            prefs.edit()
                // Keep last_run_at as the timestamp of the last successful review.
                // A failed refresh is diagnostic metadata, not a new successful run.
                .putLong("last_attempt_at", failedAt)
                .putString("last_attempt_error", message)
                .putString(
                    "status",
                    if (hasLastGoodReview && lastGoodMatchesAttempt) {
                        "✓ Letzte vollständige KI-Analyse für denselben Evidenzstand bleibt gültig • Aktualisierung folgt"
                    } else if (hasLastGoodReview) {
                        "Neue Evidenz noch nicht geprüft • alte KI-Analyse nur historisch • neuer Versuch folgt"
                    } else {
                        "Automatische KI-Prüfung wartet auf Provider • neuer Versuch automatisch"
                    }
                )
                .putLong("next_retry_at", failedAt + retryDelay)
                .apply()
        } finally {
            running.set(false)
        }
    }

    private fun storePartialDuoAttempt(
        answer: ExternalAiAnswer,
        fingerprint: String,
        completedAt: Long
    ) {
        val hasLastComplete = validStoredReviewOrNull() != null &&
            shouldPromoteExternalAiAnswer(storedReviewMode(), storedProviderCount())
        prefs.edit()
            .putString("last_partial_result", answer.text)
            .putString("last_partial_provider", answer.provider)
            .putString("last_partial_model", answer.model)
            .putString("last_partial_fingerprint", fingerprint)
            .putLong("last_partial_run_at", completedAt)
            .putLong("last_attempt_at", completedAt)
            .putString("last_attempt_error", "Pro Duo unvollständig: nur eine Provider-Antwort verfügbar")
            .putLong("next_retry_at", completedAt + TRANSIENT_RETRY_MS)
            .putString(
                "status",
                if (hasLastComplete) {
                    "Pro Duo nur teilweise verfügbar • letzte vollständige Duo-Analyse bleibt erhalten"
                } else {
                    "Pro Duo nur teilweise verfügbar • vollständige Zwei-Provider-Prüfung wird erneut versucht"
                }
            )
            .apply()
    }

    private fun currentReviewInput(): ExternalAiReviewInput = buildReviewInput(
        sync.snapshot(),
        adaptiveStore.snapshot(),
        secrets.status()
    )

    private fun buildReviewInput(
        syncSnapshot: GitHubSyncSnapshot,
        profile: AdaptiveProfileSnapshot,
        keyStatus: ExternalAiSecretStatus
    ): ExternalAiReviewInput {
        val mode = if (keyStatus.geminiConfigured && keyStatus.glmConfigured) {
            ExternalAiMode.PRO_DUO
        } else {
            ExternalAiMode.AUTO
        }
        val context = ExternalAiPromptFactory.decoderContext(syncSnapshot, profile)
        return ExternalAiReviewInput(
            mode = mode,
            prompt = DEFAULT_PROMPT,
            context = context,
            fingerprint = ExternalAiPromptFactory.reviewInputFingerprint(
                prompt = DEFAULT_PROMPT,
                context = context,
                mode = mode
            )
        )
    }

    private fun validStoredReviewOrNull(): String? = runCatching {
        requireCompleteExternalAiReview(prefs.getString("last_result", "").orEmpty())
    }.getOrNull()

    private fun storedReviewMode(): ExternalAiMode {
        val stored = prefs.getString("last_mode", "").orEmpty()
        runCatching { ExternalAiMode.valueOf(stored) }.getOrNull()?.let { return it }
        val provider = prefs.getString("last_provider", "").orEmpty()
        return if ("Duo-Fallback" in provider || provider.startsWith("Gemini +")) {
            ExternalAiMode.PRO_DUO
        } else {
            ExternalAiMode.AUTO
        }
    }

    private fun storedProviderCount(): Int {
        if (prefs.contains("last_provider_count")) {
            return prefs.getInt("last_provider_count", 1).coerceAtLeast(0)
        }
        val provider = prefs.getString("last_provider", "").orEmpty()
        return if (provider.startsWith("Gemini +")) 2 else 1
    }

    private fun setStatus(message: String) {
        prefs.edit().putString("status", message.take(400)).apply()
    }
}

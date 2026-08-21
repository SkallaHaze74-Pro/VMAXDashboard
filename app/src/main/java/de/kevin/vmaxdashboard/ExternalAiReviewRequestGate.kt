package de.kevin.vmaxdashboard

internal data class ExternalAiQueuedReview(
    val force: Boolean,
    val reason: String
)

/**
 * Coalesces review requests before they reach a provider.
 *
 * A rapid second manual request is ignored while one request is queued or
 * running. Evidence-change requests are retained as one non-forced follow-up;
 * the coordinator's fingerprint guard then decides whether that follow-up
 * actually needs a provider call.
 */
internal class ExternalAiReviewRequestGate {
    private val monitor = Any()
    private var current: ExternalAiQueuedReview? = null
    private var pendingEvidenceReason: String? = null

    fun submit(force: Boolean, reason: String): Boolean = synchronized(monitor) {
        if (current == null) {
            current = ExternalAiQueuedReview(force = force, reason = reason)
            true
        } else {
            if (!force) pendingEvidenceReason = reason
            false
        }
    }

    fun current(): ExternalAiQueuedReview? = synchronized(monitor) { current }

    fun completeAndTakeNext(): ExternalAiQueuedReview? = synchronized(monitor) {
        val pendingReason = pendingEvidenceReason
        if (pendingReason == null) {
            current = null
        } else {
            pendingEvidenceReason = null
            current = ExternalAiQueuedReview(force = false, reason = pendingReason)
        }
        current
    }

    fun isBusy(): Boolean = synchronized(monitor) { current != null }
}

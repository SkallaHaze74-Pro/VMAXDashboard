package de.kevin.vmaxdashboard

internal enum class ReconnectTransition {
    UNEXPECTED_DISCONNECT,
    LINK_RESTORED,
    TELEMETRY_RESTORED,
    CLEAR_PENDING
}

/** Pure state machine so link-level and packet-level reconnects cannot be conflated. */
internal class ReconnectTransitionTracker(initialConnected: Boolean) {
    private var previousConnected = initialConnected
    private var awaitingLink = false
    private var awaitingTelemetry = false

    fun observe(connected: Boolean, telemetryReady: Boolean, recordingActive: Boolean): List<ReconnectTransition> {
        val transitions = mutableListOf<ReconnectTransition>()
        val wasConnected = previousConnected
        previousConnected = connected

        if (wasConnected && !connected && recordingActive) {
            awaitingLink = true
            awaitingTelemetry = false
            transitions += ReconnectTransition.UNEXPECTED_DISCONNECT
        }
        if (!wasConnected && connected && recordingActive && awaitingLink) {
            awaitingLink = false
            awaitingTelemetry = true
            transitions += ReconnectTransition.LINK_RESTORED
        }
        if (connected && telemetryReady && recordingActive && awaitingTelemetry) {
            awaitingTelemetry = false
            transitions += ReconnectTransition.TELEMETRY_RESTORED
        }
        if (!recordingActive && (awaitingLink || awaitingTelemetry)) {
            awaitingLink = false
            awaitingTelemetry = false
            transitions += ReconnectTransition.CLEAR_PENDING
        }
        return transitions
    }
}

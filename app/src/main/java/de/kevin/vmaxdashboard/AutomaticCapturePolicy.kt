package de.kevin.vmaxdashboard

internal enum class AutomaticCaptureAction {
    NONE,
    START_SCAN,
    START_MEASUREMENT
}

/**
 * Keeps the automatic recorder alive independently from one exported segment.
 * A deliberate manual disconnect clears [connectionDesired] and always wins.
 */
internal fun automaticCaptureAction(
    connectionDesired: Boolean,
    connected: Boolean,
    scanning: Boolean,
    recordingActive: Boolean,
    recordingDesired: Boolean,
    permissionsGranted: Boolean
): AutomaticCaptureAction = when {
    !connectionDesired -> AutomaticCaptureAction.NONE
    connected && recordingDesired && !recordingActive -> AutomaticCaptureAction.START_MEASUREMENT
    !connected && !scanning && permissionsGranted -> AutomaticCaptureAction.START_SCAN
    else -> AutomaticCaptureAction.NONE
}

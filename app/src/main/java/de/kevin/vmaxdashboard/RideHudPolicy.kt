package de.kevin.vmaxdashboard

internal enum class RideHudAction {
    NONE,
    REQUEST_OVERLAY_PERMISSION,
    SHOW_OVERLAY,
    HIDE_OVERLAY
}

internal enum class RideHudCaptureAction {
    NONE,
    STOP_HUD,
    START_SCAN,
    RESTART_SCAN,
    START_MEASUREMENT
}

internal const val MAX_RIDE_HUD_SCAN_LEASE_MS = 30_000L

/**
 * Periodic service policy. Unlike a StateFlow-only observer, this gets another
 * chance after Android Bluetooth is switched back on even when state stayed equal.
 */
internal fun rideHudCaptureAction(
    connectionDesired: Boolean,
    connected: Boolean,
    scanning: Boolean,
    scanStartedAtElapsedMs: Long,
    recordingActive: Boolean,
    recordingDesired: Boolean,
    permissionsGranted: Boolean,
    nowElapsedMs: Long
): RideHudCaptureAction {
    if (!connectionDesired || !permissionsGranted) return RideHudCaptureAction.STOP_HUD
    if (connected && recordingDesired && !recordingActive) {
        return RideHudCaptureAction.START_MEASUREMENT
    }
    if (connected) return RideHudCaptureAction.NONE
    if (!scanning) return RideHudCaptureAction.START_SCAN

    val scanAgeMs = nowElapsedMs - scanStartedAtElapsedMs
    val staleScan = scanStartedAtElapsedMs <= 0L || nowElapsedMs <= 0L ||
        scanAgeMs !in 0..MAX_RIDE_HUD_SCAN_LEASE_MS
    return if (staleScan) RideHudCaptureAction.RESTART_SCAN else RideHudCaptureAction.NONE
}

/**
 * Decides the next HUD lifecycle action. Permission settings are opened only
 * for an explicit enable action, so returning from settings cannot form a loop.
 */
internal fun rideHudAction(
    requestedEnabled: Boolean,
    overlayPermissionGranted: Boolean,
    overlayVisible: Boolean,
    userInitiated: Boolean
): RideHudAction = when {
    overlayVisible && (!requestedEnabled || !overlayPermissionGranted) -> RideHudAction.HIDE_OVERLAY
    !requestedEnabled -> RideHudAction.NONE
    overlayPermissionGranted && !overlayVisible -> RideHudAction.SHOW_OVERLAY
    overlayPermissionGranted -> RideHudAction.NONE
    userInitiated -> RideHudAction.REQUEST_OVERLAY_PERMISSION
    else -> RideHudAction.NONE
}

/** Preserves a synchronous start failure instead of replacing it with “active”. */
internal fun rideHudDeniedNotificationStatus(
    startAccepted: Boolean,
    overlayVisible: Boolean
): RideHudRuntimeState? {
    if (!startAccepted) return null
    return RideHudRuntimeState(
        active = overlayVisible,
        serviceRunning = overlayVisible,
        message = if (overlayVisible) {
            "Mini-HUD aktiv; Begleitmeldung nur im Android-Task-Manager"
        } else {
            "Mini-HUD wird gestartet; Begleitmeldung nur im Android-Task-Manager"
        }
    )
}

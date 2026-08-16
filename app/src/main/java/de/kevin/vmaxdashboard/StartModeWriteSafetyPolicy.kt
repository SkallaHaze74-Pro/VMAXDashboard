package de.kevin.vmaxdashboard

internal enum class StartModeWriteBlockReason {
    NOT_CONNECTED,
    TELEMETRY_NOT_READY,
    RECORDING_ACTIVE,
    WRITE_PENDING,
    OPERATION_BUSY,
    LEGACY_ROUTE_NOT_CONFIRMED,
    GATT_BUSY,
    SPEED_NOT_AVAILABLE,
    SPEED_FROM_PREVIOUS_CONNECTION,
    SPEED_SAMPLE_STALE,
    SCOOTER_MOVING
}

internal data class StartModeWriteSafetyInput(
    val connected: Boolean,
    val telemetryReady: Boolean,
    val recordingActive: Boolean,
    val startModeBusy: Boolean,
    val pendingStartModeWrite: Boolean,
    val legacyRouteConfirmed: Boolean,
    val gattBusy: Boolean,
    val speedKmh: Double?,
    val speedSampleAtElapsedMs: Long,
    val speedSampleConnectionEpoch: Long,
    val connectionEpoch: Long,
    val nowElapsedMs: Long
)

/** Pure, fail-closed policy for the motor-affecting Zero-/Kick-Start write. */
internal object StartModeWriteSafetyPolicy {
    const val MAX_SPEED_SAMPLE_AGE_MS = 2_000L
    const val MAX_STATIONARY_SPEED_KMH = 0.5

    fun blockReason(input: StartModeWriteSafetyInput): StartModeWriteBlockReason? {
        if (!input.connected) return StartModeWriteBlockReason.NOT_CONNECTED
        if (!input.telemetryReady) return StartModeWriteBlockReason.TELEMETRY_NOT_READY
        if (input.recordingActive) return StartModeWriteBlockReason.RECORDING_ACTIVE
        if (input.pendingStartModeWrite) return StartModeWriteBlockReason.WRITE_PENDING
        if (input.startModeBusy) return StartModeWriteBlockReason.OPERATION_BUSY
        if (!input.legacyRouteConfirmed) return StartModeWriteBlockReason.LEGACY_ROUTE_NOT_CONFIRMED
        if (input.gattBusy) return StartModeWriteBlockReason.GATT_BUSY

        val speed = input.speedKmh
        if (speed == null || !speed.isFinite() || speed < 0.0 || input.speedSampleAtElapsedMs <= 0L) {
            return StartModeWriteBlockReason.SPEED_NOT_AVAILABLE
        }
        if (input.speedSampleConnectionEpoch != input.connectionEpoch) {
            return StartModeWriteBlockReason.SPEED_FROM_PREVIOUS_CONNECTION
        }
        val sampleAgeMs = input.nowElapsedMs - input.speedSampleAtElapsedMs
        if (sampleAgeMs !in 0..MAX_SPEED_SAMPLE_AGE_MS) {
            return StartModeWriteBlockReason.SPEED_SAMPLE_STALE
        }
        if (speed > MAX_STATIONARY_SPEED_KMH) return StartModeWriteBlockReason.SCOOTER_MOVING
        return null
    }
}

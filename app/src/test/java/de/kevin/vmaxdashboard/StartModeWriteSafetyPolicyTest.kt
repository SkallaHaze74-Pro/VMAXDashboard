package de.kevin.vmaxdashboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StartModeWriteSafetyPolicyTest {
    @Test
    fun allowsOnlyFreshStationarySpeedFromCurrentConnection() {
        assertNull(StartModeWriteSafetyPolicy.blockReason(safeInput()))
        assertNull(
            StartModeWriteSafetyPolicy.blockReason(
                safeInput(speedSampleAtElapsedMs = 8_000L)
            )
        )
    }

    @Test
    fun blocksMissingStaleMovingAndPreviousConnectionSpeed() {
        assertEquals(
            StartModeWriteBlockReason.SPEED_NOT_AVAILABLE,
            StartModeWriteSafetyPolicy.blockReason(safeInput(speedKmh = null))
        )
        assertEquals(
            StartModeWriteBlockReason.SPEED_NOT_AVAILABLE,
            StartModeWriteSafetyPolicy.blockReason(safeInput(speedKmh = Double.NaN))
        )
        assertEquals(
            StartModeWriteBlockReason.SPEED_NOT_AVAILABLE,
            StartModeWriteSafetyPolicy.blockReason(safeInput(speedKmh = -0.1))
        )
        assertEquals(
            StartModeWriteBlockReason.SPEED_NOT_AVAILABLE,
            StartModeWriteSafetyPolicy.blockReason(safeInput(speedSampleAtElapsedMs = 0L))
        )
        assertEquals(
            StartModeWriteBlockReason.SPEED_SAMPLE_STALE,
            StartModeWriteSafetyPolicy.blockReason(safeInput(speedSampleAtElapsedMs = 7_999L))
        )
        assertEquals(
            StartModeWriteBlockReason.SPEED_SAMPLE_STALE,
            StartModeWriteSafetyPolicy.blockReason(safeInput(speedSampleAtElapsedMs = 10_001L))
        )
        assertEquals(
            StartModeWriteBlockReason.SPEED_FROM_PREVIOUS_CONNECTION,
            StartModeWriteSafetyPolicy.blockReason(safeInput(speedSampleConnectionEpoch = 3L))
        )
        assertEquals(
            StartModeWriteBlockReason.SCOOTER_MOVING,
            StartModeWriteSafetyPolicy.blockReason(safeInput(speedKmh = 0.6))
        )
    }

    @Test
    fun blocksEveryOperationalSafetyGate() {
        assertEquals(
            StartModeWriteBlockReason.NOT_CONNECTED,
            StartModeWriteSafetyPolicy.blockReason(safeInput(connected = false))
        )
        assertEquals(
            StartModeWriteBlockReason.TELEMETRY_NOT_READY,
            StartModeWriteSafetyPolicy.blockReason(safeInput(telemetryReady = false))
        )
        assertEquals(
            StartModeWriteBlockReason.RECORDING_ACTIVE,
            StartModeWriteSafetyPolicy.blockReason(safeInput(recordingActive = true))
        )
        assertEquals(
            StartModeWriteBlockReason.WRITE_PENDING,
            StartModeWriteSafetyPolicy.blockReason(safeInput(pendingStartModeWrite = true))
        )
        assertEquals(
            StartModeWriteBlockReason.OPERATION_BUSY,
            StartModeWriteSafetyPolicy.blockReason(safeInput(startModeBusy = true))
        )
        assertEquals(
            StartModeWriteBlockReason.LEGACY_ROUTE_NOT_CONFIRMED,
            StartModeWriteSafetyPolicy.blockReason(safeInput(legacyRouteConfirmed = false))
        )
        assertEquals(
            StartModeWriteBlockReason.GATT_BUSY,
            StartModeWriteSafetyPolicy.blockReason(safeInput(gattBusy = true))
        )
    }

    private fun safeInput(
        connected: Boolean = true,
        telemetryReady: Boolean = true,
        recordingActive: Boolean = false,
        startModeBusy: Boolean = false,
        pendingStartModeWrite: Boolean = false,
        legacyRouteConfirmed: Boolean = true,
        gattBusy: Boolean = false,
        speedKmh: Double? = 0.0,
        speedSampleAtElapsedMs: Long = 9_000L,
        speedSampleConnectionEpoch: Long = 4L
    ): StartModeWriteSafetyInput = StartModeWriteSafetyInput(
        connected = connected,
        telemetryReady = telemetryReady,
        recordingActive = recordingActive,
        startModeBusy = startModeBusy,
        pendingStartModeWrite = pendingStartModeWrite,
        legacyRouteConfirmed = legacyRouteConfirmed,
        gattBusy = gattBusy,
        speedKmh = speedKmh,
        speedSampleAtElapsedMs = speedSampleAtElapsedMs,
        speedSampleConnectionEpoch = speedSampleConnectionEpoch,
        connectionEpoch = 4L,
        nowElapsedMs = 10_000L
    )
}

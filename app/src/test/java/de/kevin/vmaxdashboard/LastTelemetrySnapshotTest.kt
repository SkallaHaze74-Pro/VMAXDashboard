package de.kevin.vmaxdashboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LastTelemetrySnapshotTest {
    @Test
    fun validPostChargeSampleReplacesThePersistedPreChargeValue() {
        val beforeCharge = LastTelemetrySnapshot(
            batteryPercent = 27,
            voltageV = 44.2,
            measuredAtMs = 1_000L
        )

        val afterCharge = mergeLastTelemetrySnapshot(
            current = beforeCharge,
            rawBatteryPercent = 97,
            voltageV = 52.8,
            measuredAtMs = 2_000L
        )

        assertEquals(97, afterCharge.batteryPercent)
        assertEquals(52.8, afterCharge.voltageV!!, 0.0)
        assertEquals(2_000L, afterCharge.measuredAtMs)
        assertTrue(shouldPersistLastTelemetrySnapshot(beforeCharge, afterCharge))
    }

    @Test
    fun invalidRawBatteryCannotOverwriteTheLastKnownRealValue() {
        val current = LastTelemetrySnapshot(27, 44.2, 1_000L)

        val next = mergeLastTelemetrySnapshot(
            current = current,
            rawBatteryPercent = 255,
            voltageV = Double.NaN,
            measuredAtMs = 2_000L
        )

        assertEquals(current, next)
    }

    @Test
    fun unchangedTelemetryIsCheckpointedPeriodicallyWithoutWritingEveryPacket() {
        val previous = LastTelemetrySnapshot(97, 52.8, 1_000L)
        val tooSoon = LastTelemetrySnapshot(97, 52.8, 10_000L)
        val checkpoint = LastTelemetrySnapshot(97, 52.8, 31_000L)

        assertFalse(shouldPersistLastTelemetrySnapshot(previous, tooSoon))
        assertTrue(shouldPersistLastTelemetrySnapshot(previous, checkpoint))
    }

    @Test
    fun emptySnapshotStaysEmptyWithoutARealMeasurement() {
        val empty = mergeLastTelemetrySnapshot(
            current = LastTelemetrySnapshot.EMPTY,
            rawBatteryPercent = null,
            voltageV = null,
            measuredAtMs = 1_000L
        )

        assertNull(empty.batteryPercent)
        assertNull(empty.voltageV)
        assertEquals(0L, empty.measuredAtMs)
    }

    @Test
    fun delayedPacketFromAnOlderConnectionCannotRollTheSnapshotBack() {
        val current = LastTelemetrySnapshot(97, 52.8, 2_000L)

        val stale = mergeLastTelemetrySnapshot(
            current = current,
            rawBatteryPercent = 27,
            voltageV = 44.2,
            measuredAtMs = 1_000L
        )

        assertEquals(current, stale)
    }

    @Test
    fun forcedCheckpointCannotOverwriteANewerPostChargeSample() {
        val postCharge = LastTelemetrySnapshot(97, 52.8, 2_000L)
        val stalePreChargeState = LastTelemetrySnapshot(27, 44.2, 1_000L)

        assertFalse(
            shouldAcceptLastTelemetryWrite(
                previous = postCharge,
                next = stalePreChargeState,
                force = true
            )
        )
    }

    @Test
    fun forcedCheckpointWithSameTimestampCannotReplaceConflictingTelemetry() {
        val postCharge = LastTelemetrySnapshot(97, 52.8, 2_000L)
        val conflictingPreChargeState = LastTelemetrySnapshot(27, 44.2, 2_000L)

        assertFalse(
            shouldAcceptLastTelemetryWrite(
                previous = postCharge,
                next = conflictingPreChargeState,
                force = true
            )
        )
    }

    @Test
    fun implausibleFutureTimestampDoesNotFreezeTelemetryForever() {
        val now = 1_000_000L
        val future = LastTelemetrySnapshot(27, 44.2, now + LAST_TELEMETRY_MAX_FUTURE_SKEW_MS + 1L)

        assertEquals(
            LastTelemetrySnapshot.EMPTY,
            sanitizeLoadedLastTelemetrySnapshot(future, now)
        )
        assertTrue(
            shouldAcceptLastTelemetryWrite(
                previous = future,
                next = LastTelemetrySnapshot(97, 52.8, now),
                force = false,
                nowMs = now
            )
        )
    }
}

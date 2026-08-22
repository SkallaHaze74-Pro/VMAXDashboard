package de.kevin.vmaxdashboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BatteryPercentStabilizerTest {
    @Test
    fun movingLoadSagAndReboundPreserveRawWithoutMovingStableDisplay() {
        val stabilizer = BatteryPercentStabilizer()
        settle(stabilizer, 37)

        val loadSag = stabilizer.observe(rawPercent = 23, currentA = 8.0, speedKmh = 16.4)
        val movingLow = stabilizer.observe(rawPercent = 21, currentA = 0.0, speedKmh = 4.7)
        val movingRebound = stabilizer.observe(rawPercent = 32, currentA = 1.0, speedKmh = 7.2)

        assertEquals(23, loadSag.rawPercent)
        assertEquals(37, loadSag.stablePercent)
        assertEquals(BatteryPercentStability.HELD_TRANSIENT, loadSag.stability)
        assertEquals(21, movingLow.rawPercent)
        assertEquals(37, movingLow.stablePercent)
        assertEquals(32, movingRebound.rawPercent)
        assertEquals(37, movingRebound.stablePercent)
    }

    @Test
    fun restedObservationsAdoptTheRobustMedianAfterOneCoherentWindow() {
        val stabilizer = BatteryPercentStabilizer()
        settle(stabilizer, 37)

        repeat(3) {
            stabilizer.observe(rawPercent = 32, currentA = 0.0, speedKmh = 0.0)
        }
        assertEquals(32, stabilizer.current().stablePercent)
        assertEquals(32, stabilizer.current().rawPercent)
        assertEquals(BatteryPercentStability.STABLE, stabilizer.current().stability)
    }

    @Test
    fun shortRealRideTailConvergesInsteadOfRemainingTenPointsTooHigh() {
        val stabilizer = BatteryPercentStabilizer()
        settle(stabilizer, 48)

        listOf(32, 34, 37, 37, 37).forEach { raw ->
            stabilizer.observe(rawPercent = raw, currentA = 0.0, speedKmh = 0.0)
        }

        assertEquals(37, stabilizer.current().stablePercent)
        assertEquals(37, stabilizer.current().rawPercent)
    }

    @Test
    fun batteryRestContextRejectsStaleOrPreviousConnectionSpeed() {
        assertEquals(
            0.0,
            freshBatterySpeedKmh(
                speedKmh = 0.0,
                speedSampleAtElapsedMs = 9_000L,
                speedSampleConnectionEpoch = 4L,
                connectionEpoch = 4L,
                nowElapsedMs = 10_000L
            )!!,
            0.0
        )
        assertNull(
            freshBatterySpeedKmh(
                speedKmh = 0.0,
                speedSampleAtElapsedMs = 7_999L,
                speedSampleConnectionEpoch = 4L,
                connectionEpoch = 4L,
                nowElapsedMs = 10_000L
            )
        )
        assertNull(
            freshBatterySpeedKmh(
                speedKmh = 0.0,
                speedSampleAtElapsedMs = 9_000L,
                speedSampleConnectionEpoch = 3L,
                connectionEpoch = 4L,
                nowElapsedMs = 10_000L
            )
        )
    }

    @Test
    fun staleSpeedCannotCreateARestedBatteryBaseline() {
        val stabilizer = BatteryPercentStabilizer()

        repeat(3) {
            stabilizer.observe(
                rawPercent = 37,
                currentA = 0.0,
                speedKmh = freshBatterySpeedKmh(
                    speedKmh = 0.0,
                    speedSampleAtElapsedMs = 7_000L,
                    speedSampleConnectionEpoch = 4L,
                    connectionEpoch = 4L,
                    nowElapsedMs = 10_000L
                )
            )
        }

        assertEquals(37, stabilizer.current().rawPercent)
        assertNull(stabilizer.current().stablePercent)
        assertEquals(BatteryPercentStability.HELD_TRANSIENT, stabilizer.current().stability)
    }

    @Test
    fun missingCurrentCannotCreateARestedBatteryBaseline() {
        val stabilizer = BatteryPercentStabilizer()

        repeat(3) {
            stabilizer.observe(rawPercent = 37, currentA = null, speedKmh = 0.0)
        }

        assertEquals(37, stabilizer.current().rawPercent)
        assertNull(stabilizer.current().stablePercent)
        assertEquals(BatteryPercentStability.HELD_TRANSIENT, stabilizer.current().stability)
    }

    @Test
    fun movingFirstObservationCannotBecomeTheStableBaseline() {
        val stabilizer = BatteryPercentStabilizer()

        val moving = stabilizer.observe(rawPercent = 21, currentA = 0.0, speedKmh = 4.7)

        assertEquals(21, moving.rawPercent)
        assertNull(moving.stablePercent)
        assertEquals(BatteryPercentStability.HELD_TRANSIENT, moving.stability)

        settle(stabilizer, 32)
        assertEquals(32, stabilizer.current().stablePercent)
    }

    @Test
    fun packetsWithoutANewSocValueDoNotBreakTheRestedSequence() {
        val stabilizer = BatteryPercentStabilizer()

        stabilizer.observe(rawPercent = 37, currentA = 0.0, speedKmh = 0.0)
        val noSoc = stabilizer.observe(rawPercent = null, currentA = 0.0, speedKmh = 0.0)
        stabilizer.observe(rawPercent = 37, currentA = 0.0, speedKmh = 0.0)
        stabilizer.observe(rawPercent = null, currentA = 0.0, speedKmh = 0.0)
        stabilizer.observe(rawPercent = 37, currentA = 0.0, speedKmh = 0.0)

        assertEquals(37, noSoc.rawPercent)
        assertEquals(37, stabilizer.current().stablePercent)
    }

    @Test
    fun invalidRawIsPreservedForDiagnosticsButCannotChangeStableDisplay() {
        val stabilizer = BatteryPercentStabilizer()
        settle(stabilizer, 37)

        val invalid = stabilizer.observe(rawPercent = 101, currentA = 0.0, speedKmh = 0.0)

        assertEquals(101, invalid.rawPercent)
        assertEquals(37, invalid.stablePercent)
        assertEquals(BatteryPercentStability.INVALID_RAW, invalid.stability)
    }

    @Test
    fun regenerationCurrentMagnitudeIsTreatedAsLoad() {
        val stabilizer = BatteryPercentStabilizer()
        settle(stabilizer, 37)

        val regenerating = stabilizer.observe(rawPercent = 25, currentA = -5.0, speedKmh = 0.0)

        assertEquals(25, regenerating.rawPercent)
        assertEquals(37, regenerating.stablePercent)
        assertEquals(BatteryPercentStability.HELD_TRANSIENT, regenerating.stability)
    }

    @Test
    fun disconnectAndResetExplicitlyClearRawStableAndPendingState() {
        val stabilizer = BatteryPercentStabilizer()
        settle(stabilizer, 37)

        val disconnected = stabilizer.disconnect()
        assertNull(disconnected.rawPercent)
        assertNull(disconnected.stablePercent)
        assertEquals(BatteryPercentStability.DISCONNECTED, disconnected.stability)

        stabilizer.observe(rawPercent = 32, currentA = 0.0, speedKmh = 0.0)
        val reset = stabilizer.reset()
        assertNull(reset.rawPercent)
        assertNull(reset.stablePercent)
        assertEquals(BatteryPercentStability.RESET, reset.stability)

        repeat(2) {
            stabilizer.observe(rawPercent = 32, currentA = 0.0, speedKmh = 0.0)
        }
        assertNull(stabilizer.current().stablePercent)
    }

    private fun settle(stabilizer: BatteryPercentStabilizer, rawPercent: Int) {
        repeat(3) {
            stabilizer.observe(rawPercent, currentA = 0.0, speedKmh = 0.0)
        }
        assertEquals(rawPercent, stabilizer.current().stablePercent)
    }
}

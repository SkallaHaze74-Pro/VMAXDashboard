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
        assertEquals(BatteryPercentStability.RECOVERING_AFTER_LOAD, loadSag.stability)
        assertEquals(21, movingLow.rawPercent)
        assertEquals(37, movingLow.stablePercent)
        assertEquals(32, movingRebound.rawPercent)
        assertEquals(37, movingRebound.stablePercent)
    }

    @Test
    fun postLoadRecoveryHoldsStableForFull45SecondsThenRequiresFreshRestWindow() {
        val stabilizer = BatteryPercentStabilizer()
        settleAt(stabilizer, rawPercent = 37, firstSampleAtElapsedMs = 1_000L)

        val load = stabilizer.observe(
            rawPercent = 27,
            currentA = 8.0,
            speedKmh = 0.0,
            nowElapsedMs = 10_000L
        )
        assertEquals(27, load.rawPercent)
        assertEquals(37, load.stablePercent)
        assertEquals(BatteryPercentStability.RECOVERING_AFTER_LOAD, load.stability)

        listOf(54_997L, 54_998L, 54_999L).forEach { nowElapsedMs ->
            val recovering = stabilizer.observe(
                rawPercent = 32,
                currentA = 0.0,
                speedKmh = 0.0,
                nowElapsedMs = nowElapsedMs
            )
            assertEquals(32, recovering.rawPercent)
            assertEquals(37, recovering.stablePercent)
            assertEquals(BatteryPercentStability.RECOVERING_AFTER_LOAD, recovering.stability)
        }

        val firstEligibleRestSample = stabilizer.observe(
            rawPercent = 32,
            currentA = 0.0,
            speedKmh = 0.0,
            nowElapsedMs = 55_000L
        )
        assertEquals(37, firstEligibleRestSample.stablePercent)
        assertEquals(BatteryPercentStability.WAITING_FOR_REST, firstEligibleRestSample.stability)

        stabilizer.observe(32, currentA = 0.0, speedKmh = 0.0, nowElapsedMs = 55_001L)
        val settled = stabilizer.observe(
            rawPercent = 32,
            currentA = 0.0,
            speedKmh = 0.0,
            nowElapsedMs = 55_002L
        )
        assertEquals(32, settled.rawPercent)
        assertEquals(32, settled.stablePercent)
        assertEquals(BatteryPercentStability.STABLE, settled.stability)
    }

    @Test
    fun movementPositiveLoadAndRegenerationEachRestartRecoveryDeadline() {
        val stabilizer = BatteryPercentStabilizer()
        settleAt(stabilizer, rawPercent = 37, firstSampleAtElapsedMs = 1_000L)

        stabilizer.observe(30, currentA = 0.0, speedKmh = 4.0, nowElapsedMs = 10_000L)
        assertRecovering(stabilizer, nowElapsedMs = 54_999L)

        stabilizer.observe(29, currentA = 8.0, speedKmh = 0.0, nowElapsedMs = 54_999L)
        assertRecovering(stabilizer, nowElapsedMs = 99_998L)

        stabilizer.observe(28, currentA = -5.0, speedKmh = 0.0, nowElapsedMs = 99_998L)
        assertRecovering(stabilizer, nowElapsedMs = 144_997L)

        stabilizer.observe(31, currentA = 0.0, speedKmh = 0.0, nowElapsedMs = 144_998L)
        stabilizer.observe(31, currentA = 0.0, speedKmh = 0.0, nowElapsedMs = 144_999L)
        val settled = stabilizer.observe(
            rawPercent = 31,
            currentA = 0.0,
            speedKmh = 0.0,
            nowElapsedMs = 145_000L
        )
        assertEquals(31, settled.stablePercent)
        assertEquals(BatteryPercentStability.STABLE, settled.stability)
    }

    @Test
    fun movementWithoutANewSocStillStartsRecoveryAndKeepsLastRawObservable() {
        val stabilizer = BatteryPercentStabilizer()
        settleAt(stabilizer, rawPercent = 37, firstSampleAtElapsedMs = 1_000L)

        val movingWithoutSoc = stabilizer.observe(
            rawPercent = null,
            currentA = 0.0,
            speedKmh = 4.0,
            nowElapsedMs = 10_000L
        )

        assertEquals(37, movingWithoutSoc.rawPercent)
        assertEquals(37, movingWithoutSoc.stablePercent)
        assertEquals(BatteryPercentStability.RECOVERING_AFTER_LOAD, movingWithoutSoc.stability)
        assertRecovering(stabilizer, nowElapsedMs = 54_999L)

        val waitingForFreshSoc = stabilizer.observe(
            rawPercent = null,
            currentA = 0.0,
            speedKmh = 0.0,
            nowElapsedMs = 55_000L
        )
        assertEquals(37, waitingForFreshSoc.rawPercent)
        assertEquals(37, waitingForFreshSoc.stablePercent)
        assertEquals(BatteryPercentStability.WAITING_FOR_REST, waitingForFreshSoc.stability)
    }

    @Test
    fun currentMoving1505PacketStartsRecoveryWithoutWaitingForCarriedState() {
        val stabilizer = BatteryPercentStabilizer()
        settleAt(stabilizer, rawPercent = 37, firstSampleAtElapsedMs = 1_000L)

        val currentPacketSpeed = batteryMotionSpeedKmh(
            decodedSpeedKmh = 4.7,
            freshCarriedSpeedKmh = 0.0
        )
        val moving = stabilizer.observe(
            rawPercent = null,
            currentA = null,
            speedKmh = currentPacketSpeed,
            nowElapsedMs = 10_000L
        )

        assertEquals(4.7, currentPacketSpeed!!, 0.0)
        assertEquals(37, moving.rawPercent)
        assertEquals(37, moving.stablePercent)
        assertEquals(BatteryPercentStability.RECOVERING_AFTER_LOAD, moving.stability)
        assertEquals(
            4.7,
            batteryMotionSpeedKmh(decodedSpeedKmh = 0.0, freshCarriedSpeedKmh = 4.7)!!,
            0.0
        )
        assertNull(
            batteryMotionSpeedKmh(
                decodedSpeedKmh = Double.NaN,
                freshCarriedSpeedKmh = -1.0
            )
        )
    }

    @Test
    fun regressingElapsedInputCannotShortenRecoveryHold() {
        val stabilizer = BatteryPercentStabilizer()
        settleAt(stabilizer, rawPercent = 37, firstSampleAtElapsedMs = 1_000L)

        stabilizer.observe(29, currentA = 8.0, speedKmh = 0.0, nowElapsedMs = 100_000L)
        stabilizer.observe(28, currentA = -5.0, speedKmh = 0.0, nowElapsedMs = 90_000L)

        assertRecovering(stabilizer, nowElapsedMs = 144_999L)
        stabilizer.observe(32, currentA = 0.0, speedKmh = 0.0, nowElapsedMs = 145_000L)
        stabilizer.observe(32, currentA = 0.0, speedKmh = 0.0, nowElapsedMs = 145_001L)
        val settled = stabilizer.observe(
            rawPercent = 32,
            currentA = 0.0,
            speedKmh = 0.0,
            nowElapsedMs = 145_002L
        )
        assertEquals(32, settled.stablePercent)
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

        val moving = stabilizer.observe(
            rawPercent = 21,
            currentA = 0.0,
            speedKmh = 4.7,
            nowElapsedMs = 1_000L
        )

        assertEquals(21, moving.rawPercent)
        assertNull(moving.stablePercent)
        assertEquals(BatteryPercentStability.RECOVERING_AFTER_LOAD, moving.stability)

        settleAt(stabilizer, rawPercent = 32, firstSampleAtElapsedMs = 46_000L)
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
        assertEquals(BatteryPercentStability.RECOVERING_AFTER_LOAD, regenerating.stability)
    }

    @Test
    fun disconnectAndResetClearRecoveryDeadlineAsWellAsBatteryValues() {
        val stabilizer = BatteryPercentStabilizer()
        settleAt(stabilizer, rawPercent = 37, firstSampleAtElapsedMs = 1_000L)

        stabilizer.observe(27, currentA = 8.0, speedKmh = 0.0, nowElapsedMs = 10_000L)
        stabilizer.disconnect()
        settleAt(stabilizer, rawPercent = 32, firstSampleAtElapsedMs = 10_001L)
        assertEquals(32, stabilizer.current().stablePercent)

        stabilizer.observe(26, currentA = -5.0, speedKmh = 0.0, nowElapsedMs = 20_000L)
        stabilizer.reset()
        settleAt(stabilizer, rawPercent = 31, firstSampleAtElapsedMs = 20_001L)
        assertEquals(31, stabilizer.current().stablePercent)
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

    private fun settleAt(
        stabilizer: BatteryPercentStabilizer,
        rawPercent: Int,
        firstSampleAtElapsedMs: Long
    ) {
        repeat(3) { offset ->
            stabilizer.observe(
                rawPercent = rawPercent,
                currentA = 0.0,
                speedKmh = 0.0,
                nowElapsedMs = firstSampleAtElapsedMs + offset
            )
        }
        assertEquals(rawPercent, stabilizer.current().stablePercent)
    }

    private fun assertRecovering(stabilizer: BatteryPercentStabilizer, nowElapsedMs: Long) {
        val recovering = stabilizer.observe(
            rawPercent = 31,
            currentA = 0.0,
            speedKmh = 0.0,
            nowElapsedMs = nowElapsedMs
        )
        assertEquals(31, recovering.rawPercent)
        assertEquals(37, recovering.stablePercent)
        assertEquals(BatteryPercentStability.RECOVERING_AFTER_LOAD, recovering.stability)
    }
}

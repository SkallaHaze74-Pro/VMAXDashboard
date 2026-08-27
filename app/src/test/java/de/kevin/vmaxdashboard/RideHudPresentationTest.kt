package de.kevin.vmaxdashboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RideHudPresentationTest {
    @Test
    fun freshCurrentConnectionSpeedAndStableBatteryAreLive() {
        val state = ScooterState(
            connected = true,
            telemetryReady = true,
            connectionEpoch = 4L,
            speedSampleConnectionEpoch = 4L,
            lastSpeedSampleElapsedRealtimeMs = 1_000L,
            speedKmh = 21.8,
            batteryPercent = 82,
            batteryPercentRaw = 82,
            batteryStability = BatteryPercentStability.STABLE,
            lastBatteryTelemetryAt = 10_000L
        )

        val reading = rideHudReading(
            state,
            nowElapsedRealtimeMs = 3_000L,
            nowWallClockMs = 15_000L
        )

        assertTrue(reading.visible)
        assertTrue(reading.speedLive)
        assertEquals("21,8", reading.speedText)
        assertEquals("82 %", reading.batteryText)
        assertEquals("stabil", reading.batteryDetail)
        assertEquals(RideHudBatterySource.STABLE, reading.batterySource)
        assertEquals(
            "0,0",
            rideHudReading(state.copy(speedKmh = 0.0), 3_000L, 15_000L).speedText
        )
    }

    @Test
    fun stalePreviousConnectionAndInvalidSpeedAreNeverShownAsLive() {
        val freshState = ScooterState(
            connected = true,
            telemetryReady = true,
            connectionEpoch = 7L,
            speedSampleConnectionEpoch = 7L,
            lastSpeedSampleElapsedRealtimeMs = 10_000L,
            speedKmh = 18.4
        )

        assertTrue(rideHudReading(freshState, 12_000L).speedLive)
        assertFalse(rideHudReading(freshState, 12_001L).speedLive)
        assertEquals("—", rideHudReading(freshState, 12_001L).speedText)
        assertFalse(
            rideHudReading(
                freshState.copy(speedSampleConnectionEpoch = 6L),
                10_500L
            ).speedLive
        )
        assertFalse(
            rideHudReading(
                freshState.copy(speedKmh = Double.NaN),
                10_500L
            ).speedLive
        )
        assertFalse(
            rideHudReading(
                freshState.copy(speedKmh = -0.1),
                10_500L
            ).speedLive
        )
    }

    @Test
    fun recoveringBatteryKeepsConfirmedAndRawValuesSeparate() {
        val reading = rideHudReading(
            ScooterState(
                connected = true,
                telemetryReady = true,
                batteryPercent = 32,
                batteryPercentRaw = 27,
                batteryStability = BatteryPercentStability.RECOVERING_AFTER_LOAD,
                lastBatteryTelemetryAt = 4_000L
            ),
            nowElapsedRealtimeMs = 5_000L,
            nowWallClockMs = 5_000L
        )

        assertEquals("32 %", reading.batteryText)
        assertEquals("roh 27 % • Erholung", reading.batteryDetail)
        assertEquals(RideHudBatterySource.STABLE_WITH_RAW, reading.batterySource)
    }

    @Test
    fun staleBatteryIsNeverGreenOrClaimedAsLive() {
        val reading = rideHudReading(
            ScooterState(
                connected = true,
                telemetryReady = true,
                batteryPercent = 64,
                batteryPercentRaw = 63,
                batteryStability = BatteryPercentStability.STABLE,
                lastBatteryTelemetryAt = 10_000L
            ),
            nowElapsedRealtimeMs = 25_001L,
            nowWallClockMs = 25_001L
        )

        assertEquals("64 %", reading.batteryText)
        assertEquals("zuletzt • Daten alt", reading.batteryDetail)
        assertEquals(RideHudBatterySource.STALE, reading.batterySource)
    }

    @Test
    fun disconnectedLastKnownBatteryIsHistoryAndHudIsHidden() {
        val reading = rideHudReading(
            ScooterState(
                connected = false,
                telemetryReady = false,
                speedKmh = 19.0,
                lastKnownBatteryPercent = 74
            ),
            nowElapsedRealtimeMs = 5_000L
        )

        assertFalse(reading.visible)
        assertEquals("VERBINDE …", reading.statusText)
        assertFalse(reading.speedLive)
        assertEquals("—", reading.speedText)
        assertEquals("74 %", reading.batteryText)
        assertEquals("zuletzt", reading.batteryDetail)
        assertEquals(RideHudBatterySource.LAST_KNOWN, reading.batterySource)
    }
}

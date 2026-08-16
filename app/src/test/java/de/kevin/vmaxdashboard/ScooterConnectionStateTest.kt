package de.kevin.vmaxdashboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScooterConnectionStateTest {
    @Test
    fun reconnectClearsLatestConnectionValuesButKeepsMeasurementAggregates() {
        val old = ScooterState(
            connected = true,
            telemetryReady = true,
            connectionEpoch = 4L,
            speedSampleConnectionEpoch = 4L,
            lastSpeedSampleElapsedRealtimeMs = 9_000L,
            diagnosticGattReadRunning = true,
            gattOperationBusy = true,
            batteryPercent = 61,
            speedKmh = 0.0,
            motorLoadRaw = 420,
            startModeRaw = 1,
            startModeWriteAvailable = true,
            rawPackets = mapOf("1505" to "old"),
            channels = listOf(BleChannelState(channel = "1505")),
            lastPacketAt = 99L,
            currentPowerW = 410.0,
            maxSpeedKmh = 31.4,
            maxPowerW = 920.0,
            recordingPacketCount = 123
        )

        val cleared = old.clearConnectionScopedTelemetry(nextConnectionEpoch = 5L)

        assertTrue(cleared.connected)
        assertFalse(cleared.telemetryReady)
        assertEquals(5L, cleared.connectionEpoch)
        assertEquals(-1L, cleared.speedSampleConnectionEpoch)
        assertEquals(0L, cleared.lastSpeedSampleElapsedRealtimeMs)
        assertFalse(cleared.diagnosticGattReadRunning)
        assertFalse(cleared.gattOperationBusy)
        assertNull(cleared.batteryPercent)
        assertNull(cleared.speedKmh)
        assertNull(cleared.motorLoadRaw)
        assertNull(cleared.startModeRaw)
        assertFalse(cleared.startModeWriteAvailable)
        assertTrue(cleared.rawPackets.isEmpty())
        assertTrue(cleared.channels.isEmpty())
        assertEquals(0L, cleared.lastPacketAt)
        assertNull(cleared.currentPowerW)
        assertEquals(31.4, cleared.maxSpeedKmh!!, 0.0001)
        assertEquals(920.0, cleared.maxPowerW!!, 0.0001)
        assertEquals(123, cleared.recordingPacketCount)
    }
}

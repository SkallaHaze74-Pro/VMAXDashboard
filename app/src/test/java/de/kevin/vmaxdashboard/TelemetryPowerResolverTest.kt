package de.kevin.vmaxdashboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TelemetryPowerResolverTest {
    @Test
    fun currentPacketValuesOverridePreviousSnapshot() {
        assertEquals(480.0, resolveElectricalPowerW(48.0, 10.0, 52.0, 1.0)!!, 0.0001)
        assertEquals(520.0, resolveElectricalPowerW(null, 10.0, 52.0, 1.0)!!, 0.0001)
        assertEquals(104.0, resolveElectricalPowerW(52.0, null, 50.0, 2.0)!!, 0.0001)
    }

    @Test
    fun missingVoltageOrCurrentDoesNotInventPower() {
        assertNull(resolveElectricalPowerW(null, 10.0, null, 1.0))
        assertNull(resolveElectricalPowerW(52.0, null, 50.0, null))
    }

    @Test
    fun exportKeepsDirectPowerStableAcrossFollowingPackets() {
        assertEquals(297.0, resolveExportPowerW(297.0, 480.0, 680.0)!!, 0.0001)
        assertEquals(680.0, resolveExportPowerW(null, 480.0, 680.0)!!, 0.0001)
        assertEquals(480.0, resolveExportPowerW(null, 480.0, null)!!, 0.0001)
        assertNull(resolveExportPowerW(null, null, null))
    }

    @Test
    fun corruptUnsignedDirectPowerIsRejectedAtCanonicalDecoder() {
        val valid = LiveTelemetryDecoder.decode(
            "1509",
            bytes("00-00-FF-FF-50-C3-50-FF-FF-75-30")
        )
        val corrupt = LiveTelemetryDecoder.decode(
            "1509",
            bytes("00-00-FF-FF-50-C3-50-FF-FF-FF-FE")
        )

        assertEquals(30_000.0, valid.powerW!!, 0.0001)
        assertEquals(30_000, valid.motorLoadRaw)
        assertNull(corrupt.powerW)
        assertNull(corrupt.motorLoadRaw)
    }

    private fun bytes(hex: String): ByteArray = hex.split('-')
        .map { it.toInt(16).toByte() }
        .toByteArray()
}

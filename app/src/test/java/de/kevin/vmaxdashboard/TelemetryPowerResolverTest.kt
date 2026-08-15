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
    fun exportPrefersCurrentPacketThenFreshElectricalValue() {
        assertEquals(297.0, resolveExportPowerW(297.0, 480.0, 680.0)!!, 0.0001)
        assertEquals(480.0, resolveExportPowerW(null, 480.0, 680.0)!!, 0.0001)
        assertEquals(680.0, resolveExportPowerW(null, null, 680.0)!!, 0.0001)
    }
}

package de.kevin.vmaxdashboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RideTelemetrySummaryTest {
    @Test
    fun summaryUsesOnlyCanonicalFreshSourceRowsAndKeepsRawSeparateFromStable() {
        val summary = RideTelemetrySummary.fromV3Rows(
            listOf(
                row(source = "1506", odometer = "726.0"),
                row(source = "1509", rawBattery = "32", stableBattery = "32", stability = "STABLE", voltage = "45.9", current = "0.0"),
                row(source = "1505", speed = "21.8", rawBattery = "1", stableBattery = "1", voltage = "1.0", current = "199.0"),
                row(source = "1509", rawBattery = "21", stableBattery = "32", stability = "RECOVERING_AFTER_LOAD", voltage = "42.7", current = "18.0"),
                row(source = "1508", speed = "88.0", rawBattery = "2", stableBattery = "2", voltage = "2.0", current = "150.0", odometer = "999.0"),
                row(source = "1509", rawBattery = "27", stableBattery = "32", stability = "RECOVERING_AFTER_LOAD", voltage = "45.6", current = "0.0"),
                row(source = "1506", odometer = "726.8")
            )
        )

        assertEquals(7, summary.telemetryRowCount)
        assertEquals(0.8, summary.distanceKm!!, 0.0001)
        assertEquals(MeasurementTripDistanceProvenance.ODOMETER_DELTA, summary.distanceProvenance)
        assertEquals(21.8, summary.maxSpeedKmh!!, 0.0001)
        assertEquals(32, summary.startingBatteryRawPercent)
        assertEquals(32, summary.startingBatteryStablePercent)
        assertEquals(27, summary.endingBatteryRawPercent)
        assertEquals(32, summary.endingBatteryStablePercent)
        assertEquals(RideBatteryEndStatus.RECOVERY_PENDING, summary.batteryStatus)
        assertEquals(21, summary.minimumRawBatteryPercent)
        assertEquals(21, summary.heldTransientMinimumRawBatteryPercent)
        assertEquals(42.7, summary.minimumVoltageV!!, 0.0001)
        assertEquals(18.0, summary.maxAbsoluteCurrentA!!, 0.0001)
        assertTrue(summary.userSummaryLines().any { "Rohwert nicht als stabil" in it })
        assertTrue(summary.dashboardSummaryLines().any { it == "Strecke: 0,8 km • Kilometerstand 1506" })
        assertTrue(summary.dashboardSummaryLines().any { it == "Akku Ende: roh 27 % • stabil 32 % • Erholung läuft – Rohwert nicht als stabil übernommen" })
        assertTrue(summary.dashboardSummaryLines().none { '_' in it })
    }

    @Test
    fun equalFinalRawAndCarriedStableRemainPendingWhenLastWindowIsWaiting() {
        val summary = RideTelemetrySummary.fromV3Rows(
            listOf(
                row(source = "1509", rawBattery = "32", stableBattery = "32", stability = "STABLE"),
                row(source = "1509", rawBattery = "27", stableBattery = "27", stability = "STABLE"),
                row(source = "1509", rawBattery = "27", stableBattery = "27", stability = "WAITING_FOR_REST")
            )
        )

        assertEquals(27, summary.endingBatteryRawPercent)
        assertEquals(27, summary.endingBatteryStablePercent)
        assertEquals(RideBatteryEndStatus.VALIDATION_PENDING, summary.batteryStatus)
        assertTrue(
            summary.userSummaryLines().any {
                it == "Akku_Endstatus: Bestätigung läuft – Rohwert nicht neu als stabil übernommen"
            }
        )
    }

    @Test
    fun exportedMeasurementTripKeepsDistanceBeforeTheFirst1506Row() {
        val summary = RideTelemetrySummary.fromV3Rows(
            listOf(
                row(source = "1506", trip = "0.8", odometer = "726.8"),
                row(source = "1506", trip = "1.1", odometer = "727.1")
            )
        )

        assertEquals(1.1, summary.distanceKm!!, 0.0001)
        assertEquals(MeasurementTripDistanceProvenance.ODOMETER_DELTA, summary.distanceProvenance)
    }

    @Test
    fun invalidRawIsReportedButNeverPromotedToStable() {
        val invalid = RideTelemetrySummary.fromV3Rows(
            listOf(
                row(source = "1509", rawBattery = "97", stableBattery = "97", stability = "STABLE"),
                row(source = "1509", rawBattery = "101", stableBattery = "97", stability = "INVALID_RAW")
            )
        )
        val rawOnly = RideTelemetrySummary.fromV3Rows(
            listOf(row(source = "1509", rawBattery = "97", stability = "WAITING_FOR_REST"))
        )
        val unavailable = RideTelemetrySummary.fromV3Rows(
            listOf(row(source = "1508", rawBattery = "88", stableBattery = "88"), "broken")
        )

        assertEquals(RideBatteryEndStatus.INVALID_RAW, invalid.batteryStatus)
        assertEquals(101, invalid.endingBatteryRawPercent)
        assertEquals(97, invalid.endingBatteryStablePercent)
        assertTrue(invalid.userSummaryLines().any { "Rohwert ungültig" in it })
        assertEquals(RideBatteryEndStatus.RAW_ONLY, rawOnly.batteryStatus)
        assertEquals(RideBatteryEndStatus.UNAVAILABLE, unavailable.batteryStatus)
        assertNull(unavailable.endingBatteryRawPercent)
        assertNull(unavailable.distanceKm)
    }

    private fun row(
        source: String,
        speed: String = "",
        rawBattery: String = "",
        stableBattery: String = "",
        stability: String = "",
        voltage: String = "",
        current: String = "",
        trip: String = "",
        odometer: String = ""
    ): String = listOf(
        "0", "1000", speed, rawBattery, stableBattery, stability, voltage, current,
        "", "", "", "", "", trip, odometer, "", "", "", "", "", source
    ).joinToString(";")
}

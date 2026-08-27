package de.kevin.vmaxdashboard

import org.junit.Assert.assertEquals
import org.junit.Test

class DashboardPresentationTest {
    @Test
    fun inconsistentRideQualityUsesAnErrorToneInsteadOfGreenSuccess() {
        assertEquals(
            DashboardStatusTone.ERROR,
            measurementQualityTone(MeasurementDataQualityStatus.INCONSISTENT)
        )
        assertEquals(
            DashboardStatusTone.ACCENT,
            measurementQualityTone(MeasurementDataQualityStatus.CLEAN)
        )
        assertEquals(
            DashboardStatusTone.MUTED,
            measurementQualityTone(MeasurementDataQualityStatus.NO_DATA)
        )
    }

    @Test
    fun recoveringBatteryShowsStableAndRawWithoutPretendingTheRawValueIsConfirmed() {
        assertEquals(
            "32 % zuletzt stabil • roh 27 % • Akku erholt sich",
            batteryDisplayText(
                stablePercent = 32,
                rawPercent = 27,
                stability = BatteryPercentStability.RECOVERING_AFTER_LOAD,
                lastKnownPercent = 27
            )
        )
    }

    @Test
    fun equalRawAndCarriedStableRemainPendingWhileNextRestWindowIsWaiting() {
        assertEquals(
            "27 % zuletzt stabil • roh 27 % • Bestätigung läuft",
            batteryDisplayText(
                stablePercent = 27,
                rawPercent = 27,
                stability = BatteryPercentStability.WAITING_FOR_REST,
                lastKnownPercent = 27
            )
        )
    }

    @Test
    fun rawOnlyAndOfflineHistoryAreClearlyDistinguished() {
        assertEquals(
            "97 % roh • Stabilisierung läuft",
            batteryDisplayText(
                stablePercent = null,
                rawPercent = 97,
                stability = BatteryPercentStability.WAITING_FOR_REST,
                lastKnownPercent = 97
            )
        )
        assertEquals(
            "– live • letzter Rohwert 97 %",
            batteryDisplayText(
                stablePercent = null,
                rawPercent = null,
                stability = BatteryPercentStability.DISCONNECTED,
                lastKnownPercent = 97
            )
        )
    }
}

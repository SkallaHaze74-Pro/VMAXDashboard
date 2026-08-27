package de.kevin.vmaxdashboard

internal fun batteryDisplayText(
    stablePercent: Int?,
    rawPercent: Int?,
    stability: BatteryPercentStability,
    lastKnownPercent: Int?
): String {
    val rawIsValid = rawPercent in 0..100
    if (!rawIsValid && rawPercent != null) {
        return stablePercent?.let { "$it % zuletzt stabil • roh $rawPercent ungültig" }
            ?: "– • roh $rawPercent ungültig"
    }

    if (stablePercent != null && rawPercent != null) {
        if (stablePercent == rawPercent && stability == BatteryPercentStability.STABLE) {
            return "$stablePercent % • stabil bestätigt"
        }
        val suffix = when (stability) {
            BatteryPercentStability.RECOVERING_AFTER_LOAD,
            BatteryPercentStability.HELD_TRANSIENT -> "Akku erholt sich"
            else -> "Bestätigung läuft"
        }
        return "$stablePercent % zuletzt stabil • roh $rawPercent % • $suffix"
    }

    if (stablePercent != null) return "$stablePercent % • stabil"
    if (rawPercent != null) return "$rawPercent % roh • Stabilisierung läuft"
    return lastKnownPercent?.let { "– live • letzter Rohwert $it %" } ?: "–"
}

internal enum class DashboardStatusTone { ACCENT, ERROR, MUTED }

internal fun measurementQualityTone(status: MeasurementDataQualityStatus?): DashboardStatusTone =
    when (status) {
        MeasurementDataQualityStatus.INCONSISTENT -> DashboardStatusTone.ERROR
        MeasurementDataQualityStatus.NO_DATA, null -> DashboardStatusTone.MUTED
        MeasurementDataQualityStatus.CLEAN,
        MeasurementDataQualityStatus.COMPLETE_WITH_ISOLATION -> DashboardStatusTone.ACCENT
    }

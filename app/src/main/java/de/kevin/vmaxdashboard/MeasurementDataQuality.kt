package de.kevin.vmaxdashboard

enum class MeasurementDataQualityStatus {
    CLEAN,
    COMPLETE_WITH_ISOLATION,
    INCONSISTENT,
    NO_DATA
}

/**
 * Internal consistency of the captured/exported rows.
 *
 * This deliberately does not claim that Bluetooth delivered every over-air
 * packet. It only proves whether every notification Android reported was
 * classified and represented in the expected export table.
 */
internal data class MeasurementDataQuality(
    val receivedNotifications: Int,
    val acceptedNotifications: Int,
    val rejectedHybrids: Int,
    val diagnosticNotifications: Int,
    val rawRowCount: Int,
    val telemetryRowCount: Int,
    val classifiedNotificationCount: Long,
    val exportIntegrityComplete: Boolean,
    val status: MeasurementDataQualityStatus,
    val label: String,
    val detail: String
) {
    companion object {
        fun evaluate(
            received: Int,
            accepted: Int,
            rejectedHybrids: Int,
            diagnosticNotifications: Int,
            rawRowCount: Int,
            telemetryRowCount: Int
        ): MeasurementDataQuality {
            val counts = listOf(
                received,
                accepted,
                rejectedHybrids,
                diagnosticNotifications,
                rawRowCount,
                telemetryRowCount
            )
            val nonNegative = counts.all { it >= 0 }
            val classified = accepted.toLong() + rejectedHybrids.toLong() +
                diagnosticNotifications.toLong()
            val noData = counts.all { it == 0 }
            val internallyComplete = nonNegative && !noData &&
                received.toLong() == classified &&
                rawRowCount.toLong() == classified &&
                telemetryRowCount == accepted
            val status = when {
                noData -> MeasurementDataQualityStatus.NO_DATA
                !internallyComplete -> MeasurementDataQualityStatus.INCONSISTENT
                rejectedHybrids > 0 || diagnosticNotifications > 0 ->
                    MeasurementDataQualityStatus.COMPLETE_WITH_ISOLATION
                else -> MeasurementDataQualityStatus.CLEAN
            }
            val label = when (status) {
                MeasurementDataQualityStatus.CLEAN -> "Saubere Exportintegrität"
                MeasurementDataQualityStatus.COMPLETE_WITH_ISOLATION ->
                    "Exportintegrität vollständig • isolierte Pakete"
                MeasurementDataQualityStatus.INCONSISTENT -> "Exportintegrität unvollständig"
                MeasurementDataQualityStatus.NO_DATA -> "Keine Messdaten"
            }
            val detail = if (status == MeasurementDataQualityStatus.NO_DATA) {
                "Keine empfangenen Notifications; Exportintegrität und Funkvollständigkeit nicht bewertet."
            } else {
                buildString {
                    append("$classified/$received empfangene Notifications klassifiziert")
                    append(" • RAW $rawRowCount/$classified")
                    append(" • Live $telemetryRowCount/$accepted")
                    if (rejectedHybrids > 0) append(" • $rejectedHybrids Hybrid quarantänisiert")
                    if (diagnosticNotifications > 0) {
                        append(" • $diagnosticNotifications Diagnose-Notification isoliert")
                    }
                    append(" • bewertet Exportintegrität, nicht Funkvollständigkeit")
                }
            }
            return MeasurementDataQuality(
                receivedNotifications = received,
                acceptedNotifications = accepted,
                rejectedHybrids = rejectedHybrids,
                diagnosticNotifications = diagnosticNotifications,
                rawRowCount = rawRowCount,
                telemetryRowCount = telemetryRowCount,
                classifiedNotificationCount = classified,
                exportIntegrityComplete = internallyComplete,
                status = status,
                label = label,
                detail = detail
            )
        }
    }
}

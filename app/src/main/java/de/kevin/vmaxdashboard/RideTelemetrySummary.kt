package de.kevin.vmaxdashboard

import kotlin.math.abs

internal enum class RideBatteryEndStatus(val userLabel: String) {
    CONFIRMED("stabil bestätigt"),
    RECOVERY_PENDING("Erholung läuft – Rohwert nicht als stabil übernommen"),
    VALIDATION_PENDING("Bestätigung läuft – Rohwert nicht neu als stabil übernommen"),
    RAW_ONLY("nur Rohwert vorhanden – noch nicht stabil bestätigt"),
    INVALID_RAW("Rohwert ungültig – stabiler Wert unverändert"),
    UNAVAILABLE("keine Akkudaten vorhanden")
}

/** Deterministic roll-up of the existing 21-column live-telemetry V3 rows. */
internal data class RideTelemetrySummary(
    val telemetryRowCount: Int,
    val distanceKm: Double?,
    val distanceProvenance: MeasurementTripDistanceProvenance?,
    val maxSpeedKmh: Double?,
    val startingBatteryRawPercent: Int?,
    val startingBatteryStablePercent: Int?,
    val endingBatteryRawPercent: Int?,
    val endingBatteryStablePercent: Int?,
    val batteryStatus: RideBatteryEndStatus,
    val minimumRawBatteryPercent: Int?,
    val heldTransientMinimumRawBatteryPercent: Int?,
    val minimumVoltageV: Double?,
    val maxAbsoluteCurrentA: Double?
) {
    fun dashboardSummaryLines(): List<String> = buildList {
        add("Fahrdaten: $telemetryRowCount Telemetriezeilen")
        distanceKm?.let { distance ->
            val source = when (distanceProvenance) {
                MeasurementTripDistanceProvenance.DIRECT_CONFIRMED -> "bestätigtes Trip-Feld"
                MeasurementTripDistanceProvenance.ODOMETER_DELTA -> "Kilometerstand 1506"
                null -> "Quelle unbekannt"
            }
            add("Strecke: ${germanMetric(distance)} km • $source")
        }
        maxSpeedKmh?.let { add("Höchstgeschwindigkeit: ${germanMetric(it)} km/h") }
        batteryPoint("Akku Start", startingBatteryRawPercent, startingBatteryStablePercent)
            ?.let(::add)
        val end = batteryPoint("Akku Ende", endingBatteryRawPercent, endingBatteryStablePercent)
        add((end ?: "Akku Ende: kein Messwert") + " • ${batteryStatus.userLabel}")
        minimumRawBatteryPercent?.let { add("Niedrigster Akku-Rohwert: $it %") }
        heldTransientMinimumRawBatteryPercent?.let {
            add("Kurzzeitig zurückgehaltener Akku-Rohwert: $it %")
        }
        minimumVoltageV?.let { add("Niedrigste Spannung: ${germanMetric(it)} V") }
        maxAbsoluteCurrentA?.let { add("Höchster Strombetrag: ${germanMetric(it)} A") }
    }

    fun userSummaryLines(): List<String> = buildList {
        add("Fahrdaten_Zeilen: $telemetryRowCount")
        distanceKm?.let { add("Strecke_km: $it") }
        distanceProvenance?.let {
            val source = when (it) {
                MeasurementTripDistanceProvenance.DIRECT_CONFIRMED -> "direktes bestätigtes Trip-Feld"
                MeasurementTripDistanceProvenance.ODOMETER_DELTA -> "Kilometerstand-Differenz 1506"
            }
            add("Strecke_Quelle: $source")
        }
        maxSpeedKmh?.let { add("Geschwindigkeit_Max_kmh: $it") }
        startingBatteryRawPercent?.let { add("Akku_Start_roh_Prozent: $it") }
        startingBatteryStablePercent?.let { add("Akku_Start_stabil_Prozent: $it") }
        endingBatteryRawPercent?.let { add("Akku_Ende_roh_Prozent: $it") }
        endingBatteryStablePercent?.let { add("Akku_Ende_stabil_Prozent: $it") }
        add("Akku_Endstatus: ${batteryStatus.userLabel}")
        minimumRawBatteryPercent?.let { add("Akku_Rohminimum_Prozent: $it") }
        heldTransientMinimumRawBatteryPercent?.let {
            add("Akku_Gehaltenes_Rohminimum_Prozent: $it")
        }
        minimumVoltageV?.let { add("Spannung_Min_V: $it") }
        maxAbsoluteCurrentA?.let { add("Strom_Spitze_Abs_A: $it") }
    }

    companion object {
        private const val V3_COLUMN_COUNT = 21
        private const val SPEED_INDEX = 2
        private const val BATTERY_RAW_INDEX = 3
        private const val BATTERY_STABLE_INDEX = 4
        private const val BATTERY_STABILITY_INDEX = 5
        private const val VOLTAGE_INDEX = 6
        private const val CURRENT_INDEX = 7
        private const val TRIP_INDEX = 13
        private const val ODOMETER_INDEX = 14
        private const val SOURCE_CHANNEL_INDEX = 20
        private const val MAX_SPEED_KMH = 100.0
        private const val MAX_VOLTAGE_V = 100.0
        private const val MAX_ABSOLUTE_CURRENT_A = 200.0
        private val HELD_BATTERY_STATES = setOf("RECOVERING_AFTER_LOAD", "HELD_TRANSIENT")

        /**
         * Parses headerless V3 data rows as stored in [MeasurementExportSnapshot].
         * Carried values are ignored for extrema: each metric is accepted only
         * from its confirmed canonical source characteristic.
         */
        fun fromV3Rows(rows: List<String>): RideTelemetrySummary {
            val batterySamples = mutableListOf<BatterySample>()
            val speeds = mutableListOf<Double>()
            val voltages = mutableListOf<Double>()
            val currents = mutableListOf<Double>()
            val tripTracker = MeasurementTripDistanceTracker()
            var odometerStarted = false
            var odometerReading: MeasurementTripDistanceReading? = null
            var exportedOdometerDeltaKm: Double? = null
            var parsedRows = 0

            rows.forEach { row ->
                val cells = row.split(';')
                if (cells.size != V3_COLUMN_COUNT) return@forEach
                parsedRows++
                when (cells[SOURCE_CHANNEL_INDEX].trim().uppercase()) {
                    "1505" -> finiteDouble(cells[SPEED_INDEX])
                        ?.takeIf { it in 0.0..MAX_SPEED_KMH }
                        ?.let(speeds::add)

                    "1506" -> {
                        finiteDouble(cells[TRIP_INDEX])
                            ?.takeIf { it >= 0.0 }
                            ?.let { exported ->
                                exportedOdometerDeltaKm = maxOf(
                                    exportedOdometerDeltaKm ?: 0.0,
                                    exported
                                )
                            }
                        validOdometer(cells[ODOMETER_INDEX])?.let { odometer ->
                            if (!odometerStarted) {
                                tripTracker.reset(odometer)
                                odometerStarted = true
                                odometerReading = tripTracker.current()
                            } else {
                                odometerReading = tripTracker.observe(
                                    directTripKm = null,
                                    odometerKm = odometer
                                )
                            }
                        }
                    }

                    "1509" -> {
                        val raw = cells[BATTERY_RAW_INDEX].trim().toIntOrNull()
                        val stable = cells[BATTERY_STABLE_INDEX].trim().toIntOrNull()
                            ?.takeIf { it in 0..100 }
                        batterySamples += BatterySample(
                            rawPercent = raw,
                            stablePercent = stable,
                            stability = cells[BATTERY_STABILITY_INDEX].trim().uppercase()
                        )
                        finiteDouble(cells[VOLTAGE_INDEX])
                            ?.takeIf { it > 0.0 && it <= MAX_VOLTAGE_V }
                            ?.let(voltages::add)
                        finiteDouble(cells[CURRENT_INDEX])
                            ?.takeIf { abs(it) <= MAX_ABSOLUTE_CURRENT_A }
                            ?.let(currents::add)
                    }
                }
            }

            val lastBattery = batterySamples.lastOrNull()
            val validRawValues = batterySamples.mapNotNull { sample ->
                sample.rawPercent?.takeIf { it in 0..100 }
            }
            val heldRawValues = batterySamples.mapNotNull { sample ->
                sample.rawPercent
                    ?.takeIf { it in 0..100 && sample.stability in HELD_BATTERY_STATES }
            }
            return RideTelemetrySummary(
                telemetryRowCount = parsedRows,
                distanceKm = exportedOdometerDeltaKm ?: odometerReading?.tripKm,
                distanceProvenance = if (exportedOdometerDeltaKm != null) {
                    MeasurementTripDistanceProvenance.ODOMETER_DELTA
                } else {
                    odometerReading?.provenance
                },
                maxSpeedKmh = speeds.maxOrNull(),
                startingBatteryRawPercent = batterySamples.firstNotNullOfOrNull { it.rawPercent },
                startingBatteryStablePercent = batterySamples.firstNotNullOfOrNull { it.stablePercent },
                endingBatteryRawPercent = lastBattery?.rawPercent,
                endingBatteryStablePercent = lastBattery?.stablePercent,
                batteryStatus = batteryEndStatus(lastBattery),
                minimumRawBatteryPercent = validRawValues.minOrNull(),
                heldTransientMinimumRawBatteryPercent = heldRawValues.minOrNull(),
                minimumVoltageV = voltages.minOrNull(),
                maxAbsoluteCurrentA = currents.maxOfOrNull(::abs)
            )
        }

        private fun batteryEndStatus(sample: BatterySample?): RideBatteryEndStatus {
            val raw = sample?.rawPercent ?: return RideBatteryEndStatus.UNAVAILABLE
            if (raw !in 0..100) return RideBatteryEndStatus.INVALID_RAW
            val stable = sample.stablePercent ?: return RideBatteryEndStatus.RAW_ONLY
            return if (sample.stability == "STABLE" && raw == stable) {
                RideBatteryEndStatus.CONFIRMED
            } else if (sample.stability in HELD_BATTERY_STATES) {
                RideBatteryEndStatus.RECOVERY_PENDING
            } else {
                RideBatteryEndStatus.VALIDATION_PENDING
            }
        }

        private fun finiteDouble(value: String): Double? =
            value.trim().toDoubleOrNull()?.takeIf(Double::isFinite)

        private fun validOdometer(value: String): Double? =
            finiteDouble(value)?.takeIf { it >= 0.0 }
    }

    private data class BatterySample(
        val rawPercent: Int?,
        val stablePercent: Int?,
        val stability: String
    )

    private fun batteryPoint(label: String, raw: Int?, stable: Int?): String? {
        if (raw == null && stable == null) return null
        return buildString {
            append("$label:")
            raw?.let { append(" roh $it %") }
            if (raw != null && stable != null) append(" •")
            stable?.let { append(" stabil $it %") }
        }
    }

    private fun germanMetric(value: Double): String =
        java.math.BigDecimal.valueOf(value).stripTrailingZeros().toPlainString().replace('.', ',')

}

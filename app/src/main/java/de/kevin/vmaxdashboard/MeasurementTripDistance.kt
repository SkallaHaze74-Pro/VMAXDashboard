package de.kevin.vmaxdashboard

import kotlin.math.round

/** States whether a measurement trip value came from direct evidence or odometer arithmetic. */
internal enum class MeasurementTripDistanceProvenance {
    DIRECT_CONFIRMED,
    ODOMETER_DELTA
}

internal data class MeasurementTripDistanceReading(
    val tripKm: Double,
    val provenance: MeasurementTripDistanceProvenance
)

/**
 * Measurement-scoped trip accumulator.
 *
 * A reconnect must not reset this object. Call [reset] only when a new
 * measurement segment starts. Invalid or regressing inputs are ignored so they
 * cannot replace the last valid reading.
 */
internal class MeasurementTripDistanceTracker {
    private var baselineOdometerKm: Double? = null
    private var lastOdometerKm: Double? = null
    private var pendingForwardOdometerKm: Double? = null
    private var derivedReading: MeasurementTripDistanceReading? = null
    private var directReading: MeasurementTripDistanceReading? = null

    fun reset(initialOdometerKm: Double? = null) {
        baselineOdometerKm = validDistance(initialOdometerKm)
        lastOdometerKm = baselineOdometerKm
        pendingForwardOdometerKm = null
        derivedReading = baselineOdometerKm?.let {
            MeasurementTripDistanceReading(
                tripKm = 0.0,
                provenance = MeasurementTripDistanceProvenance.ODOMETER_DELTA
            )
        }
        directReading = null
    }

    fun observe(
        directTripKm: Double?,
        odometerKm: Double?
    ): MeasurementTripDistanceReading? {
        observeOdometer(odometerKm)
        observeDirectTrip(directTripKm)
        return current()
    }

    fun current(): MeasurementTripDistanceReading? = directReading ?: derivedReading

    private fun observeOdometer(candidate: Double?) {
        val odometer = validDistance(candidate) ?: return
        val baseline = baselineOdometerKm
        if (baseline == null) {
            baselineOdometerKm = odometer
            lastOdometerKm = odometer
            derivedReading = MeasurementTripDistanceReading(
                tripKm = 0.0,
                provenance = MeasurementTripDistanceProvenance.ODOMETER_DELTA
            )
            return
        }
        val previous = lastOdometerKm ?: baseline
        if (odometer < baseline || odometer < previous) return
        val totalDelta = odometer - baseline
        if (totalDelta > MAX_MEASUREMENT_TRIP_KM) {
            pendingForwardOdometerKm = null
            return
        }
        if (odometer - previous > MAX_UNCONFIRMED_FORWARD_STEP_KM) {
            val pending = pendingForwardOdometerKm
            val confirmsPending = pending != null &&
                odometer >= pending &&
                odometer - pending <= MAX_UNCONFIRMED_FORWARD_STEP_KM
            if (!confirmsPending) {
                pendingForwardOdometerKm = odometer
                return
            }
        }
        pendingForwardOdometerKm = null
        lastOdometerKm = odometer
        derivedReading = MeasurementTripDistanceReading(
            tripKm = roundedTenth(odometer - baseline),
            provenance = MeasurementTripDistanceProvenance.ODOMETER_DELTA
        )
    }

    private fun observeDirectTrip(candidate: Double?) {
        val trip = validDistance(candidate) ?: return
        val previous = directReading?.tripKm
        if (previous != null && trip < previous) return
        directReading = MeasurementTripDistanceReading(
            tripKm = roundedHundredth(trip),
            provenance = MeasurementTripDistanceProvenance.DIRECT_CONFIRMED
        )
    }

    private fun validDistance(value: Double?): Double? =
        value?.takeIf { it.isFinite() && it >= 0.0 }

    private fun roundedTenth(value: Double): Double = round(value * 10.0) / 10.0

    private fun roundedHundredth(value: Double): Double = round(value * 100.0) / 100.0

    private companion object {
        const val MAX_UNCONFIRMED_FORWARD_STEP_KM = 5.0
        const val MAX_MEASUREMENT_TRIP_KM = 500.0
    }
}

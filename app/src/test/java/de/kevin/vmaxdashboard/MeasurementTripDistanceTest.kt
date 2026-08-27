package de.kevin.vmaxdashboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MeasurementTripDistanceTest {
    @Test
    fun odometerDeltaSurvivesReconnectAndResetStartsANewMeasurement() {
        val tracker = MeasurementTripDistanceTracker()

        tracker.reset(initialOdometerKm = 726.0)
        assertReading(
            expectedKm = 0.8,
            expectedProvenance = MeasurementTripDistanceProvenance.ODOMETER_DELTA,
            actual = tracker.observe(directTripKm = null, odometerKm = 726.8)
        )

        assertReading(
            expectedKm = 0.8,
            expectedProvenance = MeasurementTripDistanceProvenance.ODOMETER_DELTA,
            actual = tracker.observe(directTripKm = null, odometerKm = null)
        )
        assertReading(
            expectedKm = 1.1,
            expectedProvenance = MeasurementTripDistanceProvenance.ODOMETER_DELTA,
            actual = tracker.observe(directTripKm = null, odometerKm = 727.1)
        )

        tracker.reset(initialOdometerKm = 727.1)
        assertReading(
            expectedKm = 0.0,
            expectedProvenance = MeasurementTripDistanceProvenance.ODOMETER_DELTA,
            actual = tracker.current()
        )
    }

    @Test
    fun invalidAndRegressingValuesCannotReplaceTheLastValidReading() {
        val tracker = MeasurementTripDistanceTracker()
        tracker.reset(initialOdometerKm = 726.0)
        tracker.observe(directTripKm = null, odometerKm = 726.8)

        listOf(Double.NaN, Double.POSITIVE_INFINITY, -1.0, 725.9, 726.7).forEach { invalid ->
            assertReading(
                expectedKm = 0.8,
                expectedProvenance = MeasurementTripDistanceProvenance.ODOMETER_DELTA,
                actual = tracker.observe(directTripKm = null, odometerKm = invalid)
            )
        }

        assertReading(
            expectedKm = 1.0,
            expectedProvenance = MeasurementTripDistanceProvenance.ODOMETER_DELTA,
            actual = tracker.observe(directTripKm = null, odometerKm = 727.0)
        )
    }

    @Test
    fun oneImplausibleForwardOdometerSampleCannotPoisonTheRideDistance() {
        val tracker = MeasurementTripDistanceTracker()
        tracker.reset(initialOdometerKm = 726.0)
        tracker.observe(directTripKm = null, odometerKm = 726.4)

        assertReading(
            expectedKm = 0.4,
            expectedProvenance = MeasurementTripDistanceProvenance.ODOMETER_DELTA,
            actual = tracker.observe(directTripKm = null, odometerKm = 5_000.0)
        )
        assertReading(
            expectedKm = 0.5,
            expectedProvenance = MeasurementTripDistanceProvenance.ODOMETER_DELTA,
            actual = tracker.observe(directTripKm = null, odometerKm = 726.5)
        )
    }

    @Test
    fun aLargeButPlausibleReconnectAdvanceNeedsASecondConsistentSample() {
        val tracker = MeasurementTripDistanceTracker()
        tracker.reset(initialOdometerKm = 726.0)

        assertReading(
            expectedKm = 0.0,
            expectedProvenance = MeasurementTripDistanceProvenance.ODOMETER_DELTA,
            actual = tracker.observe(directTripKm = null, odometerKm = 746.0)
        )
        assertReading(
            expectedKm = 20.1,
            expectedProvenance = MeasurementTripDistanceProvenance.ODOMETER_DELTA,
            actual = tracker.observe(directTripKm = null, odometerKm = 746.1)
        )
    }

    @Test
    fun confirmedDirectTripHasPriorityAndInvalidDirectInputFallsBackSafely() {
        val tracker = MeasurementTripDistanceTracker()
        tracker.reset(initialOdometerKm = 726.0)

        assertReading(
            expectedKm = 0.9,
            expectedProvenance = MeasurementTripDistanceProvenance.DIRECT_CONFIRMED,
            actual = tracker.observe(directTripKm = 0.9, odometerKm = 726.8)
        )
        assertReading(
            expectedKm = 0.9,
            expectedProvenance = MeasurementTripDistanceProvenance.DIRECT_CONFIRMED,
            actual = tracker.observe(directTripKm = null, odometerKm = null)
        )
        assertReading(
            expectedKm = 0.9,
            expectedProvenance = MeasurementTripDistanceProvenance.DIRECT_CONFIRMED,
            actual = tracker.observe(directTripKm = Double.NaN, odometerKm = 727.0)
        )
        assertReading(
            expectedKm = 0.9,
            expectedProvenance = MeasurementTripDistanceProvenance.DIRECT_CONFIRMED,
            actual = tracker.observe(directTripKm = 0.8, odometerKm = 727.1)
        )

        tracker.reset(initialOdometerKm = null)
        assertNull(tracker.current())
        assertNull(tracker.observe(directTripKm = -0.1, odometerKm = Double.NaN))
    }

    private fun assertReading(
        expectedKm: Double,
        expectedProvenance: MeasurementTripDistanceProvenance,
        actual: MeasurementTripDistanceReading?
    ) {
        requireNotNull(actual)
        assertEquals(expectedKm, actual.tripKm, 0.0001)
        assertEquals(expectedProvenance, actual.provenance)
    }
}

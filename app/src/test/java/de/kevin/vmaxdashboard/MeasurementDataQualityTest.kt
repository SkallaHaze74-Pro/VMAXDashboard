package de.kevin.vmaxdashboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MeasurementDataQualityTest {
    @Test
    fun allAcceptedRowsProduceCleanExportIntegrity() {
        val quality = MeasurementDataQuality.evaluate(
            received = 66,
            accepted = 66,
            rejectedHybrids = 0,
            diagnosticNotifications = 0,
            rawRowCount = 66,
            telemetryRowCount = 66
        )

        assertEquals(66L, quality.classifiedNotificationCount)
        assertTrue(quality.exportIntegrityComplete)
        assertEquals(MeasurementDataQualityStatus.CLEAN, quality.status)
        assertEquals("Saubere Exportintegrität", quality.label)
        assertTrue("66/66 empfangene Notifications klassifiziert" in quality.detail)
        assertTrue("nicht Funkvollständigkeit" in quality.detail)
    }

    @Test
    fun quarantinedAndDiagnosticRowsRemainCompleteButExplicitlyIsolated() {
        val quality = MeasurementDataQuality.evaluate(
            received = 66,
            accepted = 64,
            rejectedHybrids = 1,
            diagnosticNotifications = 1,
            rawRowCount = 66,
            telemetryRowCount = 64
        )

        assertTrue(quality.exportIntegrityComplete)
        assertEquals(MeasurementDataQualityStatus.COMPLETE_WITH_ISOLATION, quality.status)
        assertTrue("1 Hybrid quarantänisiert" in quality.detail)
        assertTrue("1 Diagnose-Notification isoliert" in quality.detail)
    }

    @Test
    fun unclassifiedOrMissingRowsFailClosedAsInconsistent() {
        val unclassified = MeasurementDataQuality.evaluate(
            received = 66,
            accepted = 65,
            rejectedHybrids = 0,
            diagnosticNotifications = 0,
            rawRowCount = 65,
            telemetryRowCount = 65
        )
        val missingLiveRow = MeasurementDataQuality.evaluate(
            received = 66,
            accepted = 66,
            rejectedHybrids = 0,
            diagnosticNotifications = 0,
            rawRowCount = 66,
            telemetryRowCount = 65
        )
        val invalidCounts = MeasurementDataQuality.evaluate(
            received = -1,
            accepted = 0,
            rejectedHybrids = 0,
            diagnosticNotifications = 0,
            rawRowCount = 0,
            telemetryRowCount = 0
        )

        listOf(unclassified, missingLiveRow, invalidCounts).forEach { quality ->
            assertFalse(quality.exportIntegrityComplete)
            assertEquals(MeasurementDataQualityStatus.INCONSISTENT, quality.status)
        }
        assertTrue("65/66 empfangene Notifications klassifiziert" in unclassified.detail)
    }

    @Test
    fun emptyCaptureIsNoDataRatherThanACompleteMeasurement() {
        val quality = MeasurementDataQuality.evaluate(
            received = 0,
            accepted = 0,
            rejectedHybrids = 0,
            diagnosticNotifications = 0,
            rawRowCount = 0,
            telemetryRowCount = 0
        )

        assertFalse(quality.exportIntegrityComplete)
        assertEquals(MeasurementDataQualityStatus.NO_DATA, quality.status)
        assertEquals("Keine Messdaten", quality.label)
    }
}

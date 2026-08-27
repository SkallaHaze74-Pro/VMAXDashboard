package de.kevin.vmaxdashboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.util.TimeZone

class MeasurementPublicationIdentityTest {
    @Test
    fun identityIsStableForRetriesAndDistinctForDifferentRides() {
        assertEquals(
            measurementPublicationIdentity(1_726_000_123_456L),
            measurementPublicationIdentity(1_726_000_123_456L)
        )
        assertNotEquals(
            measurementPublicationIdentity(1_726_000_123_456L),
            measurementPublicationIdentity(1_726_000_123_457L)
        )
    }

    @Test
    fun exportStampIsStableAcrossTimezoneChanges() {
        val original = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Europe/Berlin"))
            val berlin = measurementExportStampUtc(1_726_000_123_456L)
            TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Honolulu"))
            val honolulu = measurementExportStampUtc(1_726_000_123_456L)

            assertEquals(berlin, honolulu)
            assertEquals("2024-09-10_20-28-43-456Z", berlin)
        } finally {
            TimeZone.setDefault(original)
        }
    }

    @Test
    fun legacyHistoryFolderWinsOverUtcFallbackWithoutAllowingTraversal() {
        val startedAt = 1_726_000_123_456L
        val legacy = "VMAXDashboard/Messfahrt_2024-09-10_22-28-43"

        assertEquals(legacy, measurementExportFolder(startedAt, legacy))
        assertEquals(
            "VMAXDashboard/Messfahrt_${measurementExportStampUtc(startedAt)}",
            measurementExportFolder(startedAt, "../../outside")
        )
    }

    @Test
    fun completedLegacyFolderCanBeRecoveredBySummaryWithoutAHistoryRow() {
        val startedAt = 1_726_000_123_456L
        val matching = "VMAXDashboard/Messfahrt_2024-09-10_22-28-43-456"
        val candidates = listOf(
            "VMAXDashboard/Messfahrt_other" to "VMAX Dashboard Messfahrt\nStart: 7\n",
            "../../escape" to "VMAX Dashboard Messfahrt\nStart: $startedAt\n",
            matching to "VMAX Dashboard Messfahrt\nStart: $startedAt\n"
        )

        assertEquals(matching, existingMeasurementFolderForStartedAt(startedAt, candidates))
    }
}

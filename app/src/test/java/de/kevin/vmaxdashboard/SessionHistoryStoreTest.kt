package de.kevin.vmaxdashboard

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class SessionHistoryStoreTest {
    @Test
    fun retryUpsertsMatchingIdentityInsteadOfPrependingDuplicate() {
        val identity = measurementPublicationIdentity(1_726_000_123_456L)
        val original = sessionHistoryRecord(
            publicationIdentity = identity,
            folder = "VMAXDashboard/Messfahrt_alt",
            model = "VX2",
            startedAt = 1_726_000_123_456L,
            stoppedAt = 1_726_000_223_456L,
            packets = 10,
            markers = 1,
            channels = listOf("1505")
        )
        val first = upsertSessionHistoryJson("[]", original)
        val retried = upsertSessionHistoryJson(
            first,
            original.copy(
                folder = "VMAXDashboard/Messfahrt_neu",
                packets = 42,
                channels = listOf("1505", "1509")
            )
        )

        val rows = JSONArray(retried)
        assertEquals(1, rows.length())
        assertEquals(identity, rows.getJSONObject(0).getString("publicationIdentity"))
        assertEquals("VMAXDashboard/Messfahrt_neu", rows.getJSONObject(0).getString("folder"))
        assertEquals(42, rows.getJSONObject(0).getInt("packets"))
    }

    @Test
    fun legacyFolderEntryIsMigratedOnRetryWithoutDuplication() {
        val folder = "VMAXDashboard/Messfahrt_2026-08-26_10-00-00-000"
        val legacy = JSONArray().put(JSONObject().apply {
            put("folder", folder)
            put("model", "VX2")
            put("startedAt", 1_726_000_123_456L)
            put("stoppedAt", 1_726_000_223_456L)
            put("durationMs", 100_000L)
            put("packets", 10)
            put("markers", 0)
            put("channels", JSONArray(listOf("1505")))
        }).toString()
        val identity = measurementPublicationIdentity(1_726_000_123_456L)

        val updated = upsertSessionHistoryJson(
            legacy,
            sessionHistoryRecord(
                publicationIdentity = identity,
                folder = folder,
                model = "VX2",
                startedAt = 1_726_000_123_456L,
                stoppedAt = 1_726_000_223_456L,
                packets = 25,
                markers = 0,
                channels = listOf("1505")
            )
        )

        val rows = JSONArray(updated)
        assertEquals(1, rows.length())
        assertEquals(identity, rows.getJSONObject(0).getString("publicationIdentity"))
        assertEquals(25, rows.getJSONObject(0).getInt("packets"))
    }

    @Test
    fun distinctRideIdentitiesRemainDistinct() {
        fun record(startedAt: Long) = sessionHistoryRecord(
            publicationIdentity = measurementPublicationIdentity(startedAt),
            folder = "VMAXDashboard/Messfahrt_$startedAt",
            model = "VX2",
            startedAt = startedAt,
            stoppedAt = startedAt + 1_000L,
            packets = 1,
            markers = 0,
            channels = emptyList()
        )

        val first = upsertSessionHistoryJson("[]", record(1_000L))
        val second = upsertSessionHistoryJson(first, record(1_001L))

        assertEquals(2, JSONArray(second).length())
    }
}

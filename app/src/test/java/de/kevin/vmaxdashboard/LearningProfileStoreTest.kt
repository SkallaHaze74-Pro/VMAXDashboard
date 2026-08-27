package de.kevin.vmaxdashboard

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class LearningProfileStoreTest {
    private val finding = MeasurementFinding(
        marker = "Licht an",
        channel = "1508",
        byteIndex = 0,
        beforeValue = 0,
        afterValue = 1,
        confidence = 80
    )

    @Test
    fun retryWithExactSessionIdentityDoesNotIncrementAnyAggregate() {
        val identity = measurementPublicationIdentity(1_726_000_123_456L)
        val first = mergeLearningProfileJson(
            existingJson = null,
            findings = listOf(finding),
            model = "VX2",
            createdAt = 10_000L,
            sessionIdentity = identity
        )
        val firstCandidate = first.getJSONArray("candidates").getJSONObject(0)

        val retried = mergeLearningProfileJson(
            existingJson = first.toString(),
            findings = listOf(finding),
            model = "VX2",
            createdAt = 20_000L,
            sessionIdentity = identity
        )
        val retriedCandidate = retried.getJSONArray("candidates").getJSONObject(0)

        assertEquals(1, retriedCandidate.getInt("observations"))
        assertEquals(1, retriedCandidate.getInt("sessionCount"))
        assertEquals(1, retriedCandidate.getInt("consistentPairCount"))
        assertEquals(0, retriedCandidate.getInt("conflictCount"))
        assertEquals(1, retriedCandidate.getJSONObject("pairVotes").getInt("0->1"))
        assertEquals(firstCandidate.getInt("confidence"), retriedCandidate.getInt("confidence"))
    }

    @Test
    fun retryOfTheSameSnapshotProducesByteStableLearningJson() {
        val identity = measurementPublicationIdentity(1_726_000_123_456L)
        val first = mergeLearningProfileJson(
            existingJson = null,
            findings = listOf(finding),
            model = "VX2",
            createdAt = 20_000L,
            sessionIdentity = identity
        )
        val retried = mergeLearningProfileJson(
            existingJson = first.toString(),
            findings = listOf(finding),
            model = "VX2",
            createdAt = 20_000L,
            sessionIdentity = identity
        )

        assertEquals(first.toString(2), retried.toString(2))
    }

    @Test
    fun distinctExactSessionIdentitiesStillAddIndependentVotes() {
        val first = mergeLearningProfileJson(
            existingJson = null,
            findings = listOf(finding),
            model = "VX2",
            createdAt = 10_000L,
            sessionIdentity = measurementPublicationIdentity(1_000L)
        )
        val second = mergeLearningProfileJson(
            existingJson = first.toString(),
            findings = listOf(finding),
            model = "VX2",
            createdAt = 11_000L,
            sessionIdentity = measurementPublicationIdentity(1_001L)
        )

        val candidate = second.getJSONArray("candidates").getJSONObject(0)
        assertEquals(2, candidate.getInt("observations"))
        assertEquals(2, candidate.getInt("sessionCount"))
        assertEquals(2, candidate.getJSONObject("pairVotes").getInt("0->1"))
    }

    @Test
    fun legacyCandidateWithoutSessionKeysRemainsReadableAndBecomesIdempotent() {
        val legacy = JSONObject().apply {
            put("format", "VMAX_LEARNING_PROFILE_V1")
            put("updatedAt", 1L)
            put("candidates", org.json.JSONArray().put(JSONObject().apply {
                put("key", "Licht an|1508|0")
                put("model", "VX2")
                put("label", "Licht an")
                put("channel", "1508")
                put("byteIndex", 0)
                put("confidence", 80)
                put("observations", 1)
                put("status", "candidate")
                put("lastBefore", 0)
                put("lastAfter", 1)
            }))
        }
        val identity = measurementPublicationIdentity(5_000L)
        val migrated = mergeLearningProfileJson(
            existingJson = legacy.toString(),
            findings = listOf(finding),
            model = "VX2",
            createdAt = 2L,
            sessionIdentity = identity
        )
        val retried = mergeLearningProfileJson(
            existingJson = migrated.toString(),
            findings = listOf(finding),
            model = "VX2",
            createdAt = 3L,
            sessionIdentity = identity
        )

        val candidate = retried.getJSONArray("candidates").getJSONObject(0)
        assertEquals(2, candidate.getInt("observations"))
        assertEquals(1, candidate.getInt("sessionCount"))
        assertEquals(1, candidate.getJSONObject("pairVotes").getInt("0->1"))
    }
}

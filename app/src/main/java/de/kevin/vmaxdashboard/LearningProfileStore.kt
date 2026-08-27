package de.kevin.vmaxdashboard

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class LearningProfileStore(context: Context) {
    private val dir = File(context.filesDir, "learning").apply { mkdirs() }
    private val file = File(dir, "decoder_candidates.json")
    private val adaptiveStore = AdaptiveDecoderProfileStore.get(context.applicationContext)

    fun merge(
        findings: List<MeasurementFinding>,
        model: String,
        createdAt: Long,
        sessionIdentity: String
    ) {
        require(sessionIdentity.isNotBlank()) { "sessionIdentity must not be blank" }
        val existingJson = runCatching { file.takeIf(File::exists)?.readText() }.getOrNull()
        val mergedRoot = mergeLearningProfileJson(
            existingJson = existingJson,
            findings = findings,
            model = model,
            createdAt = createdAt,
            sessionIdentity = sessionIdentity
        )
        file.writeText(mergedRoot.toString(2))
        adaptiveStore.importLocalLearning(mergedRoot.toString())
    }

    fun count(): Int {
        val root = loadSanitizedRoot(updatedAt = null)
        return root.optJSONArray("candidates")?.length() ?: 0
    }

    fun exportJson(updatedAt: Long): String = loadSanitizedRoot(updatedAt).toString(2)

    private fun loadSanitizedRoot(updatedAt: Long?): JSONObject {
        val existingJson = runCatching { file.takeIf(File::exists)?.readText() }.getOrNull()
        val preservedUpdatedAt = runCatching { JSONObject(existingJson.orEmpty()).optLong("updatedAt") }
            .getOrDefault(0L)
        val root = sanitizeLearningProfileJson(existingJson, updatedAt ?: preservedUpdatedAt)
        file.writeText(root.toString(2))
        return root
    }
}

internal fun mergeLearningProfileJson(
    existingJson: String?,
    findings: List<MeasurementFinding>,
    model: String,
    createdAt: Long,
    sessionIdentity: String
): JSONObject {
    require(sessionIdentity.isNotBlank()) { "sessionIdentity must not be blank" }
    val root = sanitizeLearningProfileJson(existingJson, createdAt)
    val candidates = root.optJSONArray("candidates") ?: JSONArray()
    val byKey = linkedMapOf<String, JSONObject>()
    for (index in 0 until candidates.length()) {
        val item = candidates.optJSONObject(index) ?: continue
        byKey[item.optString("key")] = item
    }

    findings.filter(::isAllowedLearningFinding).forEach { finding ->
        val key = "${finding.marker}|${finding.channel}|${finding.byteIndex}"
        val old = byKey[key]
        val sessionKeys = readLearningStringSet(old?.optJSONArray("sessionKeys"))
        if (sessionIdentity in sessionKeys) return@forEach

        val updatedSessionKeys = (sessionKeys + sessionIdentity).sorted()
        val sessionCount = updatedSessionKeys.size
        val pairVotes = readLearningPairVotes(old?.optJSONObject("pairVotes"))
        val pairKey = "${finding.beforeValue}->${finding.afterValue}"
        val updatedPairVotes = pairVotes.toMutableMap().apply {
            this[pairKey] = (this[pairKey] ?: 0) + 1
        }
        val dominantPair = updatedPairVotes.maxByOrNull { it.value }?.key ?: pairKey
        val consistentPairCount = updatedPairVotes[dominantPair] ?: 1
        val conflictCount = updatedPairVotes.values.sum() - consistentPairCount

        val previousObservations = old?.optInt("observations", 0) ?: 0
        val observations = previousObservations + 1
        val previousConfidence = old?.optInt("confidence", 0) ?: 0
        val averaged = ((previousConfidence * previousObservations) + finding.confidence) /
            observations.coerceAtLeast(1)
        val confidenceBoost = minOf(sessionCount * 2, 8)
        val conflictPenalty = minOf(conflictCount * 5, 20)
        val strengthenedConfidence = (averaged + confidenceBoost - conflictPenalty).coerceIn(0, 99)

        byKey[key] = JSONObject().apply {
            put("key", key)
            put("model", model)
            put("label", finding.marker)
            put("channel", finding.channel)
            put("byteIndex", finding.byteIndex)
            put("confidence", strengthenedConfidence)
            put("observations", observations)
            put("sessionCount", sessionCount)
            put("consistentPairCount", consistentPairCount)
            put("conflictCount", conflictCount)
            put("dominantPair", dominantPair)
            put("lastBefore", finding.beforeValue)
            put("lastAfter", finding.afterValue)
            put("updatedAt", createdAt)
            put("status", old?.optString("status", "candidate") ?: "candidate")
            put("sessionKeys", JSONArray(updatedSessionKeys))
            put("pairVotes", JSONObject(updatedPairVotes))
        }
    }

    return learningProfileRoot(byKey.values.toList(), createdAt)
}

private fun sanitizeLearningProfileJson(existingJson: String?, updatedAt: Long): JSONObject {
    val existing = if (existingJson.isNullOrBlank()) {
        JSONObject()
    } else {
        runCatching { JSONObject(existingJson) }.getOrElse { JSONObject() }
    }
    val source = existing.optJSONArray("candidates") ?: JSONArray()
    val kept = mutableListOf<JSONObject>()
    for (index in 0 until source.length()) {
        val item = source.optJSONObject(index) ?: continue
        if (isAllowedLearningCandidate(item)) kept += item
    }
    return learningProfileRoot(kept, updatedAt)
}

private fun learningProfileRoot(candidates: List<JSONObject>, updatedAt: Long): JSONObject =
    JSONObject().apply {
        put("format", "VMAX_LEARNING_PROFILE_V1")
        put("updatedAt", updatedAt)
        put("candidates", JSONArray(candidates))
    }

private fun readLearningStringSet(array: JSONArray?): Set<String> {
    if (array == null) return emptySet()
    val out = linkedSetOf<String>()
    for (index in 0 until array.length()) {
        array.optString(index).takeIf { it.isNotBlank() }?.let(out::add)
    }
    return out
}

private fun readLearningPairVotes(obj: JSONObject?): Map<String, Int> {
    if (obj == null) return emptyMap()
    val out = linkedMapOf<String, Int>()
    obj.keys().forEach { key ->
        out[key] = obj.optInt(key, 0).coerceAtLeast(0)
    }
    return out
}

private fun isAllowedLearningFinding(finding: MeasurementFinding): Boolean =
    isAllowedLearningValue(finding.marker, finding.channel, finding.byteIndex)

private fun isAllowedLearningCandidate(item: JSONObject): Boolean =
    isAllowedLearningValue(
        item.optString("label"),
        item.optString("channel"),
        item.optInt("byteIndex", -1)
    )

private fun isAllowedLearningValue(label: String, channel: String, byteIndex: Int): Boolean =
    VmaxDecoderPolicy.isLearningCandidateAllowed(label, channel, byteIndex)

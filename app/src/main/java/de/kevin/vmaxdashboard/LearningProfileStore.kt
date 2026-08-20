package de.kevin.vmaxdashboard

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class LearningProfileStore(context: Context) {
    private val dir = File(context.filesDir, "learning").apply { mkdirs() }
    private val file = File(dir, "decoder_candidates.json")
    private val adaptiveStore = AdaptiveDecoderProfileStore.get(context.applicationContext)

    fun merge(findings: List<MeasurementFinding>, model: String, createdAt: Long) {
        val root = loadSanitizedRoot(createdAt)
        val candidates = root.optJSONArray("candidates") ?: JSONArray()
        val byKey = linkedMapOf<String, JSONObject>()
        for (i in 0 until candidates.length()) {
            val item = candidates.optJSONObject(i) ?: continue
            byKey[item.optString("key")] = item
        }

        val sessionKey = buildSessionKey(model, createdAt)

        findings.filter(::isAllowedFinding).forEach { finding ->
            val key = "${finding.marker}|${finding.channel}|${finding.byteIndex}"
            val old = byKey[key]
            val sessionKeys = readStringSet(old?.optJSONArray("sessionKeys"))
            val seenInThisSession = sessionKey in sessionKeys
            val updatedSessionKeys = (sessionKeys + sessionKey).sorted()
            val sessionCount = updatedSessionKeys.size

            val pairVotes = readPairVotes(old?.optJSONObject("pairVotes"))
            val pairKey = "${finding.beforeValue}->${finding.afterValue}"
            val updatedPairVotes = pairVotes.toMutableMap().apply {
                this[pairKey] = (this[pairKey] ?: 0) + 1
            }
            val dominantPair = updatedPairVotes.maxByOrNull { it.value }?.key ?: pairKey
            val consistentPairCount = updatedPairVotes[dominantPair] ?: 1
            val conflictCount = updatedPairVotes.values.sum() - consistentPairCount

            val previousObservations = old?.optInt("observations", 0) ?: 0
            val observations = if (seenInThisSession) previousObservations else previousObservations + 1
            val previousConfidence = old?.optInt("confidence", 0) ?: 0
            val averaged = if (seenInThisSession && previousObservations > 0) {
                previousConfidence
            } else {
                ((previousConfidence * previousObservations) + finding.confidence) / observations.coerceAtLeast(1)
            }

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

        val mergedRoot = writeRoot(byKey.values.toList(), createdAt)
        adaptiveStore.importLocalLearning(mergedRoot.toString())
    }

    fun count(): Int {
        val root = loadSanitizedRoot(System.currentTimeMillis())
        return root.optJSONArray("candidates")?.length() ?: 0
    }

    fun exportJson(): String = loadSanitizedRoot(System.currentTimeMillis()).toString(2)

    private fun loadSanitizedRoot(updatedAt: Long): JSONObject {
        val existing = if (file.exists()) {
            runCatching { JSONObject(file.readText()) }.getOrElse { JSONObject() }
        } else JSONObject()
        val source = existing.optJSONArray("candidates") ?: JSONArray()
        val kept = mutableListOf<JSONObject>()
        for (i in 0 until source.length()) {
            val item = source.optJSONObject(i) ?: continue
            if (isAllowedCandidate(item)) kept += item
        }
        return writeRoot(kept, updatedAt)
    }

    private fun writeRoot(candidates: List<JSONObject>, updatedAt: Long): JSONObject {
        val out = JSONObject().apply {
            put("format", "VMAX_LEARNING_PROFILE_V1")
            put("updatedAt", updatedAt)
            put("candidates", JSONArray(candidates))
        }
        file.writeText(out.toString(2))
        return out
    }

    private fun readStringSet(array: JSONArray?): Set<String> {
        if (array == null) return emptySet()
        val out = linkedSetOf<String>()
        for (i in 0 until array.length()) {
            array.optString(i).takeIf { it.isNotBlank() }?.let(out::add)
        }
        return out
    }

    private fun readPairVotes(obj: JSONObject?): Map<String, Int> {
        if (obj == null) return emptyMap()
        val out = linkedMapOf<String, Int>()
        obj.keys().forEach { key ->
            out[key] = obj.optInt(key, 0).coerceAtLeast(0)
        }
        return out
    }

    private fun buildSessionKey(model: String, createdAt: Long): String {
        val bucket = createdAt / 60_000L
        return "$model@$bucket"
    }

    private fun isAllowedFinding(finding: MeasurementFinding): Boolean =
        isAllowed(finding.marker, finding.channel, finding.byteIndex)

    private fun isAllowedCandidate(item: JSONObject): Boolean =
        isAllowed(item.optString("label"), item.optString("channel"), item.optInt("byteIndex", -1))

    private fun isAllowed(label: String, channel: String, byteIndex: Int): Boolean =
        VmaxDecoderPolicy.isLearningCandidateAllowed(label, channel, byteIndex)
}

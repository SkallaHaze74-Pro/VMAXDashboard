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

        findings.filter(::isAllowedFinding).forEach { finding ->
            val key = "${finding.marker}|${finding.channel}|${finding.byteIndex}"
            val old = byKey[key]
            val observations = (old?.optInt("observations", 0) ?: 0) + 1
            val oldConfidence = old?.optInt("confidence", 0) ?: 0
            val averaged = ((oldConfidence * (observations - 1)) + finding.confidence) / observations
            byKey[key] = JSONObject().apply {
                put("key", key)
                put("model", model)
                put("label", finding.marker)
                put("channel", finding.channel)
                put("byteIndex", finding.byteIndex)
                put("confidence", averaged.coerceIn(0, 99))
                put("observations", observations)
                put("lastBefore", finding.beforeValue)
                put("lastAfter", finding.afterValue)
                put("updatedAt", createdAt)
                put("status", old?.optString("status", "candidate") ?: "candidate")
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

    private fun isAllowedFinding(finding: MeasurementFinding): Boolean =
        isAllowed(finding.marker, finding.channel, finding.byteIndex)

    private fun isAllowedCandidate(item: JSONObject): Boolean =
        isAllowed(item.optString("label"), item.optString("channel"), item.optInt("byteIndex", -1))

    private fun isAllowed(label: String, channel: String, byteIndex: Int): Boolean = when (channel) {
        "1505", "1506", "1509", "150A", "150C" -> false
        "1508" -> when (byteIndex) {
            0 -> label.contains("Licht", ignoreCase = true) || label.startsWith("Auto", ignoreCase = true)
            3 -> label.contains("Fahrmodus", ignoreCase = true) ||
                label.contains("ECO", ignoreCase = true) ||
                label.contains("SPORT", ignoreCase = true) ||
                label.startsWith("Auto", ignoreCase = true)
            else -> true
        }
        else -> true
    }
}

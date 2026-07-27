package de.kevin.vmaxdashboard

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class LearningProfileStore(context: Context) {
    private val dir = File(context.filesDir, "learning").apply { mkdirs() }
    private val file = File(dir, "decoder_candidates.json")

    fun merge(findings: List<MeasurementFinding>, model: String, createdAt: Long) {
        if (findings.isEmpty()) return
        val root = if (file.exists()) runCatching { JSONObject(file.readText()) }.getOrElse { JSONObject() } else JSONObject()
        val candidates = root.optJSONArray("candidates") ?: JSONArray()
        val byKey = linkedMapOf<String, JSONObject>()
        for (i in 0 until candidates.length()) {
            val item = candidates.optJSONObject(i) ?: continue
            byKey[item.optString("key")] = item
        }
        findings.forEach { finding ->
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
        val out = JSONObject().apply {
            put("format", "VMAX_LEARNING_PROFILE_V1")
            put("updatedAt", createdAt)
            put("candidates", JSONArray(byKey.values.toList()))
        }
        file.writeText(out.toString(2))
    }

    fun count(): Int = runCatching {
        if (!file.exists()) 0 else JSONObject(file.readText()).optJSONArray("candidates")?.length() ?: 0
    }.getOrDefault(0)

    fun exportJson(): String = if (file.exists()) file.readText() else JSONObject().apply {
        put("format", "VMAX_LEARNING_PROFILE_V1")
        put("candidates", JSONArray())
    }.toString(2)
}

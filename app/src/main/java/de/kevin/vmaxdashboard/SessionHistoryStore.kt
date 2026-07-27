package de.kevin.vmaxdashboard

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class SessionHistoryStore(context: Context) {
    private val dir = File(context.filesDir, "history").apply { mkdirs() }
    private val file = File(dir, "sessions.json")

    fun add(folder: String, model: String, startedAt: Long, stoppedAt: Long, packets: Int, markers: Int, channels: List<String>) {
        val existing = if (file.exists()) runCatching { JSONArray(file.readText()) }.getOrElse { JSONArray() } else JSONArray()
        val next = JSONArray()
        next.put(JSONObject().apply {
            put("folder", folder)
            put("model", model)
            put("startedAt", startedAt)
            put("stoppedAt", stoppedAt)
            put("durationMs", stoppedAt - startedAt)
            put("packets", packets)
            put("markers", markers)
            put("channels", JSONArray(channels))
        })
        for (i in 0 until minOf(existing.length(), 99)) next.put(existing.get(i))
        file.writeText(next.toString(2))
    }

    fun count(): Int = runCatching { if (!file.exists()) 0 else JSONArray(file.readText()).length() }.getOrDefault(0)
}

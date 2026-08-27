package de.kevin.vmaxdashboard

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class SessionHistoryStore(context: Context) {
    private val dir = File(context.filesDir, "history")
    private val file = File(dir, "sessions.json")

    fun add(
        publicationIdentity: String,
        folder: String,
        model: String,
        startedAt: Long,
        stoppedAt: Long,
        packets: Int,
        markers: Int,
        channels: List<String>
    ) {
        require(publicationIdentity.isNotBlank()) { "publicationIdentity must not be blank" }
        check(dir.isDirectory || dir.mkdirs()) { "Sitzungsverlauf konnte nicht angelegt werden" }
        val existingJson = runCatching { file.takeIf(File::exists)?.readText() }.getOrNull()
        val record = sessionHistoryRecord(
            publicationIdentity = publicationIdentity,
            folder = folder,
            model = model,
            startedAt = startedAt,
            stoppedAt = stoppedAt,
            packets = packets,
            markers = markers,
            channels = channels
        )
        file.writeText(upsertSessionHistoryJson(existingJson, record))
    }

    fun count(): Int = runCatching { if (!file.exists()) 0 else JSONArray(file.readText()).length() }.getOrDefault(0)

    fun findFolderByStartedAt(startedAt: Long): String? = runCatching {
        if (!file.isFile) return@runCatching null
        val rows = JSONArray(file.readText())
        for (index in 0 until rows.length()) {
            val row = rows.optJSONObject(index) ?: continue
            if (row.optLong("startedAt", Long.MIN_VALUE) == startedAt) {
                return@runCatching row.optString("folder").takeIf(String::isNotBlank)
            }
        }
        null
    }.getOrNull()
}

internal data class SessionHistoryRecord(
    val publicationIdentity: String,
    val folder: String,
    val model: String,
    val startedAt: Long,
    val stoppedAt: Long,
    val packets: Int,
    val markers: Int,
    val channels: List<String>
)

internal fun sessionHistoryRecord(
    publicationIdentity: String,
    folder: String,
    model: String,
    startedAt: Long,
    stoppedAt: Long,
    packets: Int,
    markers: Int,
    channels: List<String>
): SessionHistoryRecord {
    require(publicationIdentity.isNotBlank()) { "publicationIdentity must not be blank" }
    return SessionHistoryRecord(
        publicationIdentity = publicationIdentity,
        folder = folder,
        model = model,
        startedAt = startedAt,
        stoppedAt = stoppedAt,
        packets = packets,
        markers = markers,
        channels = channels.toList()
    )
}

/**
 * Prepends a new ride or replaces every prior publication of that same ride.
 * A legacy row has no publicationIdentity, so its deterministic export folder
 * is used once as a migration key and the replacement gains the new identity.
 */
internal fun upsertSessionHistoryJson(
    existingJson: String?,
    record: SessionHistoryRecord
): String {
    require(record.publicationIdentity.isNotBlank()) { "publicationIdentity must not be blank" }
    val existing = if (existingJson.isNullOrBlank()) {
        JSONArray()
    } else {
        runCatching { JSONArray(existingJson) }.getOrElse { JSONArray() }
    }
    val next = JSONArray().put(record.toJson())
    for (index in 0 until existing.length()) {
        if (next.length() >= MAX_SESSION_HISTORY_ROWS) break
        val old = existing.optJSONObject(index)
        if (old != null && old.matches(record)) continue
        next.put(existing.get(index))
    }
    return next.toString(2)
}

private const val MAX_SESSION_HISTORY_ROWS = 100

private fun SessionHistoryRecord.toJson(): JSONObject = JSONObject().apply {
    put("publicationIdentity", publicationIdentity)
    put("folder", folder)
    put("model", model)
    put("startedAt", startedAt)
    put("stoppedAt", stoppedAt)
    put("durationMs", stoppedAt - startedAt)
    put("packets", packets)
    put("markers", markers)
    put("channels", JSONArray(channels))
}

private fun JSONObject.matches(record: SessionHistoryRecord): Boolean {
    val existingIdentity = optString("publicationIdentity").trim()
    return if (existingIdentity.isNotEmpty()) {
        existingIdentity == record.publicationIdentity
    } else {
        optString("folder") == record.folder
    }
}

package de.kevin.vmaxdashboard

/**
 * Stable identity for every publication attempt of the same captured ride.
 *
 * The measurement start belongs to the durable capture snapshot, so it stays
 * unchanged when an export is retried after a process restart. It is also more
 * precise than the historic minute bucket used by local learning.
 */
internal fun measurementPublicationIdentity(startedAt: Long): String =
    "measurement-v1:$startedAt"

/** Deterministic across retries even when Android's current timezone changed. */
internal fun measurementExportStampUtc(startedAt: Long): String =
    java.text.SimpleDateFormat(
        "yyyy-MM-dd_HH-mm-ss-SSS'Z'",
        java.util.Locale.GERMANY
    ).apply {
        timeZone = java.util.TimeZone.getTimeZone("UTC")
    }.format(java.util.Date(startedAt))

internal fun measurementExportFolder(startedAt: Long, historicalFolder: String?): String {
    val safeHistorical = historicalFolder
        ?.trim()
        ?.takeIf { it.matches(Regex("VMAXDashboard/Messfahrt_[A-Za-z0-9._-]{1,120}")) }
    return safeHistorical ?: "VMAXDashboard/Messfahrt_${measurementExportStampUtc(startedAt)}"
}

internal fun measurementSummaryStartedAt(summary: String): Long? {
    if (!summary.lineSequence().firstOrNull().orEmpty().startsWith("VMAX Dashboard Messfahrt")) {
        return null
    }
    return summary.lineSequence()
        .firstOrNull { it.startsWith("Start:") }
        ?.substringAfter(':')
        ?.trim()
        ?.toLongOrNull()
}

/** Candidate order is authoritative, allowing callers to prefer the newest MediaStore row. */
internal fun existingMeasurementFolderForStartedAt(
    startedAt: Long,
    candidates: List<Pair<String, String>>
): String? = candidates.firstNotNullOfOrNull { (folder, summary) ->
    folder.takeIf {
        measurementSummaryStartedAt(summary) == startedAt &&
            measurementExportFolder(startedAt, it) == it
    }
}

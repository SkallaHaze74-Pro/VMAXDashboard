package de.kevin.vmaxdashboard

import java.util.ArrayDeque

internal data class FrozenMeasurementRows(
    val rawRows: List<String>,
    val markerRows: List<String>,
    val telemetryRows: List<String>
)

internal fun isMeasurementExportComplete(
    coreExportSucceeded: Boolean,
    diagnosticBundleCount: Int,
    diagnosticExportSucceeded: Boolean
): Boolean = coreExportSucceeded &&
    (diagnosticBundleCount <= 0 || diagnosticExportSucceeded)

/** Upserts one scan generation without allowing a late partial copy to replace completion. */
internal fun upsertDiagnosticBundleByScanId(
    existing: List<DiagnosticReadBundle>,
    candidate: DiagnosticReadBundle
): List<DiagnosticReadBundle> {
    val frozenCandidate = candidate.copy(records = candidate.records.toList())
    val index = existing.indexOfFirst { it.scanId == candidate.scanId }
    if (index < 0) return existing + frozenCandidate
    val current = existing[index]
    val replacement = when {
        current.completed && !candidate.completed -> current.copy(records = current.records.toList())
        candidate.completed && !current.completed -> frozenCandidate
        candidate.scanFinishedAt >= current.scanFinishedAt -> frozenCandidate
        else -> current.copy(records = current.records.toList())
    }
    return existing.toMutableList().apply { this[index] = replacement }
}

internal data class ConsolidatedMeasurementRecovery(
    val pending: PendingMeasurementExport,
    val sourceIds: Set<String>
)

/** Publishes exactly one lossless retry for every stable ride identity. */
internal fun consolidateRecoveredMeasurementExports(
    pending: List<PendingMeasurementExport>
): List<ConsolidatedMeasurementRecovery> = pending
    .distinctBy(PendingMeasurementExport::id)
    .groupBy { measurementPublicationIdentity(it.snapshot.startedAt) }
    .values
    .map { sameRide -> consolidateSameRide(sameRide) }
    .sortedBy { it.pending.snapshot.startedAt }

/** A journal created by this live manager must never be mistaken for startup recovery. */
internal fun excludeProcessOwnedActiveExports(
    recovered: List<PendingMeasurementExport>,
    processOwnedStartedAt: Set<Long>
): List<PendingMeasurementExport> = recovered.filterNot {
    it.snapshot.startedAt in processOwnedStartedAt
}

private fun consolidateSameRide(
    sameRide: List<PendingMeasurementExport>
): ConsolidatedMeasurementRecovery {
    require(sameRide.isNotEmpty()) { "Recovery-Gruppe darf nicht leer sein" }
    val ordered = sameRide.sortedWith(
        compareBy<PendingMeasurementExport>(
            { it.snapshot.startedAt },
            { it.snapshot.rawRows.size },
            { it.snapshot.telemetryRows.size },
            { it.snapshot.diagnosticReadBundles.sumOf { bundle -> bundle.records.size } },
            { it.snapshot.diagnosticReadBundles.count(DiagnosticReadBundle::completed) },
            { it.stoppedAt },
            { it.id }
        )
    )
    val richest = ordered.last()
    val chronological = ordered.sortedWith(
        compareBy<PendingMeasurementExport>({ it.stoppedAt }, { it.snapshot.rawRows.size }, { it.id })
    )
    val rawRows = mergeAppendOrderedRows(chronological.map { it.snapshot.rawRows })
    val telemetryRows = mergeAppendOrderedRows(chronological.map { it.snapshot.telemetryRows })
    val cleanStopSnapshots = chronological.filter { pending ->
        pending.snapshot.markerRows.any { markerLabel(it) == "STOP" } &&
            pending.snapshot.markerRows.none { markerLabel(it).startsWith("APP_NEUSTART") }
    }
    val recoveryStopKeys = chronological
        .filter { pending ->
            pending.snapshot.markerRows.any { markerLabel(it).startsWith("APP_NEUSTART") }
        }
        .flatMap { pending ->
            val restartKeys = pending.snapshot.markerRows
                .filter { markerLabel(it).startsWith("APP_NEUSTART") }
                .map(::markerPositionKey)
                .toSet()
            pending.snapshot.markerRows.filter {
                markerLabel(it) == "STOP" && markerPositionKey(it) in restartKeys
            }
        }
        .toSet()
    val markerRows = mergeAppendOrderedRows(chronological.map { it.snapshot.markerRows })
        .let { rows ->
            if (cleanStopSnapshots.isNotEmpty()) {
                val withoutRecoveryClose = rows.filterNot { row ->
                    markerLabel(row).startsWith("APP_NEUSTART") || row in recoveryStopKeys
                }
                val cleanStop = cleanStopSnapshots.last().snapshot.markerRows
                    .last { markerLabel(it) == "STOP" }
                mergeAppendOrderedRows(listOf(withoutRecoveryClose, listOf(cleanStop)))
            } else {
                rows
            }
        }
    val diagnosticBundles = ordered
        .flatMap { it.snapshot.diagnosticReadBundles }
        .fold(emptyList<DiagnosticReadBundle>()) { existing, candidate ->
            upsertDiagnosticBundleByScanId(existing, candidate)
        }
    val stoppedAt = ordered.maxOf(PendingMeasurementExport::stoppedAt)
    val mergedSnapshot = richest.snapshot.copy(
        rawRows = rawRows,
        markerRows = markerRows,
        telemetryRows = telemetryRows,
        connectionCount = ordered.maxOf { it.snapshot.connectionCount },
        receivedNotifications = ordered.maxOf { it.snapshot.receivedNotifications },
        acceptedNotifications = ordered.maxOf { it.snapshot.acceptedNotifications },
        rejectedReads = ordered.maxOf { it.snapshot.rejectedReads },
        rejectedHybrids = ordered.maxOf { it.snapshot.rejectedHybrids },
        diagnosticNotifications = ordered.maxOf { it.snapshot.diagnosticNotifications },
        diagnosticReadBundles = diagnosticBundles
    )
    return ConsolidatedMeasurementRecovery(
        pending = PendingMeasurementExport(
            id = "recovered-publication-${mergedSnapshot.startedAt}",
            stoppedAt = stoppedAt,
            snapshot = mergedSnapshot
        ),
        sourceIds = ordered.mapTo(linkedSetOf(), PendingMeasurementExport::id)
    )
}

/**
 * Recovery inputs are append-ordered snapshots or prefixes/suffixes of them.
 * A richer subsequence is adopted verbatim; missing later occurrences are
 * appended without ever sorting by wall clock, which may move backwards.
 */
private fun mergeAppendOrderedRows(groups: List<List<String>>): List<String> {
    if (groups.isEmpty()) return emptyList()
    var merged = groups.first().toList()
    groups.drop(1).forEach { candidate ->
        merged = when {
            isSubsequence(merged, candidate) -> candidate.toList()
            isSubsequence(candidate, merged) -> merged
            else -> {
                val retainedCounts = merged.groupingBy { it }.eachCount()
                val seenCounts = mutableMapOf<String, Int>()
                val missing = candidate.filter { row ->
                    val occurrence = (seenCounts[row] ?: 0) + 1
                    seenCounts[row] = occurrence
                    occurrence > (retainedCounts[row] ?: 0)
                }
                merged + missing
            }
        }
    }
    return merged
}

private fun isSubsequence(candidate: List<String>, full: List<String>): Boolean {
    if (candidate.isEmpty()) return true
    var candidateIndex = 0
    full.forEach { row ->
        if (row == candidate[candidateIndex]) {
            candidateIndex += 1
            if (candidateIndex == candidate.size) return true
        }
    }
    return false
}

private fun markerLabel(row: String): String =
    row.split(';', limit = 3).getOrNull(2).orEmpty()

private fun markerPositionKey(row: String): String =
    row.split(';', limit = 3).take(2).joinToString(";")

/** Converts a durable active checkpoint into a closed, idempotent export retry. */
internal fun recoveredActiveMeasurementExport(
    active: PendingMeasurementExport
): PendingMeasurementExport {
    val relativeMs = (active.stoppedAt - active.snapshot.startedAt).coerceAtLeast(0L)
    return active.copy(
        id = "recovered-${active.id}",
        snapshot = active.snapshot.copy(
            markerRows = active.snapshot.markerRows + listOf(
                "$relativeMs;${active.stoppedAt};APP_NEUSTART • laufende Messfahrt automatisch gerettet",
                "$relativeMs;${active.stoppedAt};STOP"
            )
        )
    )
}

/**
 * Owns the mutable rows of exactly one active measurement segment.
 *
 * [rotate] freezes the old rows and prepares the optional next segment in one
 * synchronous operation. BleScooterManager calls it while holding its GATT lock,
 * so a Bluetooth callback can only land before the old STOP marker or after the
 * new START marker, never in an export gap.
 */
internal class MeasurementRowBuffer(
    private val maxRows: Int = 100_000
) {
    private val rawRows = mutableListOf<String>()
    private val markerRows = mutableListOf<String>()
    private val telemetryRows = mutableListOf<String>()

    fun start(startedAt: Long) {
        clear()
        appendMarker("START", startedAt, startedAt)
    }

    fun clearRaw() {
        rawRows.clear()
    }

    fun appendRaw(row: String) {
        rawRows += row
        trimToLimit(rawRows)
    }

    fun appendTelemetry(row: String) {
        telemetryRows += row
        trimToLimit(telemetryRows)
    }

    fun appendMarker(label: String, now: Long, startedAt: Long): String {
        val relative = if (startedAt > 0L) now - startedAt else 0L
        val row = listOf(relative.toString(), now.toString(), label).joinToString(";")
        markerRows += row
        return row
    }

    fun rawSnapshot(): List<String> = rawRows.toList()

    fun markerSnapshot(): List<String> = markerRows.toList()

    fun snapshot(): FrozenMeasurementRows = FrozenMeasurementRows(
        rawRows = rawRows.toList(),
        markerRows = markerRows.toList(),
        telemetryRows = telemetryRows.toList()
    )

    fun containsRaw(row: String): Boolean = row in rawRows

    fun prependRaw(rows: List<String>) {
        if (rows.isEmpty()) return
        rawRows.addAll(0, rows)
        while (rawRows.size > maxRows) rawRows.removeAt(0)
    }

    fun rotate(
        currentStartedAt: Long,
        stoppedAt: Long,
        nextStartedAt: Long?
    ): FrozenMeasurementRows {
        appendMarker("STOP", stoppedAt, currentStartedAt)
        val frozen = FrozenMeasurementRows(
            rawRows = rawRows.toList(),
            markerRows = markerRows.toList(),
            telemetryRows = telemetryRows.toList()
        )
        clear()
        nextStartedAt?.let { appendMarker("START", it, it) }
        return frozen
    }

    private fun clear() {
        rawRows.clear()
        markerRows.clear()
        telemetryRows.clear()
    }

    private fun trimToLimit(rows: MutableList<String>) {
        if (rows.size > maxRows) rows.removeAt(0)
    }
}

/** A failed export remains at the head until that exact immutable item succeeds. */
internal class RetainedExportQueue<T> {
    private val pending = ArrayDeque<T>()

    @Synchronized
    fun enqueue(item: T) {
        pending.addLast(item)
    }

    @Synchronized
    fun peek(): T? = pending.peekFirst()

    @Synchronized
    fun replaceHead(expected: T, replacement: T): Boolean {
        if (pending.peekFirst() != expected) return false
        pending.removeFirst()
        pending.addFirst(replacement)
        return true
    }

    @Synchronized
    fun markSucceeded(expected: T): Boolean {
        if (pending.peekFirst() != expected) return false
        pending.removeFirst()
        return true
    }

    val size: Int
        get() = synchronized(this) { pending.size }
}

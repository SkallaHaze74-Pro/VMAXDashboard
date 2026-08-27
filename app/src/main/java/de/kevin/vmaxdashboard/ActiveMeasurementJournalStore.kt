package de.kevin.vmaxdashboard

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.LinkedHashMap
import org.json.JSONObject

/**
 * Small scalar state that changes while an active measurement is running.
 * Rows and diagnostic bundles are separate append-only journal records.
 */
internal data class ActiveMeasurementJournalScalars(
    val deviceName: String,
    val stoppedAt: Long,
    val connectionCount: Int,
    val receivedNotifications: Int,
    val acceptedNotifications: Int,
    val rejectedReads: Int,
    val rejectedHybrids: Int,
    val diagnosticNotifications: Int
)

internal data class ActiveMeasurementJournalRecoveryFailure(
    val fileName: String,
    val message: String
)

internal data class ActiveMeasurementJournalRecovery(
    val pendingExports: List<PendingMeasurementExport>,
    val failures: List<ActiveMeasurementJournalRecoveryFailure>
)

/**
 * Append-only durability for active measurements.
 *
 * Each stable [startedAt] owns one JSON-lines file. Appends never serialize the
 * prior rows again and do not fsync on the caller's thread. Call
 * [syncIfNeeded] (from a background dispatcher) to bound the unsynced window.
 * Recovery replays every valid segment independently, so a save-and-continue
 * rotation or wall-clock rollback cannot make an older file erase a newer one.
 */
internal class ActiveMeasurementJournalStore(private val root: File) {
    private companion object {
        const val SCHEMA = "vmax-active-measurement-journal-v1"
        const val TYPE_START = "START"
        const val TYPE_RAW = "RAW"
        const val TYPE_TELEMETRY = "TELEMETRY"
        const val TYPE_MARKER = "MARKER"
        const val TYPE_SCALARS = "SCALARS"
        const val TYPE_DIAGNOSTIC = "DIAGNOSTIC"
        val JOURNAL_NAME = Regex("segment-p([0-9]+)\\.jsonl")
    }

    private data class RuntimeSegment(
        val file: File,
        val output: FileOutputStream,
        var nextSequence: Long,
        var unsyncedRecords: Int,
        var firstUnsyncedAt: Long?
    )

    private data class ReplayedSegment(
        val startedAt: Long,
        val nextSequence: Long,
        val rawRows: List<String>,
        val markerRows: List<String>,
        val telemetryRows: List<String>,
        val scalars: ActiveMeasurementJournalScalars,
        val diagnosticReadBundles: List<DiagnosticReadBundle>,
        val highWaterAt: Long,
        val invalidTail: Boolean
    )

    private class ReplayAccumulator(
        val startedAt: Long,
        var scalars: ActiveMeasurementJournalScalars,
        startMarker: String,
        recordedAt: Long
    ) {
        val rawRows = mutableListOf<String>()
        val markerRows = mutableListOf(startMarker)
        val telemetryRows = mutableListOf<String>()
        val diagnosticReadBundles = LinkedHashMap<String, DiagnosticReadBundle>()
        var highWaterAt: Long = maxOf(startedAt, scalars.stoppedAt, recordedAt)
    }

    private val runtimeSegments = mutableMapOf<Long, RuntimeSegment>()

    /** Starts a new segment, or opens the exact same segment without a second START marker. */
    @Synchronized
    fun startSegment(startedAt: Long, scalars: ActiveMeasurementJournalScalars) {
        ensureRootDirectory()
        validateStartedAt(startedAt)
        validateScalars(scalars)
        if (segmentFile(startedAt).exists()) {
            requireRuntime(startedAt)
            return
        }

        val start = JSONObject()
            .put("schema", SCHEMA)
            .put("seq", 0L)
            .put("type", TYPE_START)
            .put("recorded_at_ms", startedAt)
            .put("started_at_ms", startedAt)
            .put("marker_row", "0;$startedAt;START")
            .put("scalars", scalarsToJson(scalars))
        val target = segmentFile(startedAt)
        var output: FileOutputStream? = null
        try {
            check(target.createNewFile()) { "Messfahrt-Journal existiert bereits" }
            val openedOutput = FileOutputStream(target, true)
            output = openedOutput
            appendJsonLine(openedOutput, start)
            runtimeSegments[startedAt] = RuntimeSegment(
                file = target,
                output = openedOutput,
                nextSequence = 1L,
                unsyncedRecords = 1,
                firstUnsyncedAt = startedAt
            )
        } catch (error: Throwable) {
            runCatching { output?.close() }
            runtimeSegments.remove(startedAt)
            if (target.length() == 0L) target.delete()
            throw error
        }
    }

    @Synchronized
    fun appendRawRow(startedAt: Long, recordedAt: Long, row: String) {
        appendRow(startedAt, recordedAt, TYPE_RAW, row)
    }

    @Synchronized
    fun appendTelemetryRow(startedAt: Long, recordedAt: Long, row: String) {
        appendRow(startedAt, recordedAt, TYPE_TELEMETRY, row)
    }

    @Synchronized
    fun appendMarkerRow(startedAt: Long, recordedAt: Long, row: String) {
        appendRow(startedAt, recordedAt, TYPE_MARKER, row)
    }

    /** Appends only the small mutable state, never a copy of prior rows. */
    @Synchronized
    fun updateScalars(
        startedAt: Long,
        recordedAt: Long,
        scalars: ActiveMeasurementJournalScalars
    ) {
        validateScalars(scalars)
        appendRecord(startedAt, recordedAt) { sequence ->
            JSONObject()
                .put("seq", sequence)
                .put("type", TYPE_SCALARS)
                .put("recorded_at_ms", recordedAt)
                .put("scalars", scalarsToJson(scalars))
        }
    }

    /** Latest record for a stable scan ID replaces its earlier partial bundle on replay. */
    @Synchronized
    fun replaceDiagnosticBundle(
        startedAt: Long,
        recordedAt: Long,
        bundle: DiagnosticReadBundle
    ) {
        require(bundle.scanId.isNotBlank()) { "Deep-READ scanId darf nicht leer sein" }
        appendRecord(startedAt, recordedAt) { sequence ->
            JSONObject()
                .put("seq", sequence)
                .put("type", TYPE_DIAGNOSTIC)
                .put("recorded_at_ms", recordedAt)
                .put("bundle", diagnosticReadBundleToJson(bundle))
        }
    }

    /**
     * Fsyncs only when the record or time bound is reached. A backwards clock
     * jump is treated as immediately due so it cannot extend the durability gap.
     * Returns true exactly when a sync was performed.
     */
    @Synchronized
    fun syncIfNeeded(
        startedAt: Long,
        nowMs: Long,
        maxUnsyncedRecords: Int,
        maxUnsyncedMs: Long
    ): Boolean {
        require(maxUnsyncedRecords > 0) { "maxUnsyncedRecords muss positiv sein" }
        require(maxUnsyncedMs >= 0L) { "maxUnsyncedMs darf nicht negativ sein" }
        val runtime = requireRuntime(startedAt)
        if (runtime.unsyncedRecords == 0) return false
        val firstUnsyncedAt = runtime.firstUnsyncedAt ?: nowMs
        val countDue = runtime.unsyncedRecords >= maxUnsyncedRecords
        val timeDue = nowMs < firstUnsyncedAt || nowMs - firstUnsyncedAt >= maxUnsyncedMs
        if (!countDue && !timeDue) return false
        syncRuntime(runtime)
        return true
    }

    /** Explicit transition sync; callers should invoke it away from the main thread. */
    @Synchronized
    fun syncSegment(startedAt: Long) {
        syncRuntime(requireRuntime(startedAt))
    }

    /**
     * Recovers every valid journal. The returned exports are deterministic and
     * recovery markers are synthesized in memory, so repeated recovery cannot
     * append duplicate APP_NEUSTART/STOP rows to disk.
     */
    @Synchronized
    fun recoverPendingExports(): List<PendingMeasurementExport> =
        recoverPendingExportsWithDiagnostics().pendingExports

    @Synchronized
    fun recoverPendingExportsWithDiagnostics(): ActiveMeasurementJournalRecovery {
        ensureRootDirectory()
        val pending = mutableListOf<PendingMeasurementExport>()
        val failures = mutableListOf<ActiveMeasurementJournalRecoveryFailure>()
        journalFiles().also {
            // FileOutputStream is unbuffered; flush keeps the visibility intent
            // explicit without forcing storage on the recovery caller.
            runtimeSegments.values.forEach { runtime -> runtime.output.flush() }
        }.forEach { file ->
            runCatching { readJournal(file, truncateInvalidTail = false) }
                .onSuccess { replayed ->
                    pending += pendingExportFromJournal(replayed)
                    if (replayed.invalidTail) {
                        failures += ActiveMeasurementJournalRecoveryFailure(
                            fileName = file.name,
                            message = "Ungültiges oder unvollständiges Journalende; gültiger Präfix gerettet"
                        )
                    }
                }
                .onFailure { error ->
                    failures += ActiveMeasurementJournalRecoveryFailure(
                        fileName = file.name,
                        message = error.message ?: error.javaClass.simpleName
                    )
                }
        }
        return ActiveMeasurementJournalRecovery(
            pendingExports = pending.sortedWith(
                compareBy({ it.snapshot.startedAt }, { it.stoppedAt }, { it.id })
            ),
            failures = failures
        )
    }

    /** Replays one stopped segment on the ordered journal writer before export. */
    @Synchronized
    fun recoverSegmentWithDiagnostics(startedAt: Long): ActiveMeasurementJournalRecovery {
        ensureRootDirectory()
        validateStartedAt(startedAt)
        runtimeSegments[startedAt]?.output?.flush()
        val file = segmentFile(startedAt)
        if (!file.isFile) {
            return ActiveMeasurementJournalRecovery(
                pendingExports = emptyList(),
                failures = listOf(
                    ActiveMeasurementJournalRecoveryFailure(
                        fileName = file.name,
                        message = "Messfahrt-Journal fehlt vor dem vollständigen Export"
                    )
                )
            )
        }
        return runCatching { readJournal(file, truncateInvalidTail = false) }
            .fold(
                onSuccess = { replayed ->
                    ActiveMeasurementJournalRecovery(
                        pendingExports = listOf(pendingExportFromJournal(replayed)),
                        failures = if (replayed.invalidTail) {
                            listOf(
                                ActiveMeasurementJournalRecoveryFailure(
                                    fileName = file.name,
                                    message = "Ungültiges oder unvollständiges Journalende; gültiger Präfix gerettet"
                                )
                            )
                        } else {
                            emptyList()
                        }
                    )
                },
                onFailure = { error ->
                    ActiveMeasurementJournalRecovery(
                        pendingExports = emptyList(),
                        failures = listOf(
                            ActiveMeasurementJournalRecoveryFailure(
                                fileName = file.name,
                                message = error.message ?: error.javaClass.simpleName
                            )
                        )
                    )
                }
            )
    }

    /** Deletes only the requested segment after its immutable spool snapshot was staged. */
    @Synchronized
    fun clearSegment(startedAt: Long) {
        ensureRootDirectory()
        validateStartedAt(startedAt)
        runtimeSegments.remove(startedAt)?.let { runtime ->
            runCatching { runtime.output.close() }
        }
        val target = segmentFile(startedAt)
        check(!target.exists() || target.delete()) { "Messfahrt-Journal konnte nicht entfernt werden" }
    }

    private fun appendRow(startedAt: Long, recordedAt: Long, type: String, row: String) {
        appendRecord(startedAt, recordedAt) { sequence ->
            JSONObject()
                .put("seq", sequence)
                .put("type", type)
                .put("recorded_at_ms", recordedAt)
                .put("row", row)
        }
    }

    private fun appendRecord(
        startedAt: Long,
        recordedAt: Long,
        createRecord: (Long) -> JSONObject
    ) {
        validateRecordedAt(recordedAt)
        val runtime = requireRuntime(startedAt)
        val record = createRecord(runtime.nextSequence)
        try {
            appendJsonLine(runtime.output, record)
            runtime.nextSequence += 1L
            if (runtime.unsyncedRecords == 0) runtime.firstUnsyncedAt = recordedAt
            runtime.unsyncedRecords += 1
        } catch (error: Throwable) {
            // Force the next operation to replay and truncate a possible torn tail.
            runtimeSegments.remove(startedAt)
            runCatching { runtime.output.close() }
            throw error
        }
    }

    private fun requireRuntime(startedAt: Long): RuntimeSegment {
        ensureRootDirectory()
        validateStartedAt(startedAt)
        runtimeSegments[startedAt]?.let { return it }
        val file = segmentFile(startedAt)
        check(file.isFile) { "Messfahrt-Journal wurde nicht gestartet" }
        val replayed = readJournal(file, truncateInvalidTail = true)
        check(replayed.startedAt == startedAt) { "Messfahrt-Journal gehört zu anderem Segment" }
        return RuntimeSegment(
            file = file,
            output = FileOutputStream(file, true),
            nextSequence = replayed.nextSequence,
            // A new store cannot know whether another in-process writer had
            // already fsynced this descriptor. Conservatively make one bounded
            // sync due without rewriting any journal record.
            unsyncedRecords = 1,
            firstUnsyncedAt = replayed.highWaterAt
        ).also { runtimeSegments[startedAt] = it }
    }

    private fun syncRuntime(runtime: RuntimeSegment) {
        if (runtime.unsyncedRecords == 0) return
        runtime.output.flush()
        runtime.output.fd.sync()
        runtime.unsyncedRecords = 0
        runtime.firstUnsyncedAt = null
    }

    private fun ensureRootDirectory() {
        check(root.isDirectory || root.mkdirs()) { "Messfahrt-Journal konnte nicht angelegt werden" }
    }

    private fun readJournal(file: File, truncateInvalidTail: Boolean): ReplayedSegment {
        var accumulator: ReplayAccumulator? = null
        var expectedSequence = 0L
        var validEnd = 0L
        var invalidTail = false
        RandomAccessFile(file, if (truncateInvalidTail) "rw" else "r").use { input ->
            while (true) {
                val line = readCompleteUtf8Line(input) ?: break
                val record = runCatching { JSONObject(line) }.getOrNull() ?: break
                val applied = runCatching {
                    check(record.getLong("seq") == expectedSequence) { "Journal-Sequenz ist unterbrochen" }
                    accumulator = applyRecord(record, accumulator)
                }.isSuccess
                if (!applied) break
                expectedSequence += 1L
                validEnd = input.filePointer
            }
            invalidTail = input.length() != validEnd
            if (truncateInvalidTail && invalidTail) {
                input.setLength(validEnd)
            }
        }

        val complete = checkNotNull(accumulator) { "Messfahrt-Journal hat keinen gültigen START" }
        check(file == segmentFile(complete.startedAt)) { "Messfahrt-Journal-Dateiname passt nicht" }
        return ReplayedSegment(
            startedAt = complete.startedAt,
            nextSequence = expectedSequence,
            rawRows = complete.rawRows.toList(),
            markerRows = complete.markerRows.toList(),
            telemetryRows = complete.telemetryRows.toList(),
            scalars = complete.scalars,
            diagnosticReadBundles = complete.diagnosticReadBundles.values.toList(),
            highWaterAt = complete.highWaterAt,
            invalidTail = invalidTail
        )
    }

    private fun applyRecord(
        record: JSONObject,
        current: ReplayAccumulator?
    ): ReplayAccumulator {
        val type = record.getString("type")
        val recordedAt = record.getLong("recorded_at_ms")
        validateRecordedAt(recordedAt)
        if (current == null) {
            check(type == TYPE_START) { "Erster Journal-Eintrag ist kein START" }
            check(record.getString("schema") == SCHEMA) { "Unbekanntes Journal-Schema" }
            val startedAt = record.getLong("started_at_ms")
            validateStartedAt(startedAt)
            val scalars = scalarsFromJson(record.getJSONObject("scalars"))
            val marker = record.getString("marker_row")
            check(markerLabel(marker) == "START") { "START-Marker fehlt" }
            return ReplayAccumulator(startedAt, scalars, marker, recordedAt)
        }

        check(type != TYPE_START) { "Journal enthält zweiten START" }
        when (type) {
            TYPE_RAW -> current.rawRows += record.getString("row")
            TYPE_TELEMETRY -> current.telemetryRows += record.getString("row")
            TYPE_MARKER -> current.markerRows += record.getString("row")
            TYPE_SCALARS -> current.scalars = scalarsFromJson(record.getJSONObject("scalars"))
            TYPE_DIAGNOSTIC -> {
                val bundle = diagnosticReadBundleFromJson(record.getJSONObject("bundle"))
                check(bundle.scanId.isNotBlank()) { "Deep-READ scanId fehlt" }
                val selected = upsertDiagnosticBundleByScanId(
                    listOfNotNull(current.diagnosticReadBundles[bundle.scanId]),
                    bundle
                ).single()
                current.diagnosticReadBundles[bundle.scanId] = selected
            }
            else -> error("Unbekannter Journal-Eintrag: $type")
        }
        current.highWaterAt = maxOf(
            current.highWaterAt,
            recordedAt,
            current.scalars.stoppedAt
        )
        return current
    }

    private fun pendingExportFromJournal(journal: ReplayedSegment): PendingMeasurementExport {
        val stoppedAt = maxOf(journal.startedAt, journal.highWaterAt, journal.scalars.stoppedAt)
        val markerRows = recoveryMarkers(
            rows = journal.markerRows,
            startedAt = journal.startedAt,
            stoppedAt = stoppedAt
        )
        return PendingMeasurementExport(
            id = "recovered-active-${segmentToken(journal.startedAt)}-stop-${segmentToken(stoppedAt)}",
            stoppedAt = stoppedAt,
            snapshot = MeasurementExportSnapshot(
                rawRows = journal.rawRows,
                markerRows = markerRows,
                telemetryRows = journal.telemetryRows,
                deviceName = journal.scalars.deviceName,
                startedAt = journal.startedAt,
                connectionCount = journal.scalars.connectionCount,
                receivedNotifications = journal.scalars.receivedNotifications,
                acceptedNotifications = journal.scalars.acceptedNotifications,
                rejectedReads = journal.scalars.rejectedReads,
                rejectedHybrids = journal.scalars.rejectedHybrids,
                diagnosticNotifications = journal.scalars.diagnosticNotifications,
                diagnosticReadBundles = journal.diagnosticReadBundles
            )
        )
    }

    private fun recoveryMarkers(rows: List<String>, startedAt: Long, stoppedAt: Long): List<String> {
        val relativeMs = (stoppedAt - startedAt).coerceAtLeast(0L)
        val recovered = "$relativeMs;$stoppedAt;APP_NEUSTART • laufende Messfahrt automatisch gerettet"
        val stop = "$relativeMs;$stoppedAt;STOP"
        val result = rows.toMutableList()
        if (result.none { markerLabel(it).startsWith("APP_NEUSTART") }) {
            val existingStop = result.indexOfLast { markerLabel(it) == "STOP" }
            if (existingStop >= 0) result.add(existingStop, recovered) else result += recovered
        }
        if (result.none { markerLabel(it) == "STOP" }) result += stop
        return result
    }

    private fun scalarsToJson(scalars: ActiveMeasurementJournalScalars): JSONObject = JSONObject()
        .put("device_name", scalars.deviceName)
        .put("stopped_at_ms", scalars.stoppedAt)
        .put("connection_count", scalars.connectionCount)
        .put("received_notifications", scalars.receivedNotifications)
        .put("accepted_notifications", scalars.acceptedNotifications)
        .put("rejected_reads", scalars.rejectedReads)
        .put("rejected_hybrids", scalars.rejectedHybrids)
        .put("diagnostic_notifications", scalars.diagnosticNotifications)

    private fun scalarsFromJson(root: JSONObject): ActiveMeasurementJournalScalars =
        ActiveMeasurementJournalScalars(
            deviceName = root.getString("device_name"),
            stoppedAt = root.getLong("stopped_at_ms"),
            connectionCount = root.getInt("connection_count"),
            receivedNotifications = root.getInt("received_notifications"),
            acceptedNotifications = root.getInt("accepted_notifications"),
            rejectedReads = root.getInt("rejected_reads"),
            rejectedHybrids = root.getInt("rejected_hybrids"),
            diagnosticNotifications = root.getInt("diagnostic_notifications")
        ).also(::validateScalars)

    private fun appendJsonLine(output: FileOutputStream, value: JSONObject) {
        val bytes = (value.toString() + "\n").toByteArray(Charsets.UTF_8)
        output.write(bytes)
    }

    /** Returns null for EOF or a torn line without its terminating newline. */
    private fun readCompleteUtf8Line(input: RandomAccessFile): String? {
        val bytes = ByteArrayOutputStream()
        while (true) {
            when (val next = input.read()) {
                -1 -> return null
                '\n'.code -> return bytes.toString(Charsets.UTF_8.name()).removeSuffix("\r")
                else -> bytes.write(next)
            }
        }
    }

    private fun journalFiles(): List<File> =
        root.listFiles()
            ?.filter { it.isFile && JOURNAL_NAME.matches(it.name) }
            ?.sortedBy { it.name }
            .orEmpty()

    private fun segmentFile(startedAt: Long): File = File(root, "segment-${segmentToken(startedAt)}.jsonl")

    private fun segmentToken(timestamp: Long): String {
        require(timestamp >= 0L) { "Zeitstempel darf nicht negativ sein" }
        return "p$timestamp"
    }

    private fun markerLabel(row: String): String = row.split(';', limit = 3).getOrElse(2) { "" }

    private fun validateStartedAt(startedAt: Long) {
        require(startedAt >= 0L) { "startedAt darf nicht negativ sein" }
    }

    private fun validateRecordedAt(recordedAt: Long) {
        require(recordedAt >= 0L) { "recordedAt darf nicht negativ sein" }
    }

    private fun validateScalars(scalars: ActiveMeasurementJournalScalars) {
        require(scalars.stoppedAt >= 0L) { "stoppedAt darf nicht negativ sein" }
        require(scalars.connectionCount >= 0) { "connectionCount darf nicht negativ sein" }
        require(scalars.receivedNotifications >= 0) { "receivedNotifications darf nicht negativ sein" }
        require(scalars.acceptedNotifications >= 0) { "acceptedNotifications darf nicht negativ sein" }
        require(scalars.rejectedReads >= 0) { "rejectedReads darf nicht negativ sein" }
        require(scalars.rejectedHybrids >= 0) { "rejectedHybrids darf nicht negativ sein" }
        require(scalars.diagnosticNotifications >= 0) {
            "diagnosticNotifications darf nicht negativ sein"
        }
    }
}

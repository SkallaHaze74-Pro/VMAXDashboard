package de.kevin.vmaxdashboard

import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

internal data class MeasurementExportSnapshot(
    val rawRows: List<String>,
    val markerRows: List<String>,
    val telemetryRows: List<String>,
    val deviceName: String,
    val startedAt: Long,
    val connectionCount: Int,
    val receivedNotifications: Int,
    val acceptedNotifications: Int,
    val rejectedReads: Int,
    val rejectedHybrids: Int,
    val diagnosticNotifications: Int,
    val diagnosticReadBundles: List<DiagnosticReadBundle>
)

internal data class PendingMeasurementExport(
    val id: String,
    val stoppedAt: Long,
    val snapshot: MeasurementExportSnapshot
)

internal data class MeasurementExportSpoolRecoveryFailure(
    val fileName: String,
    val message: String
)

internal data class MeasurementExportSpoolRecovery(
    val pendingExports: List<PendingMeasurementExport>,
    val failures: List<MeasurementExportSpoolRecoveryFailure>
)

internal fun newMeasurementExportId(startedAt: Long, stoppedAt: Long): String =
    "measurement-$startedAt-$stoppedAt-${UUID.randomUUID().toString().replace("-", "").take(12)}"

/**
 * App-private write-ahead spool for frozen measurement segments.
 *
 * A complete JSON document is fsynced to a hidden file and atomically renamed
 * in the same directory. The file is removed only after the public export has
 * completed, so a new manager can recover it after process death.
 */
internal class MeasurementExportSpool(private val root: File) {
    private companion object {
        const val SCHEMA = "vmax-measurement-export-spool-v1"
        val SAFE_ID = Regex("[A-Za-z0-9._-]{1,160}")
    }

    @Synchronized
    fun stage(pending: PendingMeasurementExport) {
        ensureRootDirectory()
        require(SAFE_ID.matches(pending.id)) { "Ungültige Export-ID" }
        val target = targetFile(pending.id)
        if (target.isFile) {
            check(readPending(target) == pending) { "Export-ID kollidiert mit anderem Snapshot" }
            return
        }
        val staging = File(root, ".${pending.id}.staging-${System.nanoTime()}")
        try {
            val bytes = pending.toJson().toString().toByteArray(Charsets.UTF_8)
            FileOutputStream(staging).use { output ->
                output.write(bytes)
                output.flush()
                output.fd.sync()
            }
            check(readPending(staging) == pending) { "Export-Spool-Prüfung fehlgeschlagen" }
            check(staging.renameTo(target)) { "Export-Spool konnte nicht atomar übernommen werden" }
        } catch (error: Throwable) {
            staging.delete()
            throw error
        }
    }

    @Synchronized
    fun loadPending(): List<PendingMeasurementExport> =
        loadPendingWithDiagnostics().pendingExports

    @Synchronized
    fun loadPendingWithDiagnostics(): MeasurementExportSpoolRecovery {
        ensureRootDirectory()
        val failures = recoverCompleteStagingFiles().toMutableList()
        val pending = root.listFiles()
            ?.filter { it.isFile && !it.name.startsWith(".") && it.extension == "json" }
            ?.mapNotNull { file ->
                runCatching { readPending(file) }
                    .onFailure { error ->
                        failures += MeasurementExportSpoolRecoveryFailure(
                            fileName = file.name,
                            message = error.message ?: error.javaClass.simpleName
                        )
                    }
                    .getOrNull()
            }
            ?.sortedWith(compareBy({ it.snapshot.startedAt }, { it.stoppedAt }, { it.id }))
            .orEmpty()
        return MeasurementExportSpoolRecovery(pending, failures)
    }

    @Synchronized
    fun contains(id: String): Boolean {
        ensureRootDirectory()
        return SAFE_ID.matches(id) && targetFile(id).isFile
    }

    @Synchronized
    fun removeAfterConfirmedExport(id: String) {
        ensureRootDirectory()
        require(SAFE_ID.matches(id)) { "Ungültige Export-ID" }
        val target = targetFile(id)
        check(!target.exists() || target.delete()) { "Bestätigter Export konnte nicht aus Spool entfernt werden" }
    }

    private fun recoverCompleteStagingFiles(): List<MeasurementExportSpoolRecoveryFailure> {
        val failures = mutableListOf<MeasurementExportSpoolRecoveryFailure>()
        root.listFiles()
            ?.filter { it.isFile && it.name.startsWith(".") && ".staging-" in it.name }
            .orEmpty()
            .forEach { staging ->
                runCatching {
                    val pending = readPending(staging)
                    require(SAFE_ID.matches(pending.id))
                    val target = targetFile(pending.id)
                    when {
                        target.isFile -> {
                            check(readPending(target) == pending) {
                                "Export-ID kollidiert mit anderem Staging-Snapshot"
                            }
                            check(staging.delete()) {
                                "Doppeltes Export-Staging konnte nicht entfernt werden"
                            }
                        }
                        !target.exists() -> check(staging.renameTo(target)) {
                            "Vollständiges Export-Staging konnte nicht wiederhergestellt werden"
                        }
                    }
                }.onFailure { error ->
                    failures += MeasurementExportSpoolRecoveryFailure(
                        fileName = staging.name,
                        message = error.message ?: error.javaClass.simpleName
                    )
                }
            }
        return failures
    }

    private fun ensureRootDirectory() {
        check(root.isDirectory || root.mkdirs()) { "Export-Spool konnte nicht angelegt werden" }
    }

    private fun targetFile(id: String): File = File(root, "$id.json")

    private fun readPending(file: File): PendingMeasurementExport =
        pendingMeasurementExportFromJson(JSONObject(file.readText()))

    private fun PendingMeasurementExport.toJson(): JSONObject = JSONObject()
        .put("schema", SCHEMA)
        .put("id", id)
        .put("stopped_at_ms", stoppedAt)
        .put("snapshot", snapshot.toJson())

    private fun pendingMeasurementExportFromJson(root: JSONObject): PendingMeasurementExport {
        check(root.getString("schema") == SCHEMA) { "Unbekanntes Export-Spool-Schema" }
        return PendingMeasurementExport(
            id = root.getString("id"),
            stoppedAt = root.getLong("stopped_at_ms"),
            snapshot = measurementExportSnapshotFromJson(root.getJSONObject("snapshot"))
        )
    }

    private fun MeasurementExportSnapshot.toJson(): JSONObject = JSONObject()
        .put("raw_rows", JSONArray(rawRows))
        .put("marker_rows", JSONArray(markerRows))
        .put("telemetry_rows", JSONArray(telemetryRows))
        .put("device_name", deviceName)
        .put("started_at_ms", startedAt)
        .put("connection_count", connectionCount)
        .put("received_notifications", receivedNotifications)
        .put("accepted_notifications", acceptedNotifications)
        .put("rejected_reads", rejectedReads)
        .put("rejected_hybrids", rejectedHybrids)
        .put("diagnostic_notifications", diagnosticNotifications)
        .put(
            "diagnostic_read_bundles",
            JSONArray().apply { diagnosticReadBundles.forEach { put(diagnosticReadBundleToJson(it)) } }
        )

    private fun measurementExportSnapshotFromJson(root: JSONObject): MeasurementExportSnapshot =
        MeasurementExportSnapshot(
            rawRows = root.getJSONArray("raw_rows").stringList(),
            markerRows = root.getJSONArray("marker_rows").stringList(),
            telemetryRows = root.getJSONArray("telemetry_rows").stringList(),
            deviceName = root.getString("device_name"),
            startedAt = root.getLong("started_at_ms"),
            connectionCount = root.getInt("connection_count"),
            receivedNotifications = root.getInt("received_notifications"),
            acceptedNotifications = root.getInt("accepted_notifications"),
            rejectedReads = root.getInt("rejected_reads"),
            rejectedHybrids = root.getInt("rejected_hybrids"),
            diagnosticNotifications = root.getInt("diagnostic_notifications"),
            diagnosticReadBundles = root.getJSONArray("diagnostic_read_bundles").objectList {
                diagnosticReadBundleFromJson(it)
            }
        )

    private fun JSONArray.stringList(): List<String> =
        (0 until length()).map(::getString)

    private fun <T> JSONArray.objectList(transform: (JSONObject) -> T): List<T> =
        (0 until length()).map { transform(getJSONObject(it)) }
}

/** Shared exact codec for spool-v1 snapshots and append-only active journals. */
internal fun diagnosticReadBundleToJson(bundle: DiagnosticReadBundle): JSONObject = JSONObject()
    .put(
        "records",
        JSONArray().apply { bundle.records.forEach { put(diagnosticReadRecordToJson(it)) } }
    )
    .put("device_name", bundle.deviceName)
    .put("scan_started_at_ms", bundle.scanStartedAt)
    .put("scan_finished_at_ms", bundle.scanFinishedAt)
    .put("connection_epoch", bundle.connectionEpoch)
    .put("scan_id", bundle.scanId)
    .put("completed", bundle.completed)
    .put("completion_outcome", bundle.completionOutcome.name)

internal fun diagnosticReadBundleFromJson(root: JSONObject): DiagnosticReadBundle =
    DiagnosticReadBundle(
        records = root.getJSONArray("records").jsonObjectList { diagnosticReadRecordFromJson(it) },
        deviceName = root.getString("device_name"),
        scanStartedAt = root.getLong("scan_started_at_ms"),
        scanFinishedAt = root.getLong("scan_finished_at_ms"),
        connectionEpoch = root.getLong("connection_epoch"),
        scanId = root.getString("scan_id"),
        completed = root.getBoolean("completed"),
        completionOutcome = DiagnosticReadOutcome.valueOf(root.getString("completion_outcome"))
    )

private fun diagnosticReadRecordToJson(record: DiagnosticReadRecord): JSONObject = JSONObject()
    .put("timestamp_ms", record.timestampMs)
    .put("service_uuid", record.serviceUuid)
    .put("characteristic_uuid", record.characteristicUuid)
    .put("short_id", record.shortId)
    .put("properties", record.properties)
    .putJsonNullable("status", record.status)
    .put("length", record.length)
    .put("hex", record.hex)
    .put("connection_epoch", record.connectionEpoch)
    .putJsonNullable("measurement_connection_epoch", record.measurementConnectionEpoch)
    .put("evidence", record.evidence)
    .put("meaning", record.meaning)
    .put("scan_id", record.scanId)
    .put("properties_raw", record.propertiesRaw)
    .put("callback_received", record.callbackReceived)
    .put("record_kind", record.recordKind.name)
    .put("outcome", record.outcome.name)
    .put("payload_valid", record.payloadValid)
    .putJsonNullable("rssi", record.rssi)

private fun diagnosticReadRecordFromJson(root: JSONObject): DiagnosticReadRecord =
    DiagnosticReadRecord(
        timestampMs = root.getLong("timestamp_ms"),
        serviceUuid = root.getString("service_uuid"),
        characteristicUuid = root.getString("characteristic_uuid"),
        shortId = root.getString("short_id"),
        properties = root.getString("properties"),
        status = root.jsonNullableInt("status"),
        length = root.getInt("length"),
        hex = root.getString("hex"),
        connectionEpoch = root.getLong("connection_epoch"),
        measurementConnectionEpoch = root.jsonNullableInt("measurement_connection_epoch"),
        evidence = root.getString("evidence"),
        meaning = root.getString("meaning"),
        scanId = root.getString("scan_id"),
        propertiesRaw = root.getInt("properties_raw"),
        callbackReceived = root.getBoolean("callback_received"),
        recordKind = DiagnosticRecordKind.valueOf(root.getString("record_kind")),
        outcome = DiagnosticReadOutcome.valueOf(root.getString("outcome")),
        payloadValid = root.getBoolean("payload_valid"),
        rssi = root.jsonNullableInt("rssi")
    )

private fun JSONObject.putJsonNullable(key: String, value: Any?): JSONObject =
    put(key, value ?: JSONObject.NULL)

private fun JSONObject.jsonNullableInt(key: String): Int? =
    if (isNull(key)) null else getInt(key)

private fun <T> JSONArray.jsonObjectList(transform: (JSONObject) -> T): List<T> =
    (0 until length()).map { transform(getJSONObject(it)) }

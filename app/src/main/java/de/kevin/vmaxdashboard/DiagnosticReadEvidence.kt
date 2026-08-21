package de.kevin.vmaxdashboard

import java.security.MessageDigest
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

internal const val DIAGNOSTIC_READ_CSV_FILE = "Gatt_READ_Diagnose.csv"
internal const val DIAGNOSTIC_READ_SUMMARY_FILE = "Gatt_READ_Summary.txt"
internal const val DIAGNOSTIC_READ_MANIFEST_FILE = "Gatt_READ_manifest.json"

internal enum class DiagnosticRecordKind {
    GATT_READ_CALLBACK,
    GATT_READ_EVENT,
    BLE_OBSERVATION,
    CONNECTION_EVENT
}

internal enum class DiagnosticReadOutcome {
    CALLBACK_SUCCESS,
    CALLBACK_ERROR,
    READ_START_FAILED,
    READ_TIMEOUT,
    CONNECTION_CLOSED,
    CONNECTION_TIMEOUT,
    SERVICE_DISCOVERY_TIMEOUT,
    ADVERTISEMENT_OBSERVED,
    ATTEMPT_STARTED,
    SCAN_COMPLETED,
    SCAN_PARTIAL,
    OTHER
}

internal enum class DiagnosticReadRepresentation {
    LOCAL_EXACT,
    PUBLIC_REDACTED
}

internal fun diagnosticReadOutcome(
    status: Int?,
    callbackReceived: Boolean,
    recordKind: DiagnosticRecordKind
): DiagnosticReadOutcome = when {
    recordKind == DiagnosticRecordKind.BLE_OBSERVATION -> DiagnosticReadOutcome.ADVERTISEMENT_OBSERVED
    callbackReceived && status == 0 -> DiagnosticReadOutcome.CALLBACK_SUCCESS
    callbackReceived -> DiagnosticReadOutcome.CALLBACK_ERROR
    status == -1001 -> DiagnosticReadOutcome.READ_START_FAILED
    status == -1002 -> DiagnosticReadOutcome.READ_TIMEOUT
    status == -1003 -> DiagnosticReadOutcome.CONNECTION_CLOSED
    else -> DiagnosticReadOutcome.OTHER
}

internal fun diagnosticPropertyMask(properties: String): Int {
    val names = properties.split('|', '•').map(String::trim).map(String::uppercase).toSet()
    var mask = 0
    if ("BROADCAST" in names) mask = mask or 0x01
    if ("READ" in names) mask = mask or 0x02
    if ("WRITE_NR" in names) mask = mask or 0x04
    if ("WRITE" in names) mask = mask or 0x08
    if ("NOTIFY" in names) mask = mask or 0x10
    if ("INDICATE" in names) mask = mask or 0x20
    if ("SIGNED_WRITE" in names) mask = mask or 0x40
    if ("EXTENDED_PROPS" in names) mask = mask or 0x80
    return mask
}

/** One exact local diagnostic event, kept outside live telemetry and decoder learning. */
internal data class DiagnosticReadRecord(
    val timestampMs: Long,
    val serviceUuid: String,
    val characteristicUuid: String,
    val shortId: String,
    val properties: String,
    val status: Int?,
    val length: Int,
    val hex: String,
    val connectionEpoch: Long,
    val measurementConnectionEpoch: Int?,
    val evidence: String,
    val meaning: String,
    val scanId: String = "",
    val propertiesRaw: Int = diagnosticPropertyMask(properties),
    val callbackReceived: Boolean = status != null && status >= 0,
    val recordKind: DiagnosticRecordKind = if (callbackReceived) {
        DiagnosticRecordKind.GATT_READ_CALLBACK
    } else {
        DiagnosticRecordKind.GATT_READ_EVENT
    },
    val outcome: DiagnosticReadOutcome = diagnosticReadOutcome(status, callbackReceived, recordKind),
    val payloadValid: Boolean = callbackReceived && status == 0,
    val rssi: Int? = null
)

internal fun diagnosticReadScanId(scanStartedAt: Long, connectionEpoch: Long): String =
    "scan-${scanStartedAt}-e${connectionEpoch}-${UUID.randomUUID().toString().replace("-", "").take(12)}"

/** Immutable result of one READ/observation window. */
internal data class DiagnosticReadBundle(
    val records: List<DiagnosticReadRecord>,
    val deviceName: String,
    val scanStartedAt: Long,
    val scanFinishedAt: Long,
    val connectionEpoch: Long,
    val scanId: String = diagnosticReadScanId(scanStartedAt, connectionEpoch),
    val completed: Boolean = true,
    val completionOutcome: DiagnosticReadOutcome = if (completed) {
        DiagnosticReadOutcome.SCAN_COMPLETED
    } else {
        DiagnosticReadOutcome.SCAN_PARTIAL
    }
)

internal fun diagnosticReadFolderName(bundle: DiagnosticReadBundle): String {
    val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss-SSS", Locale.GERMANY)
        .format(Date(bundle.scanStartedAt))
    val safeScanId = bundle.scanId.replace(Regex("[^A-Za-z0-9._-]"), "_").take(96)
    return "DeepRead_${stamp}_${safeScanId.ifBlank { diagnosticReadScanId(bundle.scanStartedAt, bundle.connectionEpoch) }}"
}

/** Preserve the exact callback snapshot for every real callback, including GATT errors. */
internal fun diagnosticReadHex(@Suppress("UNUSED_PARAMETER") status: Int, value: ByteArray): String =
    value.joinToString("-") { "%02X".format(it.toInt() and 0xFF) }

internal data class DiagnosticReadCounts(
    val attempts: Int,
    val callbacks: Int,
    val successes: Int,
    val payloadCallbacks: Int,
    val validPayloads: Int,
    val observationPayloads: Int,
    val observations: Int
)

internal fun diagnosticReadCounts(records: List<DiagnosticReadRecord>): DiagnosticReadCounts {
    val readKinds = setOf(DiagnosticRecordKind.GATT_READ_CALLBACK, DiagnosticRecordKind.GATT_READ_EVENT)
    return DiagnosticReadCounts(
        attempts = records.count { it.recordKind in readKinds },
        callbacks = records.count(DiagnosticReadRecord::callbackReceived),
        successes = records.count { it.callbackReceived && it.status == 0 },
        payloadCallbacks = records.count { it.callbackReceived && it.hex.isNotBlank() },
        validPayloads = records.count {
            it.recordKind == DiagnosticRecordKind.GATT_READ_CALLBACK &&
                it.callbackReceived && it.status == 0 && it.payloadValid && it.hex.isNotBlank()
        },
        observationPayloads = records.count {
            it.recordKind == DiagnosticRecordKind.BLE_OBSERVATION &&
                it.payloadValid && it.hex.isNotBlank()
        },
        observations = records.count { it.recordKind !in readKinds }
    )
}

internal fun diagnosticRecordsForBundles(bundles: List<DiagnosticReadBundle>): List<DiagnosticReadRecord> =
    bundles.flatMap { bundle ->
        bundle.records.map { record ->
            if (record.scanId == bundle.scanId) record else record.copy(scanId = bundle.scanId)
        }
    }

internal fun buildDiagnosticReadCsv(
    records: List<DiagnosticReadRecord>,
    representation: DiagnosticReadRepresentation = DiagnosticReadRepresentation.LOCAL_EXACT
): String {
    val local = buildString {
        appendLine(
            "timestamp_ms;scan_id;record_kind;outcome;callback_received;service_uuid;" +
                "characteristic_uuid;short_id;properties;properties_raw;status;length;hex;payload_valid;" +
                "payload_sha256;public_redaction;connection_epoch;measurement_connection_epoch;rssi;evidence;meaning"
        )
        records.forEach { record ->
            appendLine(
                listOf(
                    record.timestampMs.toString(),
                    record.scanId,
                    record.recordKind.name,
                    record.outcome.name,
                    record.callbackReceived.toString(),
                    record.serviceUuid,
                    record.characteristicUuid,
                    record.shortId,
                    record.properties,
                    record.propertiesRaw.toString(),
                    record.status?.toString().orEmpty(),
                    record.length.toString(),
                    record.hex,
                    record.payloadValid.toString(),
                    record.hex.takeIf(String::isNotBlank)?.let(::diagnosticPayloadSha256).orEmpty(),
                    "",
                    record.connectionEpoch.toString(),
                    record.measurementConnectionEpoch?.toString().orEmpty(),
                    record.rssi?.toString().orEmpty(),
                    record.evidence,
                    record.meaning
                ).joinToString(";") { diagnosticCsvCell(it) }
            )
        }
    }
    return if (representation == DiagnosticReadRepresentation.PUBLIC_REDACTED) {
        redactDiagnosticReadCsvForPublic(local)
    } else {
        local
    }
}

private fun diagnosticSha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes)
    .joinToString("") { "%02x".format(it.toInt() and 0xFF) }

internal fun diagnosticTextSha256(text: String): String =
    diagnosticSha256(text.toByteArray(Charsets.UTF_8))

private val publicIdentityDigest = Regex("^redacted_sha256:([0-9a-fA-F]{64})$")

internal fun diagnosticPublicIdentityLabel(identity: String): String {
    val clean = identity.trim()
    val existingDigest = publicIdentityDigest.matchEntire(clean)?.groupValues?.get(1)
    return when {
        clean.isBlank() || clean.equals("redacted", ignoreCase = true) -> "redacted"
        existingDigest != null -> "redacted_sha256:${existingDigest.lowercase()}"
        else -> "redacted_sha256:${diagnosticTextSha256(clean)}"
    }
}

internal fun diagnosticPublicDeviceLabel(identity: String): String {
    val clean = identity.trim()
    return if (clean.equals("BT638", ignoreCase = true)) {
        "BT638"
    } else {
        diagnosticPublicIdentityLabel(clean)
    }
}

internal fun diagnosticPayloadSha256(hex: String): String {
    val parts = hex.replace(':', '-').split('-').filter(String::isNotBlank)
    val bytes = if (parts.isNotEmpty() && parts.all { it.length == 2 && it.toIntOrNull(16) != null }) {
        parts.map { it.toInt(16).toByte() }.toByteArray()
    } else {
        hex.toByteArray(Charsets.UTF_8)
    }
    return diagnosticSha256(bytes)
}

private val publicSensitiveShortIds = setOf("1511", "1513", "1516", "1517", "1518", "2A00", "2A25")
private val publicSensitiveMeaningTerms = listOf(
    "serial", "debug", "errorstring", "device name", "identity", "identität", "advertisement"
)

private fun diagnosticHexBytes(hex: String): ByteArray = hex.replace(':', '-').split('-')
        .filter(String::isNotBlank)
        .mapNotNull { it.takeIf { part -> part.length == 2 }?.toIntOrNull(16) }
        .map(Int::toByte)
        .toByteArray()

private fun looksLikeHumanText(value: String, minimumCodePoints: Int = 1): Boolean {
    val clean = value.trim('\u0000', '\uFEFF', ' ', '\t', '\r', '\n')
    if (clean.codePointCount(0, clean.length) < minimumCodePoints) return false
    val codePoints = clean.codePoints().toArray()
    val readable = codePoints.count { codePoint ->
        Character.isLetterOrDigit(codePoint) || Character.isSpaceChar(codePoint) ||
            codePoint in listOf('-'.code, '_'.code, '.'.code, ':'.code, '/'.code, '@'.code)
    }
    return codePoints.any { Character.isLetterOrDigit(it) } &&
        readable >= minimumCodePoints &&
        readable.toDouble() / codePoints.size >= 0.80
}

private fun decodeStrict(bytes: ByteArray, charset: java.nio.charset.Charset): String? =
    runCatching {
        charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    }.getOrNull()

private fun hasUtf16Bom(bytes: ByteArray): Boolean =
    bytes.size >= 2 && (
        (bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) ||
            (bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte())
        )

private fun looksLikeEncodedHumanText(bytes: ByteArray, minimumCodePoints: Int = 1): Boolean {
    if (decodeStrict(bytes, Charsets.UTF_8)?.let { looksLikeHumanText(it, minimumCodePoints) } == true) return true
    if (bytes.size >= 4 && bytes.size % 2 == 0) {
        val hasUtf16Shape = hasStrongUtf16Shape(bytes, minimumBytes = 4) || hasUtf16Bom(bytes)
        if (hasUtf16Shape && (
                decodeStrict(bytes, Charsets.UTF_16LE)?.let {
                    looksLikeHumanText(it, minimumCodePoints)
                } == true ||
                    decodeStrict(bytes, Charsets.UTF_16BE)?.let {
                        looksLikeHumanText(it, minimumCodePoints)
                    } == true
                )
        ) return true
    }
    return false
}

private fun isProtocolFramingByte(byte: Byte): Boolean {
    val value = byte.toInt() and 0xFF
    return value < 0x20 || value >= 0x7F
}

private fun hasStrongUtf16Shape(bytes: ByteArray, minimumBytes: Int = 8): Boolean {
    if (bytes.size < minimumBytes || bytes.size % 2 != 0) return false
    val codeUnits = bytes.size / 2
    val evenNulls = bytes.indices.count { index -> index % 2 == 0 && bytes[index] == 0.toByte() }
    val oddNulls = bytes.indices.count { index -> index % 2 == 1 && bytes[index] == 0.toByte() }
    val dominant = maxOf(evenNulls, oddNulls)
    val other = minOf(evenNulls, oddNulls)
    return dominant * 4 >= codeUnits * 3 && other * 4 <= codeUnits
}

private fun looksLikeProtocolFramedHumanText(bytes: ByteArray): Boolean {
    if (bytes.size < 7) return false
    val maxEdgeBytes = minOf(4, bytes.lastIndex)
    for (trimmedStart in 0..maxEdgeBytes) {
        for (trimmedEnd in 0..maxEdgeBytes) {
            if (trimmedStart + trimmedEnd == 0 || trimmedStart + trimmedEnd >= bytes.size) continue
            if (trimmedStart + trimmedEnd > 4) continue
            if (!bytes.take(trimmedStart).all(::isProtocolFramingByte)) continue
            if (!bytes.takeLast(trimmedEnd).all(::isProtocolFramingByte)) continue
            val endExclusive = bytes.size - trimmedEnd
            val candidate = bytes.copyOfRange(trimmedStart, endExclusive)
            val utf8 = decodeStrict(candidate, Charsets.UTF_8)
            if (utf8 != null && looksLikeHumanText(utf8, minimumCodePoints = 6)) return true
            if ((hasStrongUtf16Shape(candidate) || hasUtf16Bom(candidate)) && (
                    decodeStrict(candidate, Charsets.UTF_16LE)?.let {
                        looksLikeHumanText(it, minimumCodePoints = 4)
                    } == true ||
                        decodeStrict(candidate, Charsets.UTF_16BE)?.let {
                            looksLikeHumanText(it, minimumCodePoints = 4)
                        } == true
                    )
            ) return true
        }
    }
    return false
}

private fun looksLikeFreeFormText(hex: String): Boolean {
    val bytes = diagnosticHexBytes(hex)
    if (bytes.isEmpty()) return false
    if (looksLikeEncodedHumanText(bytes)) return true

    // GPST/BT638 fields can wrap UTF-8 or UTF-16 text in protocol bytes or a
    // checksum. Try bounded edge slices without assuming a universal marker.
    return looksLikeProtocolFramedHumanText(bytes)
}

private fun isPublicSensitiveDiagnosticRow(
    shortId: String,
    characteristicUuid: String,
    meaning: String,
    recordKind: String
): Boolean {
    val canonicalShort = diagnosticShortUuid(shortId.ifBlank { characteristicUuid })
    val lowerMeaning = meaning.lowercase()
    return canonicalShort in publicSensitiveShortIds ||
        recordKind.equals(DiagnosticRecordKind.BLE_OBSERVATION.name, ignoreCase = true) ||
        publicSensitiveMeaningTerms.any(lowerMeaning::contains)
}

/**
 * Produce the only representation allowed to leave the device through public GitHub sync.
 * Exact bytes remain in the local CSV; identity, free-form diagnostic and advertisement
 * bytes are replaced by a stable SHA-256 digest in the public copy.
 */
internal fun redactDiagnosticReadCsvForPublic(csv: String): String {
    val rows = parseDiagnosticCsv(csv)
    require(rows.isNotEmpty() || csv.isBlank()) { "Deep READ CSV could not be parsed safely" }
    if (rows.isEmpty()) return csv
    val header = rows.first().mapIndexed { index, value ->
        if (index == 0) value.removePrefix("\uFEFF") else value
    }.toMutableList()
    fun ensureColumn(name: String): Int {
        val current = header.indexOf(name)
        if (current >= 0) return current
        header += name
        return header.lastIndex
    }

    val hexIndex = header.indexOf("hex")
    require(hexIndex >= 0) { "Deep READ CSV has no recognized hex column" }
    val shortIdIndex = header.indexOf("short_id")
    val characteristicIndex = header.indexOf("characteristic_uuid")
    val meaningIndex = header.indexOf("meaning")
    val recordKindIndex = header.indexOf("record_kind")
    val hashIndex = ensureColumn("payload_sha256")
    val redactionIndex = ensureColumn("public_redaction")

    val publicRows = mutableListOf<List<String>>()
    publicRows += header
    rows.drop(1).forEach { source ->
        if (source.all(String::isBlank)) return@forEach
        val row = source.toMutableList().apply { while (size < header.size) add("") }
        val shortId = shortIdIndex.takeIf { it >= 0 }?.let { row.getOrElse(it) { "" } }.orEmpty()
        val characteristic = characteristicIndex.takeIf { it >= 0 }?.let { row.getOrElse(it) { "" } }.orEmpty()
        val meaning = meaningIndex.takeIf { it >= 0 }?.let { row.getOrElse(it) { "" } }.orEmpty()
        val kind = recordKindIndex.takeIf { it >= 0 }?.let { row.getOrElse(it) { "" } }.orEmpty()
        val exactHex = row.getOrElse(hexIndex) { "" }
        if (isPublicSensitiveDiagnosticRow(shortId, characteristic, meaning, kind) || looksLikeFreeFormText(exactHex)) {
            if (exactHex.isNotBlank()) row[hashIndex] = diagnosticPayloadSha256(exactHex)
            row[hexIndex] = ""
            if (meaningIndex >= 0) row[meaningIndex] = "REDACTED_IDENTITY_OR_FREE_FORM"
            row[redactionIndex] = "identity_or_free_form"
        }
        publicRows += row
    }
    return renderDiagnosticCsv(publicRows)
}

/**
 * Keep newly discovered notification bytes exact on the phone, but prevent a
 * serial/debug/name-like payload from entering the public telemetry branch via
 * BLE_Rohdaten.csv. Extra columns preserve a stable comparison hash without
 * presenting that hash as decodable packet bytes.
 */
internal fun redactRawTelemetryCsvForPublic(csv: String): String {
    val rows = parseDiagnosticCsv(csv)
    require(rows.isNotEmpty() || csv.isBlank()) { "Raw telemetry CSV could not be parsed safely" }
    if (rows.isEmpty()) return csv
    val header = rows.first().mapIndexed { index, value ->
        if (index == 0) value.removePrefix("\uFEFF") else value
    }.toMutableList()
    fun requireColumn(name: String): Int = header.indexOf(name).also { index ->
        require(index >= 0) { "Raw telemetry CSV has no $name column" }
    }
    fun ensureColumn(name: String): Int {
        val current = header.indexOf(name)
        if (current >= 0) return current
        header += name
        return header.lastIndex
    }

    val channelIndex = requireColumn("channel")
    val hexIndex = requireColumn("hex")
    val hashIndex = ensureColumn("payload_sha256")
    val redactionIndex = ensureColumn("public_redaction")
    val publicRows = mutableListOf<List<String>>()
    publicRows += header
    rows.drop(1).forEach { source ->
        if (source.all(String::isBlank)) return@forEach
        val row = source.toMutableList().apply { while (size < header.size) add("") }
        val channel = diagnosticShortUuid(row.getOrElse(channelIndex) { "" })
        val exactHex = row.getOrElse(hexIndex) { "" }
        if (channel in publicSensitiveShortIds || looksLikeFreeFormText(exactHex)) {
            if (exactHex.isNotBlank()) row[hashIndex] = diagnosticPayloadSha256(exactHex)
            row[hexIndex] = ""
            row[redactionIndex] = "identity_or_free_form"
        }
        publicRows += row
    }
    return renderDiagnosticCsv(publicRows)
}

internal fun buildDiagnosticReadSummary(
    bundles: List<DiagnosticReadBundle>,
    representation: DiagnosticReadRepresentation = DiagnosticReadRepresentation.LOCAL_EXACT
): String {
    val records = diagnosticRecordsForBundles(bundles)
    val counts = diagnosticReadCounts(records)
    val startedAt = bundles.minOfOrNull(DiagnosticReadBundle::scanStartedAt) ?: 0L
    val finishedAt = bundles.maxOfOrNull(DiagnosticReadBundle::scanFinishedAt) ?: startedAt
    val deviceNames = bundles.map(DiagnosticReadBundle::deviceName).filter(String::isNotBlank).distinct()
    val serializedDeviceNames = if (representation == DiagnosticReadRepresentation.PUBLIC_REDACTED) {
        deviceNames.map(::diagnosticPublicDeviceLabel)
    } else {
        deviceNames
    }
    val epochs = records.map(DiagnosticReadRecord::connectionEpoch).distinct().sorted()
    val measurementEpochs = records.mapNotNull(DiagnosticReadRecord::measurementConnectionEpoch).distinct().sorted()
    return buildString {
        appendLine("VMAX BT638 Deep READ")
        appendLine("Gerät: ${serializedDeviceNames.joinToString(",").ifBlank { "BT638" }}")
        appendLine("Start: $startedAt")
        appendLine("Ende: $finishedAt")
        appendLine("Dauer_ms: ${(finishedAt - startedAt).coerceAtLeast(0L)}")
        appendLine("Scan_IDs: ${bundles.joinToString(",") { it.scanId }}")
        appendLine("READ_Scans: ${bundles.size}")
        appendLine("READ_Versuche: ${counts.attempts}")
        appendLine("READ_Antworten: ${counts.callbacks}")
        appendLine("READ_Callbacks: ${counts.callbacks}")
        appendLine("READ_Erfolgreich: ${counts.successes}")
        appendLine("READ_Callback_Payloads: ${counts.payloadCallbacks}")
        appendLine("READ_Valide_Payloads: ${counts.validPayloads}")
        appendLine("Advertisement_Payloads: ${counts.observationPayloads}")
        appendLine("Beobachtungen: ${counts.observations}")
        appendLine("Characteristics: ${records.filter { it.characteristicUuid.isNotBlank() }.map { "${it.serviceUuid}/${it.characteristicUuid}" }.toSet().size}")
        appendLine("GATT_Verbindungsepochen: ${epochs.joinToString(",")}")
        appendLine("Messfahrt_Verbindungsepochen: ${measurementEpochs.joinToString(",")}")
        appendLine("Vollständige_Scans: ${bundles.count(DiagnosticReadBundle::completed)}")
        appendLine("Teil_Scans: ${bundles.count { !it.completed }}")
        appendLine("Modus: STRICT_READ_ONLY")
        appendLine("BluetoothAdresse: nicht gespeichert")
        appendLine("Hinweis: READ-Daten werden niemals als Live-Telemetrie oder automatische Decoder-Bestätigung behandelt.")
    }
}

/** Redact a locally generated Deep READ summary before it enters a public queue. */
internal fun redactDiagnosticReadSummaryForPublic(summary: String): String =
    summary.trimEnd('\r', '\n').lineSequence().joinToString("\n") { line ->
        val key = line.substringBefore(':', "").trim().lowercase()
        if (key !in setOf(
                "gerät", "device", "device_name", "serial", "serial_number",
                "bluetoothadresse", "bluetooth_address"
            )
        ) return@joinToString line
        val identity = line.substringAfter(':').trim()
        val label = line.substringBefore(':').trim()
        val publicIdentity = if (key in setOf("gerät", "device", "device_name")) {
            diagnosticPublicDeviceLabel(identity)
        } else {
            diagnosticPublicIdentityLabel(identity)
        }
        "$label: $publicIdentity"
    } + if (summary.endsWith('\n')) "\n" else ""

private fun diagnosticShortUuid(value: String): String {
    val normalized = value.trim().uppercase()
    return when {
        normalized.length == 4 -> normalized
        normalized.matches(Regex("^[0-9A-F]{8}$")) -> normalized.takeLast(4)
        normalized.length >= 8 -> normalized.substring(4, 8)
        else -> normalized
    }
}

private fun parseDiagnosticCsv(csv: String): List<List<String>> {
    val rows = mutableListOf<List<String>>()
    var row = mutableListOf<String>()
    val cell = StringBuilder()
    var quoted = false
    var index = 0
    while (index < csv.length) {
        val char = csv[index]
        when {
            char == '"' && quoted && index + 1 < csv.length && csv[index + 1] == '"' -> {
                cell.append('"')
                index++
            }
            char == '"' -> quoted = !quoted
            char == ';' && !quoted -> {
                row += cell.toString()
                cell.setLength(0)
            }
            (char == '\n' || char == '\r') && !quoted -> {
                if (char == '\r' && index + 1 < csv.length && csv[index + 1] == '\n') index++
                row += cell.toString()
                cell.setLength(0)
                if (row.any(String::isNotEmpty)) rows += row
                row = mutableListOf()
            }
            else -> cell.append(char)
        }
        index++
    }
    if (cell.isNotEmpty() || row.isNotEmpty()) {
        row += cell.toString()
        if (row.any(String::isNotEmpty)) rows += row
    }
    return rows
}

private fun renderDiagnosticCsv(rows: List<List<String>>): String = buildString {
    rows.forEach { row -> appendLine(row.joinToString(";") { diagnosticCsvCell(it) }) }
}

private fun diagnosticCsvCell(value: String): String {
    if (';' !in value && '\n' !in value && '\r' !in value && '"' !in value) return value
    return "\"${value.replace("\"", "\"\"")}\""
}

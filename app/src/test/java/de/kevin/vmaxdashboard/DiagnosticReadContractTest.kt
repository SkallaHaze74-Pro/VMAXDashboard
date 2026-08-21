package de.kevin.vmaxdashboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticReadContractTest {
    @Test
    fun csvKeepsFullGattIdentityPropertiesEpochAndExactCallbackPayload() {
        val record = DiagnosticReadRecord(
            timestampMs = 1_786_800_000_123,
            serviceUuid = "da1a1500-d532-4285-be94-b07a3e11a098",
            characteristicUuid = "da1a1516-d532-4285-be94-b07a3e11a098",
            shortId = "1516",
            properties = "READ|NOTIFY",
            status = 0,
            length = 3,
            hex = "31-32-33",
            connectionEpoch = 7,
            measurementConnectionEpoch = 0,
            evidence = "SDK_CONFIRMED",
            meaning = "SerialNumbers",
            scanId = "scan-1786800000000-e7",
            propertiesRaw = 0x12,
            callbackReceived = true,
            recordKind = DiagnosticRecordKind.GATT_READ_CALLBACK,
            outcome = DiagnosticReadOutcome.CALLBACK_SUCCESS,
            payloadValid = true
        )

        val lines = buildDiagnosticReadCsv(listOf(record)).lines()

        assertEquals(
            listOf(
                "timestamp_ms", "scan_id", "record_kind", "outcome", "callback_received",
                "service_uuid", "characteristic_uuid", "short_id", "properties", "properties_raw",
                "status", "length", "hex", "payload_valid", "payload_sha256", "public_redaction",
                "connection_epoch", "measurement_connection_epoch", "rssi", "evidence", "meaning"
            ),
            lines[0].split(';')
        )
        assertEquals(lines[0].split(';').size, lines[1].split(';').size)
        assertTrue(lines[1].contains("da1a1500-d532-4285-be94-b07a3e11a098"))
        assertTrue(lines[1].contains("da1a1516-d532-4285-be94-b07a3e11a098"))
        assertTrue(lines[1].contains("READ|NOTIFY"))
        assertTrue(lines[1].contains("31-32-33"))
        assertTrue(lines[1].contains(";7;"))
        assertTrue(lines[1].contains(";7;0;"))
    }

    @Test
    fun everyCallbackKeepsExactBytesAndMarksWhetherTheyAreValid() {
        assertEquals("00-80-FF", diagnosticReadHex(0, byteArrayOf(0x00, 0x80.toByte(), 0xFF.toByte())))
        assertEquals("01-02", diagnosticReadHex(133, byteArrayOf(0x01, 0x02)))

        val failed = diagnosticReadDefaults(status = 133)
        assertTrue(failed.callbackReceived)
        assertEquals(DiagnosticReadOutcome.CALLBACK_ERROR, failed.outcome)
        assertFalse(failed.payloadValid)

        val disconnected = diagnosticReadDefaults(status = -1003)
        assertFalse(disconnected.callbackReceived)
        assertEquals(DiagnosticRecordKind.GATT_READ_EVENT, disconnected.recordKind)
        assertEquals(DiagnosticReadOutcome.CONNECTION_CLOSED, disconnected.outcome)
    }

    @Test
    fun completedMeasurementQueuesAvailableDeepReadFilesWithoutRequiringThem() {
        val core = REQUIRED_MEASUREMENT_FILES.toSet()
        assertEquals(REQUIRED_MEASUREMENT_FILES, measurementFilesToQueue(core))

        val withDeepRead = core + OPTIONAL_MEASUREMENT_FILES
        assertEquals(
            REQUIRED_MEASUREMENT_FILES + OPTIONAL_MEASUREMENT_FILES,
            measurementFilesToQueue(withDeepRead)
        )

        assertEquals(
            REQUIRED_MEASUREMENT_FILES,
            measurementFilesToQueue(core + DIAGNOSTIC_READ_CSV_FILE)
        )
        assertEquals(
            REQUIRED_MEASUREMENT_FILES,
            measurementFilesToQueue(core + setOf(DIAGNOSTIC_READ_CSV_FILE, DIAGNOSTIC_READ_SUMMARY_FILE))
        )
        assertTrue(measurementFilesToQueue(core - "Zusammenfassung.txt").isEmpty())
    }

    @Test
    fun durableExactStageAndPublicQueueAreEachAllOrNothing() {
        assertTrue(isCompleteDurableDiagnosticStage(REQUIRED_DURABLE_DIAGNOSTIC_STAGE_FILES))
        assertFalse(
            isCompleteDurableDiagnosticStage(
                REQUIRED_DURABLE_DIAGNOSTIC_STAGE_FILES - "local_read.csv"
            )
        )
        assertTrue(isCompleteStandaloneDiagnosticQueue(REQUIRED_STANDALONE_DIAGNOSTIC_FILES))
        assertFalse(
            isCompleteStandaloneDiagnosticQueue(
                REQUIRED_STANDALONE_DIAGNOSTIC_FILES - ".meta.json"
            )
        )
    }

    @Test
    fun measurementQueueRequiresAtomicKnownFilesAndExpectedHeaders() {
        val coreQueue = REQUIRED_MEASUREMENT_FILES.toSet() + setOf("manifest.json", ".meta.json")
        assertTrue(isCompleteMeasurementQueueFileSet(coreQueue))
        assertFalse(isCompleteMeasurementQueueFileSet(coreQueue - "Live_Telemetrie.csv"))
        assertFalse(isCompleteMeasurementQueueFileSet(coreQueue + DIAGNOSTIC_READ_CSV_FILE))
        assertTrue(
            isCompleteMeasurementQueueFileSet(coreQueue + OPTIONAL_MEASUREMENT_FILES)
        )
        assertTrue(
            hasExpectedMeasurementFileHeader(
                "BLE_Rohdaten.csv",
                "relative_ms;timestamp_ms;channel\n"
            )
        )
        assertFalse(hasExpectedMeasurementFileHeader("BLE_Rohdaten.csv", ""))
        assertFalse(hasExpectedMeasurementFileHeader("BLE_Rohdaten.csv", "broken"))
    }

    @Test
    fun scanLimitCountsOnlyUnprocessedMeasurementsWithoutACompleteQueue() {
        assertTrue(
            shouldQueueMeasurementCandidate(
                alreadyProcessed = false,
                completeQueueAlreadyExists = false
            )
        )
        assertFalse(
            shouldQueueMeasurementCandidate(
                alreadyProcessed = true,
                completeQueueAlreadyExists = false
            )
        )
        assertFalse(
            shouldQueueMeasurementCandidate(
                alreadyProcessed = false,
                completeQueueAlreadyExists = true
            )
        )
    }

    @Test
    fun publicCsvHashesIdentityAndFreeFormPayloadsButLocalCsvKeepsExactBytes() {
        val records = listOf(
            diagnosticReadDefaults(shortId = "1516", hex = "31-32-33", meaning = "SerialNumbers"),
            diagnosticReadDefaults(shortId = "1518", hex = "44-45-42-55-47", meaning = "DebugLog"),
            diagnosticReadDefaults(shortId = "2A00", hex = "4D-59-2D-53-43-4F-4F-54-45-52", meaning = "Generic name"),
            diagnosticReadDefaults(shortId = "9999", hex = "53-45-43-52-45-54", meaning = "Unknown text"),
            diagnosticReadDefaults(
                status = null,
                shortId = "ADV",
                hex = "42-54-36-33-38",
                meaning = "BLE advertisement • device=MY-SCOOTER",
                callbackReceived = false,
                recordKind = DiagnosticRecordKind.BLE_OBSERVATION,
                outcome = DiagnosticReadOutcome.ADVERTISEMENT_OBSERVED,
                payloadValid = true
            ),
            diagnosticReadDefaults(shortId = "1509", hex = "01-02", meaning = "Battery live")
        )
        val local = buildDiagnosticReadCsv(records, DiagnosticReadRepresentation.LOCAL_EXACT)
        val public = redactDiagnosticReadCsvForPublic(local)

        assertTrue(local.contains("31-32-33"))
        assertTrue(local.contains("44-45-42-55-47"))
        assertTrue(public.contains("01-02"))
        assertFalse(public.contains("31-32-33"))
        assertFalse(public.contains("44-45-42-55-47"))
        assertFalse(public.contains("4D-59-2D-53-43-4F-4F-54-45-52"))
        assertFalse(public.contains("53-45-43-52-45-54"))
        assertFalse(public.contains("MY-SCOOTER"))
        assertTrue(public.contains(diagnosticPayloadSha256("31-32-33")))
        assertTrue(public.contains("identity_or_free_form"))
    }

    @Test
    fun publicCsvFailsClosedForShortUtf8AndUtf16FreeFormValues() {
        val utf8 = diagnosticReadDefaults(
            shortId = "9998",
            hex = "C3-84-42",
            meaning = "Unknown UTF-8"
        )
        val utf16 = diagnosticReadDefaults(
            shortId = "9999",
            hex = "41-00-42-00",
            meaning = "Unknown UTF-16LE"
        )
        val shortAscii = diagnosticReadDefaults(
            shortId = "999A",
            hex = "41-42",
            meaning = "Unknown short text"
        )

        val public = buildDiagnosticReadCsv(
            listOf(utf8, utf16, shortAscii),
            DiagnosticReadRepresentation.PUBLIC_REDACTED
        )

        assertFalse(public.contains("C3-84-42"))
        assertFalse(public.contains("41-00-42-00"))
        assertFalse(public.contains(";41-42;"))
        assertTrue(public.contains(diagnosticPayloadSha256("C3-84-42")))
        assertTrue(public.contains(diagnosticPayloadSha256("41-00-42-00")))
        assertTrue(public.contains(diagnosticPayloadSha256("41-42")))
    }

    @Test
    fun publicRideRawCsvHashesSensitiveNotificationsButKeepsLocalInputUntouched() {
        val raw = buildRawTelemetryCsv(
            listOf(
                "1;1001;1516;Serial;3;1;–;31-32-33;NOTIFICATION;0;da1a1500;da1a1516;18",
                "2;1002;1509;Battery;4;1;–;01-02-03-04;NOTIFICATION;0;da1a1500;da1a1509;18",
                "3;1003;9999;Unknown;6;1;–;53-45-43-52-45-54;NOTIFICATION;0;da1a9900;da1a9999;18"
            )
        )

        val publicCsv = redactRawTelemetryCsvForPublic(raw)

        assertTrue(raw.contains("31-32-33"))
        assertTrue(raw.contains("53-45-43-52-45-54"))
        assertFalse(publicCsv.contains("31-32-33"))
        assertFalse(publicCsv.contains("53-45-43-52-45-54"))
        assertTrue(publicCsv.contains("01-02-03-04"))
        assertTrue(publicCsv.contains("payload_sha256"))
        assertTrue(publicCsv.contains("identity_or_free_form"))
    }

    @Test
    fun publicSummaryHashesDeviceIdentityWhileLocalSummaryKeepsIt() {
        val bundle = DiagnosticReadBundle(
            records = listOf(diagnosticReadDefaults()),
            deviceName = "MY-SCOOTER",
            scanStartedAt = 1,
            scanFinishedAt = 2,
            connectionEpoch = 7,
            scanId = "scan-1-e7-test"
        )

        val local = buildDiagnosticReadSummary(listOf(bundle))
        val public = buildDiagnosticReadSummary(
            listOf(bundle),
            DiagnosticReadRepresentation.PUBLIC_REDACTED
        )
        val redactedExisting = redactDiagnosticReadSummaryForPublic(local)

        assertTrue(local.contains("MY-SCOOTER"))
        assertFalse(public.contains("MY-SCOOTER"))
        assertFalse(redactedExisting.contains("MY-SCOOTER"))
        assertTrue(public.contains(diagnosticTextSha256("MY-SCOOTER")))
        assertTrue(redactedExisting.contains(diagnosticTextSha256("MY-SCOOTER")))
    }

    @Test
    fun publicManifestRedactsNestedIdentityValues() {
        val exact = """{
          "device":"MY-SCOOTER",
          "nested":{"serialNumber":"SERIAL-123"},
          "items":[{"bluetoothAddress":"AA:BB:CC:DD:EE:FF"}]
        }""".trimIndent()

        val public = redactDiagnosticReadManifestForPublic(exact)

        assertFalse(public.contains("MY-SCOOTER"))
        assertFalse(public.contains("SERIAL-123"))
        assertFalse(public.contains("AA:BB:CC:DD:EE:FF"))
        assertTrue(public.contains(diagnosticTextSha256("SERIAL-123")))
        assertTrue(public.contains("unsalted SHA-256 pseudonym"))
    }

    @Test
    fun stableScanIdentityAndFolderNameIncludeMillisecondsAndConnectionEpoch() {
        val first = DiagnosticReadBundle(
            records = emptyList(),
            deviceName = "BT638",
            scanStartedAt = 1_786_800_000_123,
            scanFinishedAt = 1_786_800_000_456,
            connectionEpoch = 7
        )
        val second = first.copy(scanStartedAt = 1_786_800_000_789, scanFinishedAt = 1_786_800_000_999)

        val sameTimestampAndEpoch = first.copy(scanId = diagnosticReadScanId(first.scanStartedAt, first.connectionEpoch))

        assertTrue(first.scanId.startsWith("scan-1786800000123-e7-"))
        assertFalse(first.scanId == sameTimestampAndEpoch.scanId)
        assertFalse(diagnosticReadFolderName(first) == diagnosticReadFolderName(second))
        assertTrue(diagnosticReadFolderName(first).contains("-123"))
        assertTrue(diagnosticReadFolderName(first).contains("e7"))
    }

    @Test
    fun delayedBundleKeepsPhysicalToMeasurementEpochMappingAndDropsPreStartRows() {
        val beforeStart = diagnosticReadDefaults().copy(timestampMs = 999L, connectionEpoch = 70L)
        val oldConnection = diagnosticReadDefaults().copy(timestampMs = 1_001L, connectionEpoch = 70L)
        val reconnected = diagnosticReadDefaults().copy(timestampMs = 1_002L, connectionEpoch = 71L)

        val linked = linkDiagnosticRecordsToMeasurement(
            records = listOf(beforeStart, oldConnection, reconnected),
            measurementStartedAt = 1_000L,
            measurementEpochByGattEpoch = mapOf(70L to 0, 71L to 1)
        )

        assertEquals(listOf(70L, 71L), linked.map(DiagnosticReadRecord::connectionEpoch))
        assertEquals(listOf(0, 1), linked.map(DiagnosticReadRecord::measurementConnectionEpoch))
    }

    @Test
    fun summarySeparatesAttemptsCallbacksSuccessAndPayloadCallbacks() {
        val records = listOf(
            diagnosticReadDefaults(status = 0, hex = "01"),
            diagnosticReadDefaults(status = 133, hex = "02"),
            diagnosticReadDefaults(status = -1002, hex = ""),
            diagnosticReadDefaults(
                status = null,
                callbackReceived = false,
                recordKind = DiagnosticRecordKind.BLE_OBSERVATION,
                outcome = DiagnosticReadOutcome.ADVERTISEMENT_OBSERVED,
                payloadValid = true,
                hex = "03",
                rssi = -55
            )
        )
        val counts = diagnosticReadCounts(records)

        assertEquals(3, counts.attempts)
        assertEquals(2, counts.callbacks)
        assertEquals(1, counts.successes)
        assertEquals(2, counts.payloadCallbacks)
        assertEquals(1, counts.observations)
    }

    private fun diagnosticReadDefaults(
        status: Int? = 0,
        shortId: String = "1509",
        hex: String = "01",
        meaning: String = "Battery",
        callbackReceived: Boolean = status != null && status >= 0,
        recordKind: DiagnosticRecordKind = if (callbackReceived) {
            DiagnosticRecordKind.GATT_READ_CALLBACK
        } else {
            DiagnosticRecordKind.GATT_READ_EVENT
        },
        outcome: DiagnosticReadOutcome = diagnosticReadOutcome(status, callbackReceived, recordKind),
        payloadValid: Boolean = callbackReceived && status == 0,
        rssi: Int? = null
    ) = DiagnosticReadRecord(
        timestampMs = 1,
        serviceUuid = "da1a1500-d532-4285-be94-b07a3e11a098",
        characteristicUuid = "da1a${shortId.lowercase()}-d532-4285-be94-b07a3e11a098",
        shortId = shortId,
        properties = if (recordKind == DiagnosticRecordKind.BLE_OBSERVATION) "" else "READ",
        status = status,
        length = if (hex.isBlank()) 0 else hex.split('-').size,
        hex = hex,
        connectionEpoch = 7,
        measurementConnectionEpoch = 0,
        evidence = "TEST",
        meaning = meaning,
        scanId = "scan-1-e7",
        callbackReceived = callbackReceived,
        recordKind = recordKind,
        outcome = outcome,
        payloadValid = payloadValid,
        rssi = rssi
    )
}

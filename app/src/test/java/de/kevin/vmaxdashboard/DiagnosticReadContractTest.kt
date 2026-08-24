package de.kevin.vmaxdashboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject

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
    fun publicCsvRedactsProtocolPrefixedIdentityTextFromDeepReadAndRawTelemetry() {
        val protocolPrefixedIdentity =
            "FF-54-45-53-54-2D-49-44-2D-31-32-33-34-35-36-37-38-00"
        val localDeepRead = buildDiagnosticReadCsv(
            listOf(
                diagnosticReadDefaults(
                    shortId = "1511",
                    hex = protocolPrefixedIdentity,
                    meaning = "Unknown READ field"
                ),
                diagnosticReadDefaults(
                    shortId = "1513",
                    hex = protocolPrefixedIdentity,
                    meaning = "Unknown READ field"
                ),
                diagnosticReadDefaults(
                    shortId = "9999",
                    hex = protocolPrefixedIdentity,
                    meaning = "Unknown protocol-framed field"
                )
            )
        )
        val publicDeepRead = redactDiagnosticReadCsvForPublic(localDeepRead)
        val localRaw = buildRawTelemetryCsv(
            listOf(
                "1;1001;1511;Unknown;20;1;–;$protocolPrefixedIdentity;NOTIFICATION;0;da1a1500;da1a1511;2",
                "2;1002;1513;Unknown;20;1;–;$protocolPrefixedIdentity;NOTIFICATION;0;da1a1500;da1a1513;2",
                "3;1003;9999;Unknown;20;1;–;$protocolPrefixedIdentity;NOTIFICATION;0;da1a9900;da1a9999;2"
            )
        )
        val publicRaw = redactRawTelemetryCsvForPublic(localRaw)

        assertTrue(localDeepRead.contains(protocolPrefixedIdentity))
        assertTrue(localRaw.contains(protocolPrefixedIdentity))
        assertFalse(publicDeepRead.contains(protocolPrefixedIdentity))
        assertFalse(publicRaw.contains(protocolPrefixedIdentity))
        assertTrue(publicDeepRead.contains(diagnosticPayloadSha256(protocolPrefixedIdentity)))
        assertTrue(publicRaw.contains(diagnosticPayloadSha256(protocolPrefixedIdentity)))
        assertTrue(publicDeepRead.contains("identity_or_free_form"))
        assertTrue(publicRaw.contains("identity_or_free_form"))
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
        val utf16Be = diagnosticReadDefaults(
            shortId = "9997",
            hex = "00-41-00-42",
            meaning = "Unknown UTF-16BE"
        )
        val utf16Bom = diagnosticReadDefaults(
            shortId = "9996",
            hex = "FF-FE-41-00-42-00",
            meaning = "Unknown UTF-16LE with BOM"
        )
        val shortAscii = diagnosticReadDefaults(
            shortId = "999A",
            hex = "41-42",
            meaning = "Unknown short text"
        )
        val binaryZeros = diagnosticReadDefaults(
            shortId = "1509",
            hex = "00-00-00-00",
            meaning = "Battery live"
        )

        val public = buildDiagnosticReadCsv(
            listOf(utf8, utf16, utf16Be, utf16Bom, shortAscii, binaryZeros),
            DiagnosticReadRepresentation.PUBLIC_REDACTED
        )

        assertFalse(public.contains("C3-84-42"))
        assertFalse(public.contains("41-00-42-00"))
        assertFalse(public.contains("00-41-00-42"))
        assertFalse(public.contains("FF-FE-41-00-42-00"))
        assertFalse(public.contains(";41-42;"))
        assertTrue(public.contains("00-00-00-00"))
        assertTrue(public.contains(diagnosticPayloadSha256("C3-84-42")))
        assertTrue(public.contains(diagnosticPayloadSha256("41-00-42-00")))
        assertTrue(public.contains(diagnosticPayloadSha256("41-42")))
    }

    @Test
    fun publicCsvFindsBoundedUtf8AndUtf16TextFramesWithoutHidingBinaryTelemetry() {
        val framedText = listOf(
            "FE-54-45-53-54-2D-31-32-33-81",
            "01-C3-84-42-43-31-32-33-00",
            "7F-41-00-42-00-43-00-44-00-81",
            "81-00-41-00-42-00-43-00-44-A5",
            "7F-FF-FE-60-4F-7D-59-4C-75-BA-4E-81"
        )
        val binaryTelemetry = listOf(
            "00-00-FF-FF-45-C3-B4-00-00-00-00",
            "00-00-1B-BB-00-02-27-3B-00-00-02-C5-00-00-00-00"
        )
        val records = framedText.mapIndexed { index, hex ->
            diagnosticReadDefaults(
                shortId = "99${index.toString().padStart(2, '0')}",
                hex = hex,
                meaning = "Unknown framed value"
            )
        } + binaryTelemetry.mapIndexed { index, hex ->
            diagnosticReadDefaults(
                shortId = if (index == 0) "1509" else "1506",
                hex = hex,
                meaning = "Binary live telemetry"
            )
        }

        val public = buildDiagnosticReadCsv(
            records,
            DiagnosticReadRepresentation.PUBLIC_REDACTED
        )

        framedText.forEach { assertFalse(public.contains(it)) }
        binaryTelemetry.forEach { assertTrue(public.contains(it)) }
    }

    @Test
    fun publicCsvCanonicalizesFullUuidIdentityChannelsBeforeRedaction() {
        val fullUuid = "00002a00-0000-1000-8000-00805f9b34fb"
        val binaryIdentity = "DE-AD-BE-EF"
        val localDeepRead = buildDiagnosticReadCsv(
            listOf(
                diagnosticReadDefaults(shortId = "1509", hex = binaryIdentity).copy(
                    shortId = fullUuid,
                    characteristicUuid = fullUuid,
                    meaning = "Unknown legacy field"
                )
            )
        )
        val localRaw = buildRawTelemetryCsv(
            listOf(
                "1;1001;$fullUuid;Unknown;4;1;–;$binaryIdentity;NOTIFICATION;0;1800;$fullUuid;2"
            )
        )

        val publicDeepRead = redactDiagnosticReadCsvForPublic(localDeepRead)
        val publicRaw = redactRawTelemetryCsvForPublic(localRaw)

        assertFalse(publicDeepRead.contains(binaryIdentity))
        assertFalse(publicRaw.contains(binaryIdentity))
        assertTrue(publicDeepRead.contains(diagnosticPayloadSha256(binaryIdentity)))
        assertTrue(publicRaw.contains(diagnosticPayloadSha256(binaryIdentity)))
    }

    @Test
    fun uploadBoundaryReRedactsStaleQueuesAndIsIdempotent() {
        val identity = "FF-54-45-53-54-2D-49-44-2D-31-32-33-34-35-36-37-38-00"
        val staleRaw = buildRawTelemetryCsv(
            listOf(
                "1;1001;1511;Unknown;19;1;–;$identity;NOTIFICATION;0;da1a1500;da1a1511;2"
            )
        )
        val staleDeepRead = buildDiagnosticReadCsv(
            listOf(diagnosticReadDefaults(shortId = "1511", hex = identity))
        )

        val publicRaw = publicGitHubUploadBytes("BLE_Rohdaten.csv", staleRaw.toByteArray())
        val publicDeepRead = publicGitHubUploadBytes(
            DIAGNOSTIC_READ_CSV_FILE,
            staleDeepRead.toByteArray()
        )

        assertTrue(staleRaw.contains(identity))
        assertTrue(staleDeepRead.contains(identity))
        assertFalse(String(publicRaw, Charsets.UTF_8).contains(identity))
        assertFalse(String(publicDeepRead, Charsets.UTF_8).contains(identity))
        assertTrue(String(publicRaw, Charsets.UTF_8).contains(diagnosticPayloadSha256(identity)))
        assertTrue(String(publicDeepRead, Charsets.UTF_8).contains(diagnosticPayloadSha256(identity)))
        assertEquals(
            String(publicRaw, Charsets.UTF_8),
            String(publicGitHubUploadBytes("BLE_Rohdaten.csv", publicRaw), Charsets.UTF_8)
        )
        assertEquals(
            String(publicDeepRead, Charsets.UTF_8),
            String(
                publicGitHubUploadBytes(DIAGNOSTIC_READ_CSV_FILE, publicDeepRead),
                Charsets.UTF_8
            )
        )
    }

    @Test
    fun uploadBoundaryRejectsMalformedUtf8BeforePublicRedaction() {
        val validPrefix = "channel;hex\n1516;".toByteArray(Charsets.UTF_8)
        val malformedUtf8 = validPrefix + byteArrayOf(0xC3.toByte(), 0x28) +
            "\n".toByteArray(Charsets.UTF_8)

        assertThrows(IllegalArgumentException::class.java) {
            publicGitHubUploadBytes("BLE_Rohdaten.csv", malformedUtf8)
        }
        assertThrows(IllegalArgumentException::class.java) {
            publicGitHubUploadBytes(DIAGNOSTIC_READ_CSV_FILE, malformedUtf8)
        }
    }

    @Test
    fun publicCsvFailsClosedForAmbiguousOrUnidentifiableSchemas() {
        val duplicateRaw = "channel;hex;hex\n1516;31-32-33;31-32-33\n"
        val overwideRaw = "channel;hex\n1516;31-32-33;TRAILING\n"
        val unidentifiedDeepRead = "timestamp_ms;hex;meaning\n1;31-32-33;Serial\n"
        val unterminatedRaw = "channel;hex\n1516;\"31-32-33\n"

        listOf(
            { redactRawTelemetryCsvForPublic(duplicateRaw) },
            { redactRawTelemetryCsvForPublic(overwideRaw) },
            { redactDiagnosticReadCsvForPublic(unidentifiedDeepRead) },
            { redactRawTelemetryCsvForPublic(unterminatedRaw) }
        ).forEach { redaction ->
            assertThrows(IllegalArgumentException::class.java) { redaction() }
        }
    }

    @Test
    fun publicLearningAndManifestIdentityRedactionIsIdempotent() {
        val identity = "PRIVATE-TEST-SCOOTER"
        val learning = """{
          "format":"VMAX_LEARNING_PROFILE_V1",
          "candidates":[{
            "model":"$identity",
            "sessionKeys":["$identity@12345"]
          }]
        }""".trimIndent()
        val manifest = """{"device":"$identity","serial":"SERIAL-TEST-123"}"""
        val summary = "VMAX BT638 Deep READ\nGerät: $identity\nSerial: SERIAL-TEST-123\n"

        val publicLearning = publicGitHubUploadBytes("Lernprofil.json", learning.toByteArray())
        val publicManifest = publicGitHubUploadBytes("manifest.json", manifest.toByteArray())
        val publicSummary = publicGitHubUploadBytes(DIAGNOSTIC_READ_SUMMARY_FILE, summary.toByteArray())

        assertFalse(String(publicLearning, Charsets.UTF_8).contains(identity))
        assertFalse(String(publicManifest, Charsets.UTF_8).contains(identity))
        assertFalse(String(publicSummary, Charsets.UTF_8).contains(identity))
        assertTrue(String(publicLearning, Charsets.UTF_8).contains(diagnosticTextSha256(identity)))
        assertEquals(
            String(publicLearning, Charsets.UTF_8),
            String(publicGitHubUploadBytes("Lernprofil.json", publicLearning), Charsets.UTF_8)
        )
        assertEquals(
            String(publicManifest, Charsets.UTF_8),
            String(publicGitHubUploadBytes("manifest.json", publicManifest), Charsets.UTF_8)
        )
        assertEquals(
            String(publicSummary, Charsets.UTF_8),
            String(
                publicGitHubUploadBytes(DIAGNOSTIC_READ_SUMMARY_FILE, publicSummary),
                Charsets.UTF_8
            )
        )
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

    @Test
    fun summaryDistinguishesDiscoveredReadableAndCallbackCharacteristics() {
        val callback = diagnosticReadDefaults(shortId = "1509")
        val readableWithoutCallback = diagnosticReadDefaults(
            status = -1001,
            shortId = "160C",
            hex = "",
            callbackReceived = false,
            recordKind = DiagnosticRecordKind.GATT_READ_EVENT,
            outcome = DiagnosticReadOutcome.READ_START_FAILED,
            payloadValid = false
        ).copy(properties = "READ|NOTIFY", propertiesRaw = 0x12)
        val discoveredNotifyOnly = diagnosticReadDefaults(
            status = null,
            shortId = "1514",
            hex = "",
            callbackReceived = false,
            recordKind = DiagnosticRecordKind.CONNECTION_EVENT,
            outcome = DiagnosticReadOutcome.OTHER,
            payloadValid = false
        ).copy(properties = "NOTIFY", propertiesRaw = 0x10)
        val records = listOf(callback, callback.copy(timestampMs = 2), readableWithoutCallback, discoveredNotifyOnly)
        val bundle = DiagnosticReadBundle(
            records = records,
            deviceName = "BT638",
            scanStartedAt = 1,
            scanFinishedAt = 2,
            connectionEpoch = 7,
            scanId = "scan-1-e7-counts"
        )

        val summary = buildDiagnosticReadSummary(listOf(bundle))

        assertTrue(summary.contains("Characteristics_Discovered: not_archived"))
        assertTrue(summary.contains("Characteristics_Discovered_Recorded_Lower_Bound: 3"))
        assertTrue(summary.contains("Characteristics_Readable_Recorded_Lower_Bound: 2"))
        assertTrue(summary.contains("Characteristics_Callbacks: 1"))
        assertTrue(summary.contains("Characteristics_Inventory_Complete: false"))
        assertFalse(summary.lineSequence().any { it.startsWith("Characteristics: ") })
    }

    @Test
    fun diagnosticManifestMakesMissingGattInventoryExplicitWithoutLosingRecordedCounts() {
        val callback = diagnosticReadDefaults(shortId = "1509")
        val readableWithoutCallback = diagnosticReadDefaults(
            status = -1001,
            shortId = "160C",
            hex = "",
            callbackReceived = false,
            recordKind = DiagnosticRecordKind.GATT_READ_EVENT,
            outcome = DiagnosticReadOutcome.READ_START_FAILED,
            payloadValid = false
        ).copy(properties = "READ|NOTIFY", propertiesRaw = 0x12)
        val discoveredNotifyOnly = diagnosticReadDefaults(
            status = null,
            shortId = "1514",
            hex = "",
            callbackReceived = false,
            recordKind = DiagnosticRecordKind.CONNECTION_EVENT,
            outcome = DiagnosticReadOutcome.OTHER,
            payloadValid = false
        ).copy(properties = "NOTIFY", propertiesRaw = 0x10)
        val csv = buildDiagnosticReadCsv(
            listOf(callback, callback.copy(timestampMs = 2), readableWithoutCallback, discoveredNotifyOnly),
            DiagnosticReadRepresentation.PUBLIC_REDACTED
        )

        val manifest = JSONObject(
            enrichDiagnosticReadManifestWithCsv(
                """{"schema":"vmax-bt638-deep-read-v3","read_callbacks":2}""",
                csv
            )
        )

        assertTrue(manifest.isNull("discovered_characteristics"))
        assertEquals(3, manifest.getInt("discovered_characteristics_recorded_lower_bound"))
        assertEquals(2, manifest.getInt("readable_characteristics_recorded_lower_bound"))
        assertEquals(1, manifest.getInt("callback_characteristics"))
        assertFalse(manifest.getBoolean("characteristic_inventory_complete"))
        assertTrue(manifest.getString("characteristic_count_scope").contains("not persisted"))
    }

    @Test
    fun legacyPublicSummaryIsUpgradedFromItsSiblingCsvBeforeUpload() {
        val csv = buildDiagnosticReadCsv(
            listOf(
                diagnosticReadDefaults(shortId = "1509"),
                diagnosticReadDefaults(shortId = "160C").copy(
                    callbackReceived = false,
                    recordKind = DiagnosticRecordKind.GATT_READ_EVENT,
                    outcome = DiagnosticReadOutcome.READ_TIMEOUT,
                    payloadValid = false,
                    status = -1002,
                    length = 0,
                    hex = ""
                )
            ),
            DiagnosticReadRepresentation.PUBLIC_REDACTED
        )
        val legacy = """
            VMAX BT638 Deep READ
            READ_Callbacks: 1
            Characteristics: 2
            Modus: STRICT_READ_ONLY
        """.trimIndent() + "\n"

        val upgraded = enrichDiagnosticReadSummaryWithCsv(legacy, csv)

        assertFalse(upgraded.lineSequence().any { it.startsWith("Characteristics: ") })
        assertTrue(upgraded.contains("Characteristics_Discovered: not_archived"))
        assertTrue(upgraded.contains("Characteristics_Discovered_Recorded_Lower_Bound: 2"))
        assertTrue(upgraded.contains("Characteristics_Readable_Recorded_Lower_Bound: 2"))
        assertTrue(upgraded.contains("Characteristics_Callbacks: 1"))
        assertTrue(upgraded.endsWith("\n"))
        assertEquals(legacy, enrichDiagnosticReadSummaryWithCsv(legacy, "unsupported legacy csv"))

        val uploadBytes = publicGitHubUploadBytesWithDiagnosticCsv(
            DIAGNOSTIC_READ_SUMMARY_FILE,
            legacy.toByteArray(),
            csv.toByteArray()
        )
        val uploadedSummary = String(uploadBytes, Charsets.UTF_8)
        assertFalse(uploadedSummary.lineSequence().any { it.startsWith("Characteristics: ") })
        assertTrue(uploadedSummary.contains("Characteristics_Callbacks: 1"))
    }

    @Test
    fun uploadCompatibilityUpgradeDoesNotMistakeMeasurementManifestForDiagnosticManifest() {
        val csv = buildDiagnosticReadCsv(listOf(diagnosticReadDefaults()))
        val measurementManifest = """{"schema":"vmax-github-telemetry-v1"}"""
        val standaloneDiagnosticManifest = """{"schema":"vmax-bt638-deep-read-v3"}"""

        val measurementUpload = JSONObject(
            String(
                publicGitHubUploadBytesWithDiagnosticCsv(
                    "manifest.json",
                    measurementManifest.toByteArray(),
                    csv.toByteArray()
                ),
                Charsets.UTF_8
            )
        )
        val standaloneUpload = JSONObject(
            String(
                publicGitHubUploadBytesWithDiagnosticCsv(
                    "manifest.json",
                    standaloneDiagnosticManifest.toByteArray(),
                    csv.toByteArray(),
                    diagnosticManifestFileName = "manifest.json"
                ),
                Charsets.UTF_8
            )
        )

        assertFalse(measurementUpload.has("callback_characteristics"))
        assertEquals(1, standaloneUpload.getInt("callback_characteristics"))
        assertFalse(standaloneUpload.getBoolean("characteristic_inventory_complete"))
    }

    @Test
    fun measurementSummarySeparatesArchiveSuccessFromCompletedScans() {
        assertEquals(
            "nicht vorhanden",
            measurementDeepReadExportStatus(totalScans = 0, completedScans = 0, exportSucceeded = true)
        )
        assertEquals(
            "vollständig archiviert; Scans 1/2 abgeschlossen",
            measurementDeepReadExportStatus(totalScans = 2, completedScans = 1, exportSucceeded = true)
        )
        assertEquals(
            "fehlgeschlagen (Fahrdaten separat gesichert); Scans 1/2 abgeschlossen",
            measurementDeepReadExportStatus(totalScans = 2, completedScans = 1, exportSucceeded = false)
        )
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

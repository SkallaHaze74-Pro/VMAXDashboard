package de.kevin.vmaxdashboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BleDiagnosticLifecyclePolicyTest {
    @Test
    fun scanStartIsIdempotentWhileScanningConnectingOrConnected() {
        assertTrue(shouldStartBleScan(scanning = false, connected = false, connectionAllocated = false))
        assertFalse(shouldStartBleScan(scanning = true, connected = false, connectionAllocated = false))
        assertFalse(shouldStartBleScan(scanning = false, connected = false, connectionAllocated = true))
        assertFalse(shouldStartBleScan(scanning = false, connected = true, connectionAllocated = true))
    }

    @Test
    fun lateScanResultAfterDisconnectCannotReconnect() {
        assertTrue(shouldAcceptBleScanResult(scanning = true, activeScanIntent = 7L, callbackScanIntent = 7L))
        assertFalse(shouldAcceptBleScanResult(scanning = false, activeScanIntent = null, callbackScanIntent = 7L))
        assertFalse(shouldAcceptBleScanResult(scanning = true, activeScanIntent = 8L, callbackScanIntent = 7L))
    }

    @Test
    fun quarantinedNotificationsStayInRawExportButNeverReachAnalysis() {
        val accepted = "10;1000;1505;Fahrdaten;18;1;0;00-01;NOTIFICATION;0"
        val quarantined =
            "20;1010;1505;Fahrdaten;18;2;–;41-42;NOTIFICATION_QUARANTINED;0"
        val diagnostic =
            "30;1020;1516;Diagnose;8;3;–;41-42;NOTIFICATION_DIAGNOSTIC;0"

        assertEquals(
            listOf(accepted),
            rawTelemetryRowsForAnalysis(listOf(accepted, quarantined, diagnostic))
        )
    }

    @Test
    fun knownBt638SubscriptionsRunBeforeOtherDiscoveredNotifyServices() {
        assertEquals(
            NotificationSubscriptionPhase.KNOWN_BT638,
            notificationSubscriptionPhase("da1a1500-d532-4285-be94-b07a3e11a098")
        )
        assertEquals(
            NotificationSubscriptionPhase.KNOWN_BT638,
            notificationSubscriptionPhase("da1a1600-d532-4285-be94-b07a3e11a098")
        )
        assertEquals(
            NotificationSubscriptionPhase.REMAINING,
            notificationSubscriptionPhase("da1a1e00-d532-4285-be94-b07a3e11a098")
        )
        assertTrue(
            notificationSubscriptionPriority(
                "da1a1500-d532-4285-be94-b07a3e11a098",
                "da1a1509-d532-4285-be94-b07a3e11a098"
            ) < notificationSubscriptionPriority(
                "da1a1600-d532-4285-be94-b07a3e11a098",
                "da1a160c-d532-4285-be94-b07a3e11a098"
            )
        )
    }

    @Test
    fun fullServiceAndCharacteristicIdentityPreventsCrossServiceDeduplication() {
        val first = notificationCharacteristicKey(
            "da1a1500-d532-4285-be94-b07a3e11a098",
            "00002a00-0000-1000-8000-00805f9b34fb"
        )
        val second = notificationCharacteristicKey(
            "da1a1e00-d532-4285-be94-b07a3e11a098",
            "00002a00-0000-1000-8000-00805f9b34fb"
        )

        assertTrue(first != second)
    }

    @Test
    fun timedOutEpochRequiresReconnectBeforeAnotherDiagnosticRead() {
        assertFalse(isDiagnosticReadAllowed(connectionEpoch = 7L, reconnectRequiredEpoch = 7L))
        assertTrue(isDiagnosticReadAllowed(connectionEpoch = 8L, reconnectRequiredEpoch = 7L))
        assertTrue(isDiagnosticReadAllowed(connectionEpoch = 7L, reconnectRequiredEpoch = null))
    }

    @Test
    fun advertisementWithoutConnectionCallbackExpiresOnlyItsConnectingEpoch() {
        val connecting = GattConnectionDeadline(11L, GattConnectionStage.CONNECTING)

        assertTrue(matchesGattConnectionDeadline(connecting, 11L, GattConnectionStage.CONNECTING))
        assertFalse(matchesGattConnectionDeadline(connecting, 12L, GattConnectionStage.CONNECTING))
        assertFalse(
            matchesGattConnectionDeadline(connecting, 11L, GattConnectionStage.DISCOVERING_SERVICES)
        )
    }

    @Test
    fun connectedWithoutServicesCallbackUsesSeparateDiscoveryDeadline() {
        val discovery = GattConnectionDeadline(21L, GattConnectionStage.DISCOVERING_SERVICES)

        assertTrue(
            matchesGattConnectionDeadline(discovery, 21L, GattConnectionStage.DISCOVERING_SERVICES)
        )
        assertFalse(matchesGattConnectionDeadline(discovery, 21L, GattConnectionStage.CONNECTING))
    }

    @Test
    fun connectedCallbackIsAcceptedOnlyOnceForExactConnectingEpochAndSuccess() {
        val connecting = GattConnectionDeadline(25L, GattConnectionStage.CONNECTING)

        assertEquals(
            GattConnectedCallbackDisposition.ACCEPT,
            gattConnectedCallbackDisposition(connecting, 25L, gattSuccess = true)
        )
        assertEquals(
            GattConnectedCallbackDisposition.FAIL_GATT_STATUS,
            gattConnectedCallbackDisposition(connecting, 25L, gattSuccess = false)
        )
        assertEquals(
            GattConnectedCallbackDisposition.IGNORE_STALE_OR_DUPLICATE,
            gattConnectedCallbackDisposition(null, 25L, gattSuccess = true)
        )
        assertEquals(
            GattConnectedCallbackDisposition.IGNORE_STALE_OR_DUPLICATE,
            gattConnectedCallbackDisposition(
                GattConnectionDeadline(25L, GattConnectionStage.DISCOVERING_SERVICES),
                25L,
                gattSuccess = true
            )
        )
        assertEquals(
            GattConnectedCallbackDisposition.IGNORE_STALE_OR_DUPLICATE,
            gattConnectedCallbackDisposition(connecting, 26L, gattSuccess = true)
        )
    }

    @Test
    fun cachedServicesCannotBypassInFlightDiscoveryAndDuplicateCallbackIsIgnored() {
        val discovery = GattConnectionDeadline(31L, GattConnectionStage.DISCOVERING_SERVICES)

        assertEquals(
            GattServicesCallbackDisposition.ACCEPT,
            gattServicesCallbackDisposition(discovery, 31L, gattSuccess = true, serviceCount = 4)
        )
        assertEquals(
            GattServicesCallbackDisposition.IGNORE_STALE_OR_DUPLICATE,
            gattServicesCallbackDisposition(null, 31L, gattSuccess = true, serviceCount = 4)
        )
        assertEquals(
            GattServicesCallbackDisposition.IGNORE_STALE_OR_DUPLICATE,
            gattServicesCallbackDisposition(discovery, 32L, gattSuccess = true, serviceCount = 4)
        )
    }

    @Test
    fun successfulDiscoveryWithEmptyInventoryFailsClosed() {
        val discovery = GattConnectionDeadline(41L, GattConnectionStage.DISCOVERING_SERVICES)

        assertEquals(
            GattServicesCallbackDisposition.FAIL_EMPTY_SERVICES,
            gattServicesCallbackDisposition(discovery, 41L, gattSuccess = true, serviceCount = 0)
        )
        assertEquals(
            GattServicesCallbackDisposition.FAIL_GATT_STATUS,
            gattServicesCallbackDisposition(discovery, 41L, gattSuccess = false, serviceCount = 3)
        )
    }

    @Test
    fun stoppingDuringPlatformReadPoisonsEpochBeforeAnyNextGattOperation() {
        assertTrue(
            shouldPoisonDiagnosticEpochOnFinalize(
                hasActiveSession = true,
                hasInFlightPlatformRead = true
            )
        )
        assertFalse(shouldPoisonDiagnosticEpochOnFinalize(true, false))
        assertFalse(shouldPoisonDiagnosticEpochOnFinalize(false, true))
    }

    @Test
    fun writeWithResponseTimeoutRequiresCallbackEvenIfControllerDataChanged() {
        assertTrue(
            shouldForceCloseWriteEpochOnTimeout(
                writeCallbackExpected = true,
                writeCallbackReceived = false
            )
        )
        assertFalse(shouldForceCloseWriteEpochOnTimeout(true, true))
        assertFalse(
            shouldForceCloseWriteEpochOnTimeout(
                writeCallbackExpected = false,
                writeCallbackReceived = false
            )
        )
    }

    @Test
    fun onlyExactBt638Da1aPairsCanFeedLiveTelemetry() {
        val service1500 = "da1a1500-d532-4285-be94-b07a3e11a098"
        val characteristic1509 = "da1a1509-d532-4285-be94-b07a3e11a098"

        assertTrue(isBt638LiveNotificationRoute(service1500, characteristic1509))
        assertFalse(
            isBt638LiveNotificationRoute(
                "da1a1600-d532-4285-be94-b07a3e11a098",
                characteristic1509
            )
        )
        assertFalse(
            isBt638LiveNotificationRoute(
                service1500,
                "abcd1509-1111-2222-3333-444455556666"
            )
        )
        assertFalse(
            isBt638LiveNotificationRoute(
                service1500,
                "da1a1516-d532-4285-be94-b07a3e11a098"
            )
        )
    }

    @Test
    fun capabilityLabelsRequireExactGattFamiliesNotMatchingShortText() {
        val exact = VmaxSdkCapabilityCatalog.classify(
            "da1a1500-d532-4285-be94-b07a3e11a098",
            "da1a1509-d532-4285-be94-b07a3e11a098"
        )
        val copiedShortId = VmaxSdkCapabilityCatalog.classify(
            "abcd1500-1111-2222-3333-444455556666",
            "abcd1509-1111-2222-3333-444455556666"
        )
        val exactStandard = VmaxSdkCapabilityCatalog.classify(
            "0000180a-0000-1000-8000-00805f9b34fb",
            "00002a25-0000-1000-8000-00805f9b34fb"
        )

        assertEquals(CapabilityEvidence.BT638_CONFIRMED, exact.evidence)
        assertEquals(CapabilityEvidence.UNKNOWN, copiedShortId.evidence)
        assertEquals(CapabilityEvidence.BLUETOOTH_STANDARD, exactStandard.evidence)
    }

    @Test
    fun shortPowerWindowReadsBatteryStateBeforeIdentityAndDebugBlocks() {
        assertTrue(diagnosticReadPriority("1509") < diagnosticReadPriority("1516"))
        assertTrue(diagnosticReadPriority("150C") < diagnosticReadPriority("1518"))
        assertTrue(diagnosticReadPriority("1502") < diagnosticReadPriority("160C"))
    }

    @Test
    fun advertisementEvidenceKeepsExactBytesTimestampRssiAndNoAddress() {
        val record = diagnosticAdvertisementRecord(
            timestampMs = 1_786_800_123_456L,
            connectionEpoch = 9L,
            scanId = "scan-charge-power",
            rssi = -61,
            deviceName = "BT638",
            payload = byteArrayOf(0x02, 0x01, 0x06, 0xFF.toByte())
        )

        assertEquals(1_786_800_123_456L, record.timestampMs)
        assertEquals("02-01-06-FF", record.hex)
        assertEquals(-61, record.rssi)
        assertEquals(DiagnosticRecordKind.BLE_OBSERVATION, record.recordKind)
        assertEquals(DiagnosticReadOutcome.ADVERTISEMENT_OBSERVED, record.outcome)
        assertTrue(record.payloadValid)
        assertTrue(record.meaning.contains("device=BT638"))
        assertFalse(record.meaning.contains(":")) // no MAC-address-shaped metadata
    }

    @Test
    fun advertisementMetadataWithoutScanRecordIsNotCountedAsPayload() {
        val record = diagnosticAdvertisementRecord(
            timestampMs = 1_786_800_123_999L,
            connectionEpoch = 10L,
            scanId = "scan-no-payload",
            rssi = -70,
            deviceName = "BT638",
            payload = byteArrayOf()
        )

        assertEquals("", record.hex)
        assertFalse(record.payloadValid)
        assertEquals(0, diagnosticReadCounts(listOf(record)).observationPayloads)
        assertEquals(1, diagnosticReadCounts(listOf(record)).observations)
    }
}

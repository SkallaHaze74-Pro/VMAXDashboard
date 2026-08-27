package de.kevin.vmaxdashboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class GattReadScannerCheckpointTest {
    @Test
    fun noActiveAttemptHasNoCheckpointBundle() {
        assertNull(
            diagnosticReadCheckpointBundle(
                records = emptyList(),
                deviceName = "BT638",
                scanStartedAt = 0L,
                checkpointAt = 200L,
                connectionEpoch = null,
                scanId = ""
            )
        )
    }

    @Test
    fun checkpointIsAnImmutablePartialCopyOfTheCurrentAttempt() {
        val sourceRecord = record(scanId = "stale-id")
        val mutableSource = mutableListOf(sourceRecord)

        val checkpoint = diagnosticReadCheckpointBundle(
            records = mutableSource,
            deviceName = "BT638",
            scanStartedAt = 100L,
            checkpointAt = 200L,
            connectionEpoch = 7L,
            scanId = "scan-100-e7-stable"
        )!!
        mutableSource.clear()

        assertEquals("scan-100-e7-stable", checkpoint.scanId)
        assertEquals(7L, checkpoint.connectionEpoch)
        assertEquals(100L, checkpoint.scanStartedAt)
        assertEquals(200L, checkpoint.scanFinishedAt)
        assertFalse(checkpoint.completed)
        assertSame(DiagnosticReadOutcome.SCAN_PARTIAL, checkpoint.completionOutcome)
        assertEquals(1, checkpoint.records.size)
        assertEquals("scan-100-e7-stable", checkpoint.records.single().scanId)
        assertSame(DiagnosticReadOutcome.CALLBACK_SUCCESS, checkpoint.records.single().outcome)
        assertNotSame(sourceRecord, checkpoint.records.single())
        assertEquals("stale-id", sourceRecord.scanId)
    }

    @Test
    fun laterCompletedBundleCanBeDeduplicatedByTheSameScanId() {
        val checkpoint = diagnosticReadCheckpointBundle(
            records = listOf(record(scanId = "scan-100-e7-stable")),
            deviceName = "BT638",
            scanStartedAt = 100L,
            checkpointAt = 150L,
            connectionEpoch = 7L,
            scanId = "scan-100-e7-stable"
        )!!
        val completed = checkpoint.copy(
            scanFinishedAt = 300L,
            completed = true,
            completionOutcome = DiagnosticReadOutcome.SCAN_COMPLETED
        )

        assertEquals(checkpoint.scanId, completed.scanId)
        assertEquals(1, listOf(checkpoint, completed).distinctBy { it.scanId }.size)
    }

    private fun record(scanId: String) = DiagnosticReadRecord(
        timestampMs = 125L,
        serviceUuid = "da1a1500-d532-4285-be94-b07a3e11a098",
        characteristicUuid = "da1a1509-d532-4285-be94-b07a3e11a098",
        shortId = "1509",
        properties = "READ",
        status = 0,
        length = 1,
        hex = "64",
        connectionEpoch = 7L,
        measurementConnectionEpoch = null,
        evidence = CapabilityEvidence.BT638_CONFIRMED.label,
        meaning = "Battery live state",
        scanId = scanId,
        callbackReceived = true,
        recordKind = DiagnosticRecordKind.GATT_READ_CALLBACK,
        outcome = DiagnosticReadOutcome.CALLBACK_SUCCESS,
        payloadValid = true
    )
}

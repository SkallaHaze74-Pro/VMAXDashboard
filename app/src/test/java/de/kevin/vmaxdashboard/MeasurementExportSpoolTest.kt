package de.kevin.vmaxdashboard

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MeasurementExportSpoolTest {
    @Test
    fun pendingSnapshotSurvivesANewSpoolInstanceUntilConfirmedExport() {
        val root = Files.createTempDirectory("vmax-measurement-spool").toFile()
        try {
            val pending = samplePendingExport()
            MeasurementExportSpool(root).stage(pending)

            val afterProcessRestart = MeasurementExportSpool(root)
            assertEquals(listOf(pending), afterProcessRestart.loadPending())
            assertTrue(afterProcessRestart.contains(pending.id))

            afterProcessRestart.removeAfterConfirmedExport(pending.id)
            assertFalse(MeasurementExportSpool(root).contains(pending.id))
            assertTrue(MeasurementExportSpool(root).loadPending().isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun failedExportLeavesTheExactSnapshotAvailableForRetry() {
        val root = Files.createTempDirectory("vmax-measurement-retry").toFile()
        try {
            val pending = samplePendingExport()
            val spool = MeasurementExportSpool(root)
            spool.stage(pending)

            // No success acknowledgement: a fresh manager must see every row.
            val restored = MeasurementExportSpool(root).loadPending().single()
            assertEquals(pending, restored)
            assertEquals(listOf("raw-before", "raw-after"), restored.snapshot.rawRows)
            assertEquals(listOf("0;1000;START", "1000;2000;STOP"), restored.snapshot.markerRows)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun successfulRideFilesDoNotAcknowledgeAFailedLinkedDiagnosticExport() {
        val root = Files.createTempDirectory("vmax-measurement-component-retry").toFile()
        try {
            val pending = samplePendingExport()
            val spool = MeasurementExportSpool(root)
            spool.stage(pending)

            val complete = isMeasurementExportComplete(
                coreExportSucceeded = true,
                diagnosticBundleCount = pending.snapshot.diagnosticReadBundles.size,
                diagnosticExportSucceeded = false
            )
            if (complete) spool.removeAfterConfirmedExport(pending.id)

            assertFalse(complete)
            assertTrue(spool.contains(pending.id))
            assertEquals(pending, MeasurementExportSpool(root).loadPending().single())
            assertTrue(isMeasurementExportComplete(true, 0, false))
            assertTrue(isMeasurementExportComplete(true, 1, true))
            assertFalse(isMeasurementExportComplete(false, 0, true))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun corruptSnapshotIsReportedAndKeptWhileValidSnapshotStillRecovers() {
        val root = Files.createTempDirectory("vmax-measurement-corrupt-spool").toFile()
        try {
            val pending = samplePendingExport()
            MeasurementExportSpool(root).stage(pending)
            val corrupt = java.io.File(root, "corrupt.json")
            corrupt.writeText("{not-json")

            val recovery = MeasurementExportSpool(root).loadPendingWithDiagnostics()

            assertEquals(listOf(pending), recovery.pendingExports)
            assertEquals(listOf("corrupt.json"), recovery.failures.map { it.fileName })
            assertTrue(corrupt.isFile)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun samplePendingExport(): PendingMeasurementExport = PendingMeasurementExport(
        id = "measurement-1000-2000-test",
        stoppedAt = 2_000L,
        snapshot = MeasurementExportSnapshot(
            rawRows = listOf("raw-before", "raw-after"),
            markerRows = listOf("0;1000;START", "1000;2000;STOP"),
            telemetryRows = listOf("telemetry"),
            deviceName = "BT638",
            startedAt = 1_000L,
            connectionCount = 1,
            receivedNotifications = 2,
            acceptedNotifications = 2,
            rejectedReads = 0,
            rejectedHybrids = 0,
            diagnosticNotifications = 0,
            diagnosticReadBundles = listOf(
                DiagnosticReadBundle(
                    records = listOf(
                        DiagnosticReadRecord(
                            timestampMs = 1_500L,
                            serviceUuid = "da1a1500-d532-4285-be94-b07a3e11a098",
                            characteristicUuid = "da1a1509-d532-4285-be94-b07a3e11a098",
                            shortId = "1509",
                            properties = "READ|NOTIFY",
                            status = 0,
                            length = 8,
                            hex = "01-02-03-04-61-00-00-00",
                            connectionEpoch = 4L,
                            measurementConnectionEpoch = 0,
                            evidence = "callback",
                            meaning = "battery",
                            scanId = "scan-test",
                            propertiesRaw = 0x12,
                            callbackReceived = true,
                            recordKind = DiagnosticRecordKind.GATT_READ_CALLBACK,
                            outcome = DiagnosticReadOutcome.CALLBACK_SUCCESS,
                            payloadValid = true,
                            rssi = -55
                        )
                    ),
                    deviceName = "BT638",
                    scanStartedAt = 1_400L,
                    scanFinishedAt = 1_600L,
                    connectionEpoch = 4L,
                    scanId = "scan-test",
                    completed = true,
                    completionOutcome = DiagnosticReadOutcome.SCAN_COMPLETED
                )
            )
        )
    )
}

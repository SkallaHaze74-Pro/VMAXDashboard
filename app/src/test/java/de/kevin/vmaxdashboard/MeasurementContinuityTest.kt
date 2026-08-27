package de.kevin.vmaxdashboard

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MeasurementContinuityTest {
    @Test
    fun completedDeepReadReplacesItsPartialCheckpointAndCannotRegress() {
        val partial = diagnosticBundleForContinuity(
            scanId = "same-scan",
            completed = false,
            finishedAt = 1_100L
        )
        val completed = diagnosticBundleForContinuity(
            scanId = "same-scan",
            completed = true,
            finishedAt = 1_200L
        )

        val upgraded = upsertDiagnosticBundleByScanId(listOf(partial), completed)
        assertEquals(1, upgraded.size)
        assertTrue(upgraded.single().completed)

        val notRegressed = upsertDiagnosticBundleByScanId(upgraded, partial)
        assertEquals(1, notRegressed.size)
        assertTrue(notRegressed.single().completed)
    }

    @Test
    fun duplicateRecoveryAttemptsBecomeOneMergedPublication() {
        val partial = pendingForContinuity(
            id = "journal-partial",
            startedAt = 1_000L,
            stoppedAt = 1_500L,
            rawRows = listOf("one")
        )
        val full = pendingForContinuity(
            id = "spool-full",
            startedAt = 1_000L,
            stoppedAt = 2_000L,
            rawRows = listOf("one", "two")
        )

        val consolidated = consolidateRecoveredMeasurementExports(listOf(full, partial, full))

        assertEquals(1, consolidated.size)
        assertEquals("recovered-publication-1000", consolidated.single().pending.id)
        assertEquals(listOf("one", "two"), consolidated.single().pending.snapshot.rawRows)
        assertEquals(setOf("journal-partial", "spool-full"), consolidated.single().sourceIds)
    }

    @Test
    fun startupRecoveryNeverConsumesANewProcessOwnedSegment() {
        val old = pendingForContinuity("old", 1_000L, 2_000L, listOf("old-row"))
        val live = pendingForContinuity("live", 3_000L, 3_100L, listOf("live-row"))

        val filtered = excludeProcessOwnedActiveExports(
            recovered = listOf(old, live),
            processOwnedStartedAt = setOf(3_000L)
        )

        assertEquals(listOf(old), filtered)
    }

    @Test
    fun recoveryKeepsLegitimateDuplicateRowsButDropsOverlappingCopies() {
        val repeated = "100;1100;same-packet"
        val partial = pendingForContinuity(
            id = "journal-partial",
            startedAt = 1_000L,
            stoppedAt = 1_100L,
            rawRows = listOf(repeated)
        )
        val full = pendingForContinuity(
            id = "spool-full",
            startedAt = 1_000L,
            stoppedAt = 1_200L,
            rawRows = listOf(repeated, repeated, "200;1200;later")
        )

        val rows = consolidateRecoveredMeasurementExports(listOf(partial, full))
            .single()
            .pending
            .snapshot
            .rawRows

        assertEquals(listOf(repeated, repeated, "200;1200;later"), rows)
    }

    @Test
    fun recoveryPreservesAppendOrderAcrossWallClockRollback() {
        val appendedBeforeRollback = "1000;2000;before-clock-rollback"
        val appendedAfterRollback = "500;1500;after-clock-rollback"
        val source = pendingForContinuity(
            id = "clock-rollback",
            startedAt = 1_000L,
            stoppedAt = 2_100L,
            rawRows = listOf(appendedBeforeRollback, appendedAfterRollback)
        )

        val rows = consolidateRecoveredMeasurementExports(listOf(source))
            .single().pending.snapshot.rawRows

        assertEquals(listOf(appendedBeforeRollback, appendedAfterRollback), rows)
    }

    @Test
    fun cleanSnapshotRemovesSyntheticRecoveryStopAsWellAsRestartMarker() {
        val recoveredJournal = pendingForContinuity(
            id = "journal",
            startedAt = 1_000L,
            stoppedAt = 1_500L,
            rawRows = listOf("journal-row"),
            markerRows = listOf(
                "0;1000;START",
                "500;1500;APP_NEUSTART • laufende Messfahrt automatisch gerettet",
                "500;1500;STOP"
            )
        )
        val cleanSpool = pendingForContinuity(
            id = "spool",
            startedAt = 1_000L,
            stoppedAt = 2_000L,
            rawRows = listOf("journal-row", "full-row"),
            markerRows = listOf("0;1000;START", "1000;2000;STOP")
        )

        val markers = consolidateRecoveredMeasurementExports(listOf(recoveredJournal, cleanSpool))
            .single().pending.snapshot.markerRows

        assertEquals(listOf("0;1000;START", "1000;2000;STOP"), markers)
    }

    @Test
    fun activeCheckpointBecomesACompleteRetryWithoutLosingAnyRows() {
        val active = PendingMeasurementExport(
            id = "active-start-1000-checkpoint-2000",
            stoppedAt = 2_000L,
            snapshot = MeasurementExportSnapshot(
                rawRows = listOf("raw-1", "raw-2"),
                markerRows = listOf("0;1000;START", "500;1500;Lichttest"),
                telemetryRows = listOf("live-1", "live-2"),
                deviceName = "BT638",
                startedAt = 1_000L,
                connectionCount = 2,
                receivedNotifications = 2,
                acceptedNotifications = 2,
                rejectedReads = 0,
                rejectedHybrids = 0,
                diagnosticNotifications = 0,
                diagnosticReadBundles = emptyList()
            )
        )

        val recovered = recoveredActiveMeasurementExport(active)

        assertEquals("recovered-${active.id}", recovered.id)
        assertEquals(active.snapshot.rawRows, recovered.snapshot.rawRows)
        assertEquals(active.snapshot.telemetryRows, recovered.snapshot.telemetryRows)
        assertEquals(
            listOf(
                "0;1000;START",
                "500;1500;Lichttest",
                "1000;2000;APP_NEUSTART • laufende Messfahrt automatisch gerettet",
                "1000;2000;STOP"
            ),
            recovered.snapshot.markerRows
        )
    }

    @Test
    fun packetsArrivingDuringSlowExportBelongToTheFreshSegmentExactlyOnce() {
        val buffer = MeasurementRowBuffer()
        buffer.start(1_000L)
        buffer.appendRaw("before-export")
        val frozen = buffer.rotate(
            currentStartedAt = 1_000L,
            stoppedAt = 2_000L,
            nextStartedAt = 2_000L
        )
        val exportStarted = CountDownLatch(1)
        val releaseExport = CountDownLatch(1)
        val exported = mutableListOf<String>()

        val worker = thread(start = true) {
            exportStarted.countDown()
            releaseExport.await(2, TimeUnit.SECONDS)
            exported += frozen.rawRows
        }
        assertTrue(exportStarted.await(2, TimeUnit.SECONDS))

        buffer.appendRaw("during-export")
        assertEquals(listOf("before-export"), frozen.rawRows)
        assertEquals(listOf("0;1000;START", "1000;2000;STOP"), frozen.markerRows)
        assertEquals(listOf("during-export"), buffer.rawSnapshot())
        assertEquals(listOf("0;2000;START"), buffer.markerSnapshot())

        releaseExport.countDown()
        worker.join(2_000L)
        assertFalse(worker.isAlive)
        assertEquals(listOf("before-export"), exported)
        assertEquals(1, (exported + buffer.rawSnapshot()).count { it == "during-export" })
    }

    @Test
    fun failedExportRemainsQueuedUntilTheExactSnapshotSucceeds() {
        val queue = RetainedExportQueue<String>()
        queue.enqueue("ride-27-to-90")

        assertEquals("ride-27-to-90", queue.peek())
        assertEquals(1, queue.size)
        assertFalse(queue.markSucceeded("different-ride"))
        assertEquals("ride-27-to-90", queue.peek())
        assertTrue(queue.markSucceeded("ride-27-to-90"))
        assertEquals(0, queue.size)
    }

    @Test
    fun richerDurableSnapshotReplacesQueueHeadForRetry() {
        val queue = RetainedExportQueue<String>()
        queue.enqueue("bounded-memory")

        assertTrue(queue.replaceHead("bounded-memory", "full-journal"))
        assertEquals("full-journal", queue.peek())
        assertFalse(queue.markSucceeded("bounded-memory"))
        assertTrue(queue.markSucceeded("full-journal"))
    }

    @Test
    fun journalRestoresRowsBeyondMemoryCapAndCanonicalRetryKeepsThem() {
        val allRows = (0..100_000).map { index -> "$index;${1_000L + index};row-$index" }
        val buffer = MeasurementRowBuffer(maxRows = 100_000)
        buffer.start(1_000L)
        allRows.forEach(buffer::appendRaw)
        val boundedRows = buffer.rawSnapshot()
        assertEquals(100_000, boundedRows.size)
        assertEquals(allRows[1], boundedRows.first())

        val bounded = pendingForContinuity("same-id", 1_000L, 102_000L, boundedRows)
        val journal = pendingForContinuity("journal-id", 1_000L, 102_000L, allRows)
        val durable = consolidateRecoveredMeasurementExports(listOf(bounded, journal))
            .single().pending.copy(id = bounded.id)
        val queue = RetainedExportQueue<PendingMeasurementExport>()
        queue.enqueue(bounded)

        assertTrue(queue.replaceHead(bounded, durable))
        assertEquals(100_001, queue.peek()?.snapshot?.rawRows?.size)
        assertEquals(allRows.first(), queue.peek()?.snapshot?.rawRows?.first())
    }

    private fun diagnosticBundleForContinuity(
        scanId: String,
        completed: Boolean,
        finishedAt: Long
    ): DiagnosticReadBundle = DiagnosticReadBundle(
        records = emptyList(),
        deviceName = "BT638",
        scanStartedAt = 1_000L,
        scanFinishedAt = finishedAt,
        connectionEpoch = 1L,
        scanId = scanId,
        completed = completed,
        completionOutcome = if (completed) {
            DiagnosticReadOutcome.SCAN_COMPLETED
        } else {
            DiagnosticReadOutcome.SCAN_PARTIAL
        }
    )

    private fun pendingForContinuity(
        id: String,
        startedAt: Long,
        stoppedAt: Long,
        rawRows: List<String>,
        markerRows: List<String> = listOf("0;$startedAt;START")
    ): PendingMeasurementExport = PendingMeasurementExport(
        id = id,
        stoppedAt = stoppedAt,
        snapshot = MeasurementExportSnapshot(
            rawRows = rawRows,
            markerRows = markerRows,
            telemetryRows = emptyList(),
            deviceName = "BT638",
            startedAt = startedAt,
            connectionCount = 1,
            receivedNotifications = rawRows.size,
            acceptedNotifications = 0,
            rejectedReads = 0,
            rejectedHybrids = 0,
            diagnosticNotifications = rawRows.size,
            diagnosticReadBundles = emptyList()
        )
    )
}

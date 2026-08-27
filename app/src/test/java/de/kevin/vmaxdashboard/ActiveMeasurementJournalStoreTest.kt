package de.kevin.vmaxdashboard

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActiveMeasurementJournalStoreTest {

    @Test
    fun `second manager recovery leaves first manager live segment untouched`() = withRoot { root ->
        val startedAt = 12_000L
        val firstManagerOwner = 1L
        val firstManagerStore = ActiveMeasurementJournalStore(root)
        val secondManagerStore = ActiveMeasurementJournalStore(root)
        val journalLane = OrderedIoExecutor("vmax-two-manager-journal-test")

        try {
            journalLane.submit {
                assertTrue(
                    ProcessActiveMeasurementJournals.register(
                        root,
                        startedAt,
                        firstManagerOwner
                    )
                )
                firstManagerStore.startSegment(startedAt, initialScalars(startedAt))
                firstManagerStore.appendRawRow(
                    startedAt,
                    recordedAt = 12_100L,
                    row = "first-manager-live-row"
                )
                firstManagerStore.syncSegment(startedAt)
                assertFalse(
                    ProcessActiveMeasurementJournals.register(
                        root,
                        startedAt,
                        ownerToken = 2L
                    )
                )
                assertFalse(
                    ProcessActiveMeasurementJournals.release(
                        root,
                        startedAt,
                        ownerToken = 2L
                    )
                )
            }.get()

            val recoveredBySecondManager = journalLane.submit {
                recoverStageAndClearUnownedActiveMeasurementJournals(
                    store = secondManagerStore,
                    root = root,
                    stage = { error("live journal must not be staged") }
                )
            }.get()

            assertTrue(recoveredBySecondManager.recovery.pendingExports.isEmpty())
            assertTrue(recoveredBySecondManager.recovery.failures.isEmpty())
            assertTrue(recoveredBySecondManager.segmentsNotCleared.isEmpty())
            assertEquals(
                listOf("first-manager-live-row"),
                journalLane.submit {
                    firstManagerStore.recoverSegmentWithDiagnostics(startedAt)
                        .pendingExports
                        .single()
                        .snapshot
                        .rawRows
                }.get()
            )

            journalLane.submit {
                firstManagerStore.appendRawRow(
                    startedAt,
                    recordedAt = 12_200L,
                    row = "first-manager-after-second-recovery"
                )
                firstManagerStore.syncSegment(startedAt)
                assertTrue(
                    ProcessActiveMeasurementJournals.release(
                        root,
                        startedAt,
                        firstManagerOwner
                    )
                )
            }.get()

            val staged = mutableListOf<PendingMeasurementExport>()
            val recoveredAfterRelease = journalLane.submit {
                recoverStageAndClearUnownedActiveMeasurementJournals(
                    store = secondManagerStore,
                    root = root,
                    stage = staged::add
                )
            }.get()

            assertEquals(1, recoveredAfterRelease.recovery.pendingExports.size)
            assertEquals(1, staged.size)
            assertEquals(
                listOf(
                    "first-manager-live-row",
                    "first-manager-after-second-recovery"
                ),
                staged.single().snapshot.rawRows
            )
            assertTrue(secondManagerStore.recoverPendingExports().isEmpty())
        } finally {
            journalLane.submit {
                ProcessActiveMeasurementJournals.release(root, startedAt, firstManagerOwner)
                firstManagerStore.clearSegment(startedAt)
            }.get()
            journalLane.close()
        }
    }

    @Test
    fun `incremental rows and scalar state recover without rewriting the prefix`() = withRoot { root ->
        val store = ActiveMeasurementJournalStore(root)
        val startedAt = 1_000L
        store.startSegment(startedAt, initialScalars(startedAt))
        store.appendRawRow(startedAt, recordedAt = 1_100L, row = "raw;with;semicolons\nquotes=\"safe\";ä")
        val journal = journalFiles(root).single()
        val prefixBeforeMoreRows = journal.readBytes()

        store.appendTelemetryRow(startedAt, recordedAt = 1_200L, row = "telemetry;one")
        store.appendMarkerRow(startedAt, recordedAt = 1_300L, row = "300;1300;LICHT • an;bestätigt")
        store.updateScalars(
            startedAt = startedAt,
            recordedAt = 1_400L,
            scalars = initialScalars(startedAt).copy(
                deviceName = "BT638 wiederverbunden",
                stoppedAt = 1_400L,
                connectionCount = 2,
                receivedNotifications = 3,
                acceptedNotifications = 2,
                rejectedReads = 1,
                rejectedHybrids = 1,
                diagnosticNotifications = 1
            )
        )

        val bytesAfterMoreRows = journal.readBytes()
        assertTrue(bytesAfterMoreRows.size > prefixBeforeMoreRows.size)
        assertTrue(bytesAfterMoreRows.copyOf(prefixBeforeMoreRows.size).contentEquals(prefixBeforeMoreRows))

        val recovered = ActiveMeasurementJournalStore(root).recoverPendingExports().single()
        assertEquals(listOf("raw;with;semicolons\nquotes=\"safe\";ä"), recovered.snapshot.rawRows)
        assertEquals(listOf("telemetry;one"), recovered.snapshot.telemetryRows)
        assertEquals("BT638 wiederverbunden", recovered.snapshot.deviceName)
        assertEquals(2, recovered.snapshot.connectionCount)
        assertEquals(3, recovered.snapshot.receivedNotifications)
        assertEquals(2, recovered.snapshot.acceptedNotifications)
        assertEquals(1, recovered.snapshot.rejectedReads)
        assertEquals(1, recovered.snapshot.rejectedHybrids)
        assertEquals(1, recovered.snapshot.diagnosticNotifications)
        assertEquals(
            listOf(
                "0;1000;START",
                "300;1300;LICHT • an;bestätigt",
                "400;1400;APP_NEUSTART • laufende Messfahrt automatisch gerettet",
                "400;1400;STOP"
            ),
            recovered.snapshot.markerRows
        )
    }

    @Test
    fun `torn final record is discarded while earlier rows remain appendable`() = withRoot { root ->
        val startedAt = 2_000L
        ActiveMeasurementJournalStore(root).apply {
            startSegment(startedAt, initialScalars(startedAt))
            appendRawRow(startedAt, recordedAt = 2_100L, row = "complete-before-tear")
        }
        val journal = journalFiles(root).single()
        FileOutputStream(journal, true).use { output ->
            output.write("{\"seq\":2,\"type\":\"RAW\",\"row\":\"torn".toByteArray())
            output.flush()
        }

        val reopened = ActiveMeasurementJournalStore(root)
        val diagnosed = reopened.recoverPendingExportsWithDiagnostics()
        assertEquals(1, diagnosed.failures.size)
        assertTrue(diagnosed.failures.single().message.contains("Präfix"))
        assertEquals(
            listOf("complete-before-tear"),
            diagnosed.pendingExports.single().snapshot.rawRows
        )

        reopened.appendRawRow(startedAt, recordedAt = 2_200L, row = "complete-after-reopen")
        assertEquals(
            listOf("complete-before-tear", "complete-after-reopen"),
            ActiveMeasurementJournalStore(root).recoverPendingExports().single().snapshot.rawRows
        )
    }

    @Test
    fun `bad journal header is reported while another segment still recovers`() = withRoot { root ->
        val store = ActiveMeasurementJournalStore(root)
        store.startSegment(7_000L, initialScalars(7_000L))
        File(root, "segment-p8000.jsonl").writeText("{\"schema\":\"wrong\"}\n")

        val recovery = ActiveMeasurementJournalStore(root).recoverPendingExportsWithDiagnostics()

        assertEquals(listOf(7_000L), recovery.pendingExports.map { it.snapshot.startedAt })
        assertEquals(listOf("segment-p8000.jsonl"), recovery.failures.map { it.fileName })
    }

    @Test
    fun `all segments survive clock rollback and clearing one leaves the other`() = withRoot { root ->
        val store = ActiveMeasurementJournalStore(root)
        store.startSegment(9_000L, initialScalars(9_000L).copy(deviceName = "first-generation"))
        store.appendRawRow(9_000L, recordedAt = 9_100L, row = "first")

        // A wall-clock rollback makes the newer generation's timestamp smaller.
        store.startSegment(4_000L, initialScalars(4_000L).copy(deviceName = "newer-generation"))
        store.appendRawRow(4_000L, recordedAt = 4_100L, row = "second")

        val recovered = ActiveMeasurementJournalStore(root).recoverPendingExports()
        assertEquals(setOf(4_000L, 9_000L), recovered.map { it.snapshot.startedAt }.toSet())
        assertEquals(2, journalFiles(root).size)
        assertTrue(journalFiles(root).all { it.name.matches(Regex("segment-[A-Za-z0-9_-]+\\.jsonl")) })

        store.clearSegment(9_000L)
        val afterClear = ActiveMeasurementJournalStore(root).recoverPendingExports()
        assertEquals(listOf(4_000L), afterClear.map { it.snapshot.startedAt })
        assertEquals(listOf("second"), afterClear.single().snapshot.rawRows)
    }

    @Test
    fun `later diagnostic bundle replaces partial bundle with the same scan id`() = withRoot { root ->
        val startedAt = 3_000L
        val store = ActiveMeasurementJournalStore(root)
        store.startSegment(startedAt, initialScalars(startedAt))
        store.replaceDiagnosticBundle(
            startedAt,
            recordedAt = 3_100L,
            bundle = sampleBundle(scanId = "scan-stable", completed = false, finishedAt = 3_100L)
        )
        store.replaceDiagnosticBundle(
            startedAt,
            recordedAt = 3_200L,
            bundle = sampleBundle(scanId = "scan-stable", completed = true, finishedAt = 3_200L)
        )
        store.replaceDiagnosticBundle(
            startedAt,
            recordedAt = 3_300L,
            bundle = sampleBundle(scanId = "scan-stable", completed = false, finishedAt = 3_300L)
        )

        val bundles = ActiveMeasurementJournalStore(root)
            .recoverPendingExports()
            .single()
            .snapshot
            .diagnosticReadBundles
        assertEquals(1, bundles.size)
        assertEquals("scan-stable", bundles.single().scanId)
        assertTrue(bundles.single().completed)
        assertEquals(DiagnosticReadOutcome.SCAN_COMPLETED, bundles.single().completionOutcome)
    }

    @Test
    fun `reopening start is idempotent and recovery markers never duplicate`() = withRoot { root ->
        val startedAt = 5_000L
        ActiveMeasurementJournalStore(root).startSegment(startedAt, initialScalars(startedAt))
        ActiveMeasurementJournalStore(root).startSegment(startedAt, initialScalars(startedAt))

        val first = ActiveMeasurementJournalStore(root).recoverPendingExports().single()
        val second = ActiveMeasurementJournalStore(root).recoverPendingExports().single()

        assertEquals(first, second)
        assertEquals(1, first.snapshot.markerRows.count { markerLabel(it) == "START" })
        assertEquals(1, first.snapshot.markerRows.count { markerLabel(it).startsWith("APP_NEUSTART") })
        assertEquals(1, first.snapshot.markerRows.count { markerLabel(it) == "STOP" })
        assertEquals(1, journalFiles(root).size)
    }

    @Test
    fun `bounded sync fires by row count time and clock rollback`() = withRoot { root ->
        val startedAt = 6_000L
        val store = ActiveMeasurementJournalStore(root)
        store.startSegment(startedAt, initialScalars(startedAt))

        assertFalse(
            store.syncIfNeeded(
                startedAt,
                nowMs = 6_001L,
                maxUnsyncedRecords = 3,
                maxUnsyncedMs = 5_000L
            )
        )
        store.appendRawRow(startedAt, recordedAt = 6_100L, row = "one")
        store.appendRawRow(startedAt, recordedAt = 6_200L, row = "two")
        assertTrue(
            store.syncIfNeeded(
                startedAt,
                nowMs = 6_200L,
                maxUnsyncedRecords = 3,
                maxUnsyncedMs = 5_000L
            )
        )

        store.appendRawRow(startedAt, recordedAt = 7_000L, row = "after-sync")
        assertTrue(
            store.syncIfNeeded(
                startedAt,
                nowMs = 6_500L,
                maxUnsyncedRecords = 99,
                maxUnsyncedMs = 5_000L
            )
        )
    }

    private fun initialScalars(startedAt: Long) = ActiveMeasurementJournalScalars(
        deviceName = "BT638",
        stoppedAt = startedAt,
        connectionCount = 1,
        receivedNotifications = 0,
        acceptedNotifications = 0,
        rejectedReads = 0,
        rejectedHybrids = 0,
        diagnosticNotifications = 0
    )

    private fun sampleBundle(scanId: String, completed: Boolean, finishedAt: Long) =
        DiagnosticReadBundle(
            records = emptyList(),
            deviceName = "BT638",
            scanStartedAt = 3_050L,
            scanFinishedAt = finishedAt,
            connectionEpoch = 7L,
            scanId = scanId,
            completed = completed,
            completionOutcome = if (completed) {
                DiagnosticReadOutcome.SCAN_COMPLETED
            } else {
                DiagnosticReadOutcome.SCAN_PARTIAL
            }
        )

    private fun markerLabel(row: String): String = row.split(';', limit = 3).getOrElse(2) { "" }

    private fun journalFiles(root: File): List<File> =
        root.listFiles()?.filter { it.isFile && it.extension == "jsonl" }.orEmpty()

    private fun withRoot(block: (File) -> Unit) {
        val root = Files.createTempDirectory("vmax-active-journal").toFile()
        try {
            block(root)
        } finally {
            root.deleteRecursively()
        }
    }
}

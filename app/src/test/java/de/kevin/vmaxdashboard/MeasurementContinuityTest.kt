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
}

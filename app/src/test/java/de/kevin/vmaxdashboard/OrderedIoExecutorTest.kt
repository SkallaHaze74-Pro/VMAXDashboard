package de.kevin.vmaxdashboard

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OrderedIoExecutorTest {
    @Test
    fun blockedIoNeverBlocksCallerAndLaterBarrierKeepsSubmissionOrder() {
        val io = OrderedIoExecutor("ordered-io-test")
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val order = Collections.synchronizedList(mutableListOf<String>())
        try {
            val callStartedAt = System.nanoTime()
            io.execute {
                entered.countDown()
                release.await(2, TimeUnit.SECONDS)
                order += "append"
            }
            val callerElapsedMs = (System.nanoTime() - callStartedAt) / 1_000_000L

            assertTrue(entered.await(2, TimeUnit.SECONDS))
            assertTrue("enqueue blocked caller for ${callerElapsedMs}ms", callerElapsedMs < 100L)
            val barrier = io.submit {
                order += "sync"
                order.toList()
            }
            assertFalse(barrier.isDone)

            release.countDown()
            assertEquals(listOf("append", "sync"), barrier.get(2, TimeUnit.SECONDS))
        } finally {
            release.countDown()
            io.close()
        }
    }
}

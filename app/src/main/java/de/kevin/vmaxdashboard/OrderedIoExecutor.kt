package de.kevin.vmaxdashboard

import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future

/** One non-UI writer lane for ordered durability operations and explicit barriers. */
internal class OrderedIoExecutor(threadName: String) : AutoCloseable {
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, threadName).apply { isDaemon = true }
    }

    fun execute(operation: () -> Unit) {
        executor.execute(operation)
    }

    fun <T> submit(operation: () -> T): Future<T> =
        executor.submit(Callable(operation))

    override fun close() {
        executor.shutdownNow()
    }
}

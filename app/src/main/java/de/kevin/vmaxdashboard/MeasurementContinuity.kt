package de.kevin.vmaxdashboard

import java.util.ArrayDeque

internal data class FrozenMeasurementRows(
    val rawRows: List<String>,
    val markerRows: List<String>,
    val telemetryRows: List<String>
)

internal fun isMeasurementExportComplete(
    coreExportSucceeded: Boolean,
    diagnosticBundleCount: Int,
    diagnosticExportSucceeded: Boolean
): Boolean = coreExportSucceeded &&
    (diagnosticBundleCount <= 0 || diagnosticExportSucceeded)

/**
 * Owns the mutable rows of exactly one active measurement segment.
 *
 * [rotate] freezes the old rows and prepares the optional next segment in one
 * synchronous operation. BleScooterManager calls it while holding its GATT lock,
 * so a Bluetooth callback can only land before the old STOP marker or after the
 * new START marker, never in an export gap.
 */
internal class MeasurementRowBuffer(
    private val maxRows: Int = 100_000
) {
    private val rawRows = mutableListOf<String>()
    private val markerRows = mutableListOf<String>()
    private val telemetryRows = mutableListOf<String>()

    fun start(startedAt: Long) {
        clear()
        appendMarker("START", startedAt, startedAt)
    }

    fun clearRaw() {
        rawRows.clear()
    }

    fun appendRaw(row: String) {
        rawRows += row
        trimToLimit(rawRows)
    }

    fun appendTelemetry(row: String) {
        telemetryRows += row
        trimToLimit(telemetryRows)
    }

    fun appendMarker(label: String, now: Long, startedAt: Long) {
        val relative = if (startedAt > 0L) now - startedAt else 0L
        markerRows += listOf(relative.toString(), now.toString(), label).joinToString(";")
    }

    fun rawSnapshot(): List<String> = rawRows.toList()

    fun markerSnapshot(): List<String> = markerRows.toList()

    fun containsRaw(row: String): Boolean = row in rawRows

    fun prependRaw(rows: List<String>) {
        if (rows.isEmpty()) return
        rawRows.addAll(0, rows)
        while (rawRows.size > maxRows) rawRows.removeAt(0)
    }

    fun rotate(
        currentStartedAt: Long,
        stoppedAt: Long,
        nextStartedAt: Long?
    ): FrozenMeasurementRows {
        appendMarker("STOP", stoppedAt, currentStartedAt)
        val frozen = FrozenMeasurementRows(
            rawRows = rawRows.toList(),
            markerRows = markerRows.toList(),
            telemetryRows = telemetryRows.toList()
        )
        clear()
        nextStartedAt?.let { appendMarker("START", it, it) }
        return frozen
    }

    private fun clear() {
        rawRows.clear()
        markerRows.clear()
        telemetryRows.clear()
    }

    private fun trimToLimit(rows: MutableList<String>) {
        if (rows.size > maxRows) rows.removeAt(0)
    }
}

/** A failed export remains at the head until that exact immutable item succeeds. */
internal class RetainedExportQueue<T> {
    private val pending = ArrayDeque<T>()

    @Synchronized
    fun enqueue(item: T) {
        pending.addLast(item)
    }

    @Synchronized
    fun peek(): T? = pending.peekFirst()

    @Synchronized
    fun markSucceeded(expected: T): Boolean {
        if (pending.peekFirst() != expected) return false
        pending.removeFirst()
        return true
    }

    val size: Int
        get() = synchronized(this) { pending.size }
}

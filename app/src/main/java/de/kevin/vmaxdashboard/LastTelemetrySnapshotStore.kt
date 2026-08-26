package de.kevin.vmaxdashboard

import android.content.Context
import kotlin.math.abs

internal data class LastTelemetrySnapshot(
    val batteryPercent: Int?,
    val voltageV: Double?,
    val measuredAtMs: Long
) {
    companion object {
        val EMPTY = LastTelemetrySnapshot(null, null, 0L)
    }
}

private const val LAST_TELEMETRY_CHECKPOINT_MS = 30_000L
private const val LAST_TELEMETRY_VOLTAGE_DELTA_V = 0.2
internal const val LAST_TELEMETRY_MAX_FUTURE_SKEW_MS = 5 * 60_000L

internal fun mergeLastTelemetrySnapshot(
    current: LastTelemetrySnapshot,
    rawBatteryPercent: Int?,
    voltageV: Double?,
    measuredAtMs: Long
): LastTelemetrySnapshot {
    if (measuredAtMs <= 0L || measuredAtMs < current.measuredAtMs) return current
    val validBattery = rawBatteryPercent?.takeIf { it in 0..100 }
    val validVoltage = voltageV?.takeIf { it.isFinite() && it > 0.0 && it <= 100.0 }
    if (validBattery == null && validVoltage == null) return current
    return LastTelemetrySnapshot(
        batteryPercent = validBattery ?: current.batteryPercent,
        voltageV = validVoltage ?: current.voltageV,
        measuredAtMs = measuredAtMs
    )
}

internal fun shouldPersistLastTelemetrySnapshot(
    previous: LastTelemetrySnapshot,
    next: LastTelemetrySnapshot
): Boolean {
    if (next.measuredAtMs <= 0L) return false
    if (previous.measuredAtMs <= 0L) return true
    if (previous.batteryPercent != next.batteryPercent) return true
    val previousVoltage = previous.voltageV
    val nextVoltage = next.voltageV
    if (previousVoltage == null || nextVoltage == null) {
        if (previousVoltage != nextVoltage) return true
    } else if (abs(previousVoltage - nextVoltage) >= LAST_TELEMETRY_VOLTAGE_DELTA_V) {
        return true
    }
    return next.measuredAtMs - previous.measuredAtMs >= LAST_TELEMETRY_CHECKPOINT_MS
}

internal fun sanitizeLoadedLastTelemetrySnapshot(
    snapshot: LastTelemetrySnapshot,
    nowMs: Long
): LastTelemetrySnapshot {
    if (snapshot.measuredAtMs <= 0L || snapshot.measuredAtMs > nowMs + LAST_TELEMETRY_MAX_FUTURE_SKEW_MS) {
        return LastTelemetrySnapshot.EMPTY
    }
    val battery = snapshot.batteryPercent?.takeIf { it in 0..100 }
    val voltage = snapshot.voltageV?.takeIf { it.isFinite() && it > 0.0 && it <= 100.0 }
    return if (battery == null && voltage == null) {
        LastTelemetrySnapshot.EMPTY
    } else {
        snapshot.copy(batteryPercent = battery, voltageV = voltage)
    }
}

/** Force bypasses throttling only; it never bypasses timestamp monotonicity. */
internal fun shouldAcceptLastTelemetryWrite(
    previous: LastTelemetrySnapshot,
    next: LastTelemetrySnapshot,
    force: Boolean,
    nowMs: Long = System.currentTimeMillis()
): Boolean {
    val sanitizedNext = sanitizeLoadedLastTelemetrySnapshot(next, nowMs)
    if (sanitizedNext == LastTelemetrySnapshot.EMPTY) return false
    val sanitizedPrevious = sanitizeLoadedLastTelemetrySnapshot(previous, nowMs)
    if (sanitizedPrevious != LastTelemetrySnapshot.EMPTY &&
        sanitizedNext.measuredAtMs < sanitizedPrevious.measuredAtMs
    ) {
        return false
    }
    if (force && sanitizedPrevious != LastTelemetrySnapshot.EMPTY &&
        sanitizedNext.measuredAtMs == sanitizedPrevious.measuredAtMs &&
        sanitizedNext != sanitizedPrevious
    ) {
        return false
    }
    return force || shouldPersistLastTelemetrySnapshot(sanitizedPrevious, sanitizedNext)
}

/** Persists only the last real read-only battery sample, never a guessed offline value. */
internal class LastTelemetrySnapshotStore(context: Context) {
    private companion object {
        const val PREFS = "vmax_last_telemetry"
        const val KEY_BATTERY = "battery_percent"
        const val KEY_VOLTAGE_BITS = "voltage_bits"
        const val KEY_MEASURED_AT = "measured_at_ms"
    }

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private var lastPersisted = loadFromPreferences()

    @Synchronized
    fun load(): LastTelemetrySnapshot = lastPersisted

    @Synchronized
    fun persist(snapshot: LastTelemetrySnapshot, force: Boolean = false) {
        val now = System.currentTimeMillis()
        val sanitizedPrevious = sanitizeLoadedLastTelemetrySnapshot(lastPersisted, now)
        val sanitizedSnapshot = sanitizeLoadedLastTelemetrySnapshot(snapshot, now)
        if (!shouldAcceptLastTelemetryWrite(sanitizedPrevious, sanitizedSnapshot, force, now)) return
        val editor = prefs.edit().apply {
            sanitizedSnapshot.batteryPercent?.let { putInt(KEY_BATTERY, it) } ?: remove(KEY_BATTERY)
            sanitizedSnapshot.voltageV?.let { putLong(KEY_VOLTAGE_BITS, it.toBits()) }
                ?: remove(KEY_VOLTAGE_BITS)
            putLong(KEY_MEASURED_AT, sanitizedSnapshot.measuredAtMs)
        }
        val accepted = if (force) editor.commit() else {
            editor.apply()
            true
        }
        if (accepted) lastPersisted = sanitizedSnapshot
    }

    private fun loadFromPreferences(): LastTelemetrySnapshot {
        val battery = runCatching {
            if (prefs.contains(KEY_BATTERY)) prefs.getInt(KEY_BATTERY, -1) else null
        }.getOrNull()
        val voltage = runCatching {
            if (prefs.contains(KEY_VOLTAGE_BITS)) {
                Double.fromBits(prefs.getLong(KEY_VOLTAGE_BITS, 0L))
            } else {
                null
            }
        }.getOrNull()
        val measuredAt = runCatching { prefs.getLong(KEY_MEASURED_AT, 0L) }.getOrDefault(0L)
        val loaded = mergeLastTelemetrySnapshot(
            current = LastTelemetrySnapshot.EMPTY,
            rawBatteryPercent = battery,
            voltageV = voltage,
            measuredAtMs = measuredAt
        )
        return sanitizeLoadedLastTelemetrySnapshot(loaded, System.currentTimeMillis())
    }
}

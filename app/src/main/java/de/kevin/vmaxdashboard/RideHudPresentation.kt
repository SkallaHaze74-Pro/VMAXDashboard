package de.kevin.vmaxdashboard

internal enum class RideHudBatterySource {
    STABLE,
    STABLE_WITH_RAW,
    RAW,
    STALE,
    LAST_KNOWN,
    UNAVAILABLE
}

internal data class RideHudReading(
    val visible: Boolean,
    val speedLive: Boolean,
    val speedText: String,
    val batteryText: String,
    val batteryDetail: String,
    val batterySource: RideHudBatterySource,
    val statusText: String
)

internal const val MAX_RIDE_HUD_BATTERY_AGE_MS = 10_000L

/** Builds the HUD text without carrying stale telemetry across BLE connections. */
internal fun rideHudReading(
    state: ScooterState,
    nowElapsedRealtimeMs: Long,
    nowWallClockMs: Long = System.currentTimeMillis()
): RideHudReading {
    val liveTelemetry = state.connected && state.telemetryReady
    val speed = if (liveTelemetry) {
        freshBatterySpeedKmh(
            speedKmh = state.speedKmh,
            speedSampleAtElapsedMs = state.lastSpeedSampleElapsedRealtimeMs,
            speedSampleConnectionEpoch = state.speedSampleConnectionEpoch,
            connectionEpoch = state.connectionEpoch,
            nowElapsedMs = nowElapsedRealtimeMs
        )
    } else {
        null
    }
    val battery = if (liveTelemetry) {
        liveBatteryReading(state, nowWallClockMs)
    } else {
        historicalBatteryReading(state)
    }

    return RideHudReading(
        visible = liveTelemetry,
        speedLive = speed != null,
        speedText = speed?.let(::germanHudNumber) ?: "—",
        batteryText = battery.text,
        batteryDetail = battery.detail,
        batterySource = battery.source,
        statusText = when {
            !liveTelemetry -> "VERBINDE …"
            speed != null -> "LIVE"
            else -> "VERBUNDEN"
        }
    )
}

private data class HudBatteryReading(
    val text: String,
    val detail: String,
    val source: RideHudBatterySource
)

private fun liveBatteryReading(state: ScooterState, nowWallClockMs: Long): HudBatteryReading {
    val stable = state.batteryPercent.validBatteryPercent()
    val raw = state.batteryPercentRaw.validBatteryPercent()
    val batteryAgeMs = nowWallClockMs - state.lastBatteryTelemetryAt
    if (state.lastBatteryTelemetryAt <= 0L || batteryAgeMs !in 0..MAX_RIDE_HUD_BATTERY_AGE_MS) {
        val stale = stable ?: raw ?: state.lastKnownBatteryPercent.validBatteryPercent()
        return if (stale != null) {
            HudBatteryReading(
                text = "$stale %",
                detail = "zuletzt • Daten alt",
                source = RideHudBatterySource.STALE
            )
        } else {
            HudBatteryReading("—", "keine Akkudaten", RideHudBatterySource.UNAVAILABLE)
        }
    }

    if (stable != null && state.batteryStability == BatteryPercentStability.STABLE) {
        return HudBatteryReading("$stable %", "stabil", RideHudBatterySource.STABLE)
    }

    if (stable != null && raw != null) {
        val status = when (state.batteryStability) {
            BatteryPercentStability.RECOVERING_AFTER_LOAD,
            BatteryPercentStability.HELD_TRANSIENT -> "Erholung"
            else -> "Bestätigung"
        }
        return HudBatteryReading(
            text = "$stable %",
            detail = "roh $raw % • $status",
            source = RideHudBatterySource.STABLE_WITH_RAW
        )
    }

    if (stable != null) {
        return HudBatteryReading(
            text = "$stable %",
            detail = "zuletzt stabil",
            source = RideHudBatterySource.STABLE_WITH_RAW
        )
    }

    if (raw != null) {
        val status = when (state.batteryStability) {
            BatteryPercentStability.RECOVERING_AFTER_LOAD,
            BatteryPercentStability.HELD_TRANSIENT -> "Erholung"
            else -> "Bestätigung"
        }
        return HudBatteryReading(
            text = "$raw %",
            detail = "roh • $status",
            source = RideHudBatterySource.RAW
        )
    }

    return HudBatteryReading("—", "keine Akkudaten", RideHudBatterySource.UNAVAILABLE)
}

private fun historicalBatteryReading(state: ScooterState): HudBatteryReading {
    val lastKnown = state.lastKnownBatteryPercent.validBatteryPercent()
        ?: return HudBatteryReading("—", "offline", RideHudBatterySource.UNAVAILABLE)
    return HudBatteryReading("$lastKnown %", "zuletzt", RideHudBatterySource.LAST_KNOWN)
}

private fun Int?.validBatteryPercent(): Int? = this?.takeIf { it in 0..100 }

private fun germanHudNumber(value: Double): String =
    String.format(java.util.Locale.GERMANY, "%.1f", value)

package de.kevin.vmaxdashboard

import kotlin.math.abs

internal enum class BatteryPercentStability {
    WAITING_FOR_REST,
    STABLE,
    HELD_TRANSIENT,
    INVALID_RAW,
    DISCONNECTED,
    RESET
}

internal data class BatteryPercentReading(
    val rawPercent: Int?,
    val stablePercent: Int?,
    val stability: BatteryPercentStability
)

/** Fail closed when the carried 1505 speed is stale or belongs to another GATT epoch. */
internal fun freshBatterySpeedKmh(
    speedKmh: Double?,
    speedSampleAtElapsedMs: Long,
    speedSampleConnectionEpoch: Long,
    connectionEpoch: Long,
    nowElapsedMs: Long
): Double? {
    val speed = speedKmh ?: return null
    if (!speed.isFinite() || speed < 0.0 || speedSampleAtElapsedMs <= 0L) return null
    if (speedSampleConnectionEpoch != connectionEpoch) return null
    val ageMs = nowElapsedMs - speedSampleAtElapsedMs
    return speed.takeIf { ageMs in 0..StartModeWriteSafetyPolicy.MAX_SPEED_SAMPLE_AGE_MS }
}

/**
 * Pure state machine that keeps the controller's raw SOC observable while
 * preventing load sag and moving recovery from immediately changing the
 * user-facing battery percentage.
 */
internal class BatteryPercentStabilizer {
    private companion object {
        const val REST_SAMPLES_REQUIRED = 3
        const val MAX_REST_CURRENT_A = 1.0
        const val MAX_REST_SPEED_KMH = 0.5
        const val MAX_REST_WINDOW_SPREAD_PERCENT = 1
    }

    private var rawPercent: Int? = null
    private var stablePercent: Int? = null
    private var stability = BatteryPercentStability.RESET
    private val restedCandidates = mutableListOf<Int>()

    fun observe(rawPercent: Int?, currentA: Double?, speedKmh: Double?): BatteryPercentReading {
        if (rawPercent == null) return current()
        this.rawPercent = rawPercent

        if (rawPercent !in 0..100) {
            restedCandidates.clear()
            stability = BatteryPercentStability.INVALID_RAW
            return current()
        }

        if (!isRested(currentA, speedKmh)) {
            restedCandidates.clear()
            stability = BatteryPercentStability.HELD_TRANSIENT
            return current()
        }

        addRestedCandidate(rawPercent)
        if (restedCandidates.size < REST_SAMPLES_REQUIRED) {
            stability = BatteryPercentStability.WAITING_FOR_REST
            return current()
        }

        val target = restedCandidates.sorted()[REST_SAMPLES_REQUIRED / 2]
        restedCandidates.clear()
        stablePercent = target
        stability = BatteryPercentStability.STABLE
        return current()
    }

    fun current(): BatteryPercentReading = BatteryPercentReading(
        rawPercent = rawPercent,
        stablePercent = stablePercent,
        stability = stability
    )

    fun disconnect(): BatteryPercentReading = clear(BatteryPercentStability.DISCONNECTED)

    fun reset(): BatteryPercentReading = clear(BatteryPercentStability.RESET)

    private fun isRested(currentA: Double?, speedKmh: Double?): Boolean =
        currentA != null && currentA.isFinite() &&
            speedKmh != null && speedKmh.isFinite() && speedKmh >= 0.0 &&
            abs(currentA) <= MAX_REST_CURRENT_A && speedKmh <= MAX_REST_SPEED_KMH

    private fun addRestedCandidate(candidate: Int) {
        val prospectiveMin = minOf(restedCandidates.minOrNull() ?: candidate, candidate)
        val prospectiveMax = maxOf(restedCandidates.maxOrNull() ?: candidate, candidate)
        if (prospectiveMax - prospectiveMin > MAX_REST_WINDOW_SPREAD_PERCENT) {
            restedCandidates.clear()
        }
        restedCandidates += candidate
    }

    private fun clear(clearedState: BatteryPercentStability): BatteryPercentReading {
        rawPercent = null
        stablePercent = null
        restedCandidates.clear()
        stability = clearedState
        return current()
    }
}

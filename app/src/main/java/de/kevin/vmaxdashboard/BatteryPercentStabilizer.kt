package de.kevin.vmaxdashboard

import kotlin.math.abs

enum class BatteryPercentStability {
    WAITING_FOR_REST,
    STABLE,
    RECOVERING_AFTER_LOAD,
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
 * Prefer the strongest valid movement evidence available to the current
 * notification. The just-decoded 1505 value must be considered immediately,
 * while a still-fresh carried value keeps the recovery hold conservative on
 * the first stop packet.
 */
internal fun batteryMotionSpeedKmh(
    decodedSpeedKmh: Double?,
    freshCarriedSpeedKmh: Double?
): Double? = listOfNotNull(decodedSpeedKmh, freshCarriedSpeedKmh)
    .filter { it.isFinite() && it >= 0.0 }
    .maxOrNull()

/**
 * Pure state machine that keeps the controller's raw SOC observable while
 * preventing load sag and moving recovery from immediately changing the
 * user-facing battery percentage.
 */
internal class BatteryPercentStabilizer {
    private companion object {
        const val REST_SAMPLES_REQUIRED = 3
        const val POST_LOAD_RECOVERY_MS = 45_000L
        const val MAX_REST_CURRENT_A = 1.0
        const val MAX_REST_SPEED_KMH = 0.5
        const val MAX_REST_WINDOW_SPREAD_PERCENT = 1

        fun monotonicNowMs(): Long = System.nanoTime() / 1_000_000L
    }

    private var rawPercent: Int? = null
    private var stablePercent: Int? = null
    private var stability = BatteryPercentStability.RESET
    private val restedCandidates = mutableListOf<Int>()
    private var recoveryStartedAtElapsedMs: Long? = null
    private var lastObservationElapsedMs: Long? = null

    fun observe(
        rawPercent: Int?,
        currentA: Double?,
        speedKmh: Double?,
        nowElapsedMs: Long = monotonicNowMs()
    ): BatteryPercentReading {
        val effectiveNowElapsedMs = nonDecreasingElapsedMs(nowElapsedMs)
        if (isMovingOrLoaded(currentA, speedKmh)) {
            recoveryStartedAtElapsedMs = effectiveNowElapsedMs
            restedCandidates.clear()
        }

        val hadRecoveryPeriod = recoveryStartedAtElapsedMs != null
        val recoveringAfterLoad = isRecoveringAfterLoad(effectiveNowElapsedMs)
        if (!recoveringAfterLoad) {
            recoveryStartedAtElapsedMs = null
        }
        if (rawPercent == null) {
            if (recoveringAfterLoad) {
                stability = BatteryPercentStability.RECOVERING_AFTER_LOAD
            } else if (hadRecoveryPeriod) {
                stability = BatteryPercentStability.WAITING_FOR_REST
            }
            return current()
        }
        this.rawPercent = rawPercent

        if (rawPercent !in 0..100) {
            restedCandidates.clear()
            stability = if (recoveringAfterLoad) {
                BatteryPercentStability.RECOVERING_AFTER_LOAD
            } else {
                BatteryPercentStability.INVALID_RAW
            }
            return current()
        }

        if (recoveringAfterLoad) {
            restedCandidates.clear()
            stability = BatteryPercentStability.RECOVERING_AFTER_LOAD
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

    private fun isMovingOrLoaded(currentA: Double?, speedKmh: Double?): Boolean {
        val observedLoad = currentA?.takeIf { it.isFinite() }
            ?.let { abs(it) > MAX_REST_CURRENT_A } == true
        val observedMovement = speedKmh?.takeIf { it.isFinite() && it >= 0.0 }
            ?.let { it > MAX_REST_SPEED_KMH } == true
        return observedLoad || observedMovement
    }

    private fun isRecoveringAfterLoad(nowElapsedMs: Long): Boolean {
        val recoveryStartedAt = recoveryStartedAtElapsedMs ?: return false
        if (nowElapsedMs < recoveryStartedAt) return true
        return nowElapsedMs - recoveryStartedAt < POST_LOAD_RECOVERY_MS
    }

    private fun nonDecreasingElapsedMs(observedElapsedMs: Long): Long {
        val previousElapsedMs = lastObservationElapsedMs
        val effectiveElapsedMs = if (previousElapsedMs == null) {
            observedElapsedMs
        } else {
            maxOf(previousElapsedMs, observedElapsedMs)
        }
        lastObservationElapsedMs = effectiveElapsedMs
        return effectiveElapsedMs
    }

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
        recoveryStartedAtElapsedMs = null
        lastObservationElapsedMs = null
        stability = clearedState
        return current()
    }
}

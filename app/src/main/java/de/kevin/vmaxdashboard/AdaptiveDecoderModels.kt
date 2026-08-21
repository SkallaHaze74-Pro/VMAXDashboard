package de.kevin.vmaxdashboard

import org.json.JSONObject
import java.io.File

data class AdaptiveProfileSnapshot(
    val revision: String = "",
    val ruleCount: Int = 0,
    val confirmedRuleCount: Int = 0,
    val generatedAtMs: Long = 0L,
    val source: String = "Noch kein KI-Profil",
    val signals: Set<String> = emptySet(),
    val cloudRuleCount: Int = 0,
    val localRuleCount: Int = 0,
    val confidenceSummary: String = "Noch keine Vertrauensdaten"
)

data class AdaptiveDecodedTelemetry(
    val batteryPercent: Int? = null,
    val speedKmh: Double? = null,
    val voltageV: Double? = null,
    val currentA: Double? = null,
    val powerW: Double? = null,
    val motorTemperatureC: Double? = null,
    val batteryTemperatureC: Double? = null,
    val tripDistanceKm: Double? = null,
    val odometerKm: Double? = null,
    val brakeActive: Boolean? = null,
    val leftIndicator: Boolean? = null,
    val rightIndicator: Boolean? = null,
    val lightOn: Boolean? = null,
    val lockActive: Boolean? = null,
    val charging: Boolean? = null,
    val signalConfidence: Map<String, String> = emptyMap(),
    val signalSources: Map<String, String> = emptyMap(),
    val signalChannels: Map<String, String> = emptyMap()
)

internal data class AdaptiveRule(
    val id: String,
    val signal: String,
    val channel: String,
    val offset: Int,
    val width: Int,
    val encoding: String,
    val scale: Double,
    val bias: Double,
    val activeValue: Long?,
    val inactiveValue: Long?,
    val confidence: Int,
    val observations: Int,
    val sessionCount: Int = 1,
    val consistentPairCount: Int = 1,
    val conflictCount: Int = 0,
    val status: String,
    val source: String
)

internal data class DecodedCandidate(
    val signal: String,
    val value: Any,
    val confidenceLabel: String,
    val sourceLabel: String,
    val channel: String,
    val priority: Int
)

internal fun shouldInstallCloudProfile(
    incomingGeneratedAtMs: Long,
    currentGeneratedAtMs: Long,
    usableRuleCount: Int
): Boolean = usableRuleCount > 0 && incomingGeneratedAtMs >= currentGeneratedAtMs

internal fun isActivatableAdaptiveRule(status: String, confidence: Int): Boolean =
    status == "confirmed" && confidence >= 92

private val ADAPTIVE_BOOLEAN_SIGNALS = setOf(
    "brakeActive",
    "leftIndicator",
    "rightIndicator",
    "lightOn",
    "lockActive",
    "charging"
)

internal fun isSemanticallyUsableAdaptiveRule(
    signal: String,
    status: String,
    confidence: Int,
    observations: Int,
    offset: Int,
    width: Int,
    encoding: String,
    scale: Double,
    bias: Double,
    activeValue: Long?,
    inactiveValue: Long?
): Boolean {
    if (!isActivatableAdaptiveRule(status, confidence) || observations <= 0) return false
    if (width !in 1..4 || offset !in 0..(4_096 - width)) return false
    if (!scale.isFinite() || !bias.isFinite()) return false
    return if (signal in ADAPTIVE_BOOLEAN_SIGNALS) {
        activeValue != null && inactiveValue != null && activeValue != inactiveValue &&
            rawValueFitsEncoding(activeValue, encoding) &&
            rawValueFitsEncoding(inactiveValue, encoding)
    } else {
        scale != 0.0
    }
}

/** Final consumer gate for both cloud-consensus and local-learning rules. */
internal fun isSafeAdaptiveRuleForActivation(
    signal: String,
    channel: String,
    status: String,
    confidence: Int,
    observations: Int,
    offset: Int,
    width: Int,
    encoding: String,
    scale: Double,
    bias: Double,
    activeValue: Long?,
    inactiveValue: Long?
): Boolean =
    VmaxDecoderPolicy.isAdaptiveRuleAllowed(signal, channel, offset, encoding) &&
        isSemanticallyUsableAdaptiveRule(
            signal = signal,
            status = status,
            confidence = confidence,
            observations = observations,
            offset = offset,
            width = width,
            encoding = encoding,
            scale = scale,
            bias = bias,
            activeValue = activeValue,
            inactiveValue = inactiveValue
        )

private fun rawValueFitsEncoding(value: Long, encoding: String): Boolean = when (encoding) {
    "u8" -> value in 0L..0xFFL
    "u16be", "u16le" -> value in 0L..0xFFFFL
    "s16be", "s16le" -> value in Short.MIN_VALUE.toLong()..Short.MAX_VALUE.toLong()
    "u32be", "u32le" -> value in 0L..0xFFFFFFFFL
    else -> false
}

internal fun readRoot(file: File): JSONObject? =
    if (!file.isFile) null else runCatching { JSONObject(file.readText()) }.getOrNull()

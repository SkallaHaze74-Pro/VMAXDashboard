package de.kevin.vmaxdashboard

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.round

data class AdaptiveProfileSnapshot(
    val revision: String = "",
    val ruleCount: Int = 0,
    val confirmedRuleCount: Int = 0,
    val generatedAtMs: Long = 0L,
    val source: String = "Noch kein KI-Profil",
    val signals: Set<String> = emptySet()
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
    val charging: Boolean? = null
)

private data class AdaptiveRule(
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
    val status: String,
    val source: String
)

class AdaptiveDecoderProfileStore private constructor(context: Context) {
    companion object {
        private const val CLOUD_SCHEMA = "vmax-adaptive-decoder-v1"
        private const val LOCAL_SCHEMA = "vmax-local-adaptive-decoder-v1"
        private val SUPPORTED_SIGNALS = setOf(
            "speedKmh",
            "batteryPercent",
            "voltageV",
            "currentA",
            "powerW",
            "motorTemperatureC",
            "batteryTemperatureC",
            "tripDistanceKm",
            "odometerKm",
            "brakeActive",
            "leftIndicator",
            "rightIndicator",
            "lightOn",
            "lockActive",
            "charging"
        )

        @Volatile private var instance: AdaptiveDecoderProfileStore? = null

        fun get(context: Context): AdaptiveDecoderProfileStore =
            instance ?: synchronized(this) {
                instance ?: AdaptiveDecoderProfileStore(context.applicationContext).also { instance = it }
            }
    }

    private val dir = File(context.filesDir, "adaptive_decoder").apply { mkdirs() }
    private val cloudFile = File(dir, "cloud_profile.json")
    private val localFile = File(dir, "local_profile.json")

    @Volatile private var activeRules: List<AdaptiveRule> = emptyList()
    @Volatile private var currentSnapshot = AdaptiveProfileSnapshot()

    init {
        rebuildCache()
    }

    @Synchronized
    fun installCloudProfile(json: String): AdaptiveProfileSnapshot {
        val root = JSONObject(json)
        require(root.optString("schema") == CLOUD_SCHEMA) { "Unbekanntes Decoder-AI-Profil" }
        val rules = root.optJSONArray("rules") ?: JSONArray()
        require(rules.length() <= 500) { "Decoder-AI-Profil enthält zu viele Regeln" }
        cloudFile.writeText(root.toString(2))
        rebuildCache()
        return currentSnapshot
    }

    @Synchronized
    fun importLocalLearning(learningJson: String): AdaptiveProfileSnapshot {
        val root = runCatching { JSONObject(learningJson) }.getOrNull() ?: return currentSnapshot
        val candidates = root.optJSONArray("candidates") ?: JSONArray()
        data class Evidence(
            val signal: String,
            val channel: String,
            val byteIndex: Int,
            val active: Long,
            val inactive: Long,
            val confidence: Int,
            val observations: Int,
            val label: String
        )

        val evidence = mutableListOf<Evidence>()
        for (index in 0 until candidates.length()) {
            val item = candidates.optJSONObject(index) ?: continue
            val label = item.optString("label")
            val signal = signalForLabel(label) ?: continue
            val channel = item.optString("channel").uppercase()
            val byteIndex = item.optInt("byteIndex", -1)
            if (channel.isBlank() || byteIndex < 0) continue
            val before = item.optLong("lastBefore", Long.MIN_VALUE)
            val after = item.optLong("lastAfter", Long.MIN_VALUE)
            if (before == Long.MIN_VALUE || after == Long.MIN_VALUE || before == after) continue
            val (active, inactive) = orientValues(label, before, after)
            evidence += Evidence(
                signal = signal,
                channel = channel,
                byteIndex = byteIndex,
                active = active,
                inactive = inactive,
                confidence = item.optInt("confidence", 0).coerceIn(0, 99),
                observations = item.optInt("observations", 1).coerceAtLeast(1),
                label = label
            )
        }

        val rules = JSONArray()
        evidence.groupBy { Triple(it.signal, it.channel, it.byteIndex) }.forEach { (key, items) ->
            val pairWeights = linkedMapOf<Pair<Long, Long>, Int>()
            var total = 0
            var weightedConfidence = 0
            items.forEach { item ->
                val weight = item.observations.coerceAtLeast(1)
                val pair = item.active to item.inactive
                pairWeights[pair] = (pairWeights[pair] ?: 0) + weight
                total += weight
                weightedConfidence += item.confidence * weight
            }
            val winner = pairWeights.maxByOrNull { it.value } ?: return@forEach
            val consistency = winner.value.toDouble() / total.coerceAtLeast(1)
            val confidence = if (total > 0) weightedConfidence / total else 0
            if (winner.value < 2 || confidence < 92 || consistency < 0.80) return@forEach
            rules.put(JSONObject().apply {
                put("id", "${key.first}:${key.second}:${key.third}:u8")
                put("signal", key.first)
                put("channel", key.second)
                put("offset", key.third)
                put("width", 1)
                put("encoding", "u8")
                put("activeValue", winner.key.first)
                put("inactiveValue", winner.key.second)
                put("confidence", confidence.coerceIn(0, 99))
                put("observations", total)
                put("status", "confirmed")
                put("source", "local-learning")
                put("labels", JSONArray(items.map { it.label }.distinct()))
            })
        }

        val localRoot = JSONObject().apply {
            put("schema", LOCAL_SCHEMA)
            put("revision", "local-${root.optLong("updatedAt", System.currentTimeMillis())}")
            put("generatedAtMs", root.optLong("updatedAt", System.currentTimeMillis()))
            put("rules", rules)
        }
        localFile.writeText(localRoot.toString(2))
        rebuildCache()
        return currentSnapshot
    }

    fun snapshot(): AdaptiveProfileSnapshot = currentSnapshot

    fun decode(channel: String, value: ByteArray): AdaptiveDecodedTelemetry {
        if (value.isEmpty()) return AdaptiveDecodedTelemetry()
        val matching = activeRules.filter { it.channel == channel.uppercase() }
            .sortedWith(compareByDescending<AdaptiveRule> { it.source == "cloud-consensus" }
                .thenByDescending { it.confidence }
                .thenByDescending { it.observations })
        if (matching.isEmpty()) return AdaptiveDecodedTelemetry()

        var batteryPercent: Int? = null
        var speedKmh: Double? = null
        var voltageV: Double? = null
        var currentA: Double? = null
        var powerW: Double? = null
        var motorTemperatureC: Double? = null
        var batteryTemperatureC: Double? = null
        var tripDistanceKm: Double? = null
        var odometerKm: Double? = null
        var brakeActive: Boolean? = null
        var leftIndicator: Boolean? = null
        var rightIndicator: Boolean? = null
        var lightOn: Boolean? = null
        var lockActive: Boolean? = null
        var charging: Boolean? = null

        matching.forEach { rule ->
            val raw = readRaw(rule, value) ?: return@forEach
            val booleanValue = when (raw) {
                rule.activeValue -> true
                rule.inactiveValue -> false
                else -> null
            }
            when (rule.signal) {
                "brakeActive" -> if (brakeActive == null) brakeActive = booleanValue
                "leftIndicator" -> if (leftIndicator == null) leftIndicator = booleanValue
                "rightIndicator" -> if (rightIndicator == null) rightIndicator = booleanValue
                "lightOn" -> if (lightOn == null) lightOn = booleanValue
                "lockActive" -> if (lockActive == null) lockActive = booleanValue
                "charging" -> if (charging == null) charging = booleanValue
                else -> {
                    val numeric = raw.toDouble() * rule.scale + rule.bias
                    if (!numeric.isFinite() || !plausible(rule.signal, numeric)) return@forEach
                    val rounded = round(numeric * 1000.0) / 1000.0
                    when (rule.signal) {
                        "batteryPercent" -> if (batteryPercent == null) batteryPercent = rounded.toInt().coerceIn(0, 100)
                        "speedKmh" -> if (speedKmh == null) speedKmh = rounded
                        "voltageV" -> if (voltageV == null) voltageV = rounded
                        "currentA" -> if (currentA == null) currentA = rounded
                        "powerW" -> if (powerW == null) powerW = rounded
                        "motorTemperatureC" -> if (motorTemperatureC == null) motorTemperatureC = rounded
                        "batteryTemperatureC" -> if (batteryTemperatureC == null) batteryTemperatureC = rounded
                        "tripDistanceKm" -> if (tripDistanceKm == null) tripDistanceKm = rounded
                        "odometerKm" -> if (odometerKm == null) odometerKm = rounded
                    }
                }
            }
        }

        return AdaptiveDecodedTelemetry(
            batteryPercent = batteryPercent,
            speedKmh = speedKmh,
            voltageV = voltageV,
            currentA = currentA,
            powerW = powerW,
            motorTemperatureC = motorTemperatureC,
            batteryTemperatureC = batteryTemperatureC,
            tripDistanceKm = tripDistanceKm,
            odometerKm = odometerKm,
            brakeActive = brakeActive,
            leftIndicator = leftIndicator,
            rightIndicator = rightIndicator,
            lightOn = lightOn,
            lockActive = lockActive,
            charging = charging
        )
    }

    @Synchronized
    private fun rebuildCache() {
        val cloudRoot = readRoot(cloudFile)
        val localRoot = readRoot(localFile)
        val cloudAll = parseRules(cloudRoot, "cloud-consensus")
        val localAll = parseRules(localRoot, "local-learning")
        val allRules = cloudAll + localAll
        val confirmed = allRules
            .filter { it.status == "confirmed" && it.confidence >= 92 && it.signal in SUPPORTED_SIGNALS }
            .groupBy { "${it.signal}|${it.channel}|${it.offset}|${it.encoding}" }
            .mapNotNull { (_, values) ->
                values.maxWithOrNull(compareBy<AdaptiveRule> { it.source == "cloud-consensus" }
                    .thenBy { it.confidence }
                    .thenBy { it.observations })
            }
        activeRules = confirmed

        val cloudRevision = cloudRoot?.optString("revision").orEmpty()
        val localRevision = localRoot?.optString("revision").orEmpty()
        val generatedAt = maxOf(
            cloudRoot?.optLong("generatedAtMs", 0L) ?: 0L,
            localRoot?.optLong("generatedAtMs", 0L) ?: 0L
        )
        currentSnapshot = AdaptiveProfileSnapshot(
            revision = cloudRevision.ifBlank { localRevision },
            ruleCount = allRules.size,
            confirmedRuleCount = confirmed.size,
            generatedAtMs = generatedAt,
            source = when {
                cloudAll.isNotEmpty() && localAll.isNotEmpty() -> "GitHub-Konsens + lokal"
                cloudAll.isNotEmpty() -> "GitHub-Konsens"
                localAll.isNotEmpty() -> "Lokales Lernen"
                else -> "Noch kein KI-Profil"
            },
            signals = confirmed.map { it.signal }.toSet()
        )
    }

    private fun readRoot(file: File): JSONObject? =
        if (!file.isFile) null else runCatching { JSONObject(file.readText()) }.getOrNull()

    private fun parseRules(root: JSONObject?, sourceOverride: String): List<AdaptiveRule> {
        val array = root?.optJSONArray("rules") ?: return emptyList()
        val out = mutableListOf<AdaptiveRule>()
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val signal = item.optString("signal")
            val channel = item.optString("channel").uppercase()
            val encoding = item.optString("encoding")
            val offset = item.optInt("offset", -1)
            val width = item.optInt("width", widthForEncoding(encoding))
            if (signal !in SUPPORTED_SIGNALS || channel.isBlank() || offset < 0 || width !in 1..4) continue
            if (encoding !in setOf("u8", "u16be", "u16le", "s16be", "s16le", "u32be", "u32le")) continue
            out += AdaptiveRule(
                id = item.optString("id", "$signal:$channel:$offset:$encoding"),
                signal = signal,
                channel = channel,
                offset = offset,
                width = width,
                encoding = encoding,
                scale = item.optDouble("scale", 1.0),
                bias = item.optDouble("bias", 0.0),
                activeValue = item.optLongOrNull("activeValue"),
                inactiveValue = item.optLongOrNull("inactiveValue"),
                confidence = item.optInt("confidence", 0).coerceIn(0, 99),
                observations = item.optInt("observations", 0).coerceAtLeast(0),
                status = item.optString("status", "candidate"),
                source = sourceOverride
            )
        }
        return out
    }

    private fun readRaw(rule: AdaptiveRule, value: ByteArray): Long? {
        if (rule.offset < 0 || rule.offset + rule.width > value.size) return null
        val bytes = value.copyOfRange(rule.offset, rule.offset + rule.width)
        if (bytes.all { (it.toInt() and 0xFF) == 0xFF }) return null
        fun u8(index: Int): Int = bytes[index].toInt() and 0xFF
        return when (rule.encoding) {
            "u8" -> u8(0).toLong()
            "u16be", "s16be" -> {
                val raw = (u8(0) shl 8) or u8(1)
                if (raw == 0xFFFF || raw == 0x8000) null
                else if (rule.encoding == "s16be") raw.toShort().toLong() else raw.toLong()
            }
            "u16le", "s16le" -> {
                val raw = u8(0) or (u8(1) shl 8)
                if (raw == 0xFFFF || raw == 0x8000) null
                else if (rule.encoding == "s16le") raw.toShort().toLong() else raw.toLong()
            }
            "u32be" -> ((u8(0).toLong() shl 24) or (u8(1).toLong() shl 16) or (u8(2).toLong() shl 8) or u8(3).toLong())
            "u32le" -> (u8(0).toLong() or (u8(1).toLong() shl 8) or (u8(2).toLong() shl 16) or (u8(3).toLong() shl 24))
            else -> null
        }
    }

    private fun plausible(signal: String, value: Double): Boolean = when (signal) {
        "speedKmh" -> value in 0.0..200.0
        "batteryPercent" -> value in 0.0..100.0
        "voltageV" -> value in 0.0..100.0
        "currentA" -> value in -200.0..200.0
        "powerW" -> value in -30000.0..30000.0
        "motorTemperatureC" -> value in -50.0..220.0
        "batteryTemperatureC" -> value in -50.0..150.0
        "tripDistanceKm", "odometerKm" -> value in 0.0..10000000.0
        else -> false
    }

    private fun signalForLabel(label: String): String? {
        val text = label.lowercase()
        return when {
            "blinker links" in text -> "leftIndicator"
            "blinker rechts" in text -> "rightIndicator"
            "bremse" in text -> "brakeActive"
            "licht" in text -> "lightOn"
            "ladegerät" in text || "laden" in text || "charging" in text -> "charging"
            "sperr" in text || "lock" in text -> "lockActive"
            else -> null
        }
    }

    private fun orientValues(label: String, before: Long, after: Long): Pair<Long, Long> {
        val text = label.lowercase()
        val reverse = ("licht" in text && "aus" in text && "an" !in text) ||
            (("ladegerät" in text || "laden" in text) && ("abziehen" in text || "aus" in text)) ||
            (("sperr" in text || "lock" in text) && ("aus" in text || "entsperr" in text || "unlock" in text))
        return if (reverse) before to after else after to before
    }

    private fun widthForEncoding(encoding: String): Int = when (encoding) {
        "u8" -> 1
        "u16be", "u16le", "s16be", "s16le" -> 2
        "u32be", "u32le" -> 4
        else -> 0
    }

    private fun JSONObject.optLongOrNull(name: String): Long? =
        if (!has(name) || isNull(name)) null else runCatching { getLong(name) }.getOrNull()
}

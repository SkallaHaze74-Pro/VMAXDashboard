package de.kevin.vmaxdashboard

enum class MotorTuningParameter(
    val id: Int,
    val label: String,
    val sdkMaximum: Int
) {
    MaxPower(0, "Maximale Motorleistung", 100),
    AssistFactor(1, "Unterstützungsfaktor", 100),
    DynamicFactor(2, "Dynamik / Ansprechverhalten", 100),
    SpeedCut(3, "Abregelgrenze", 100),
    MaxSpeed(4, "Maximalgeschwindigkeit (Rohwert)", 250),
    Cadence(5, "Kadenz", 100),
    TorqueHuman(6, "Fahrerdrehmoment", 100),
    BrakeCombined(7, "Kombinierte Bremse", 100),
    BrakeStatic(8, "Statische Bremse", 100),
    FreePushingTime(9, "Freischiebezeit", 100),
    SupportGain(10, "Unterstützungsverstärkung", 100);

    companion object {
        fun fromId(id: Int): MotorTuningParameter? = entries.firstOrNull { it.id == id }
    }
}

enum class MotorTuningProtocolMode(val label: String) {
    GPST_V9_SCOOTER("GPST v9 • Scooter"),
    GPST_V2("GPST v2"),
    GPST_LEGACY("GPST Legacy"),
    UNKNOWN("Unbekannt")
}

data class MotorTuningProfile(
    val index: Int,
    val values: Map<MotorTuningParameter, Int>,
    val wireOrder: List<MotorTuningParameter>,
    val rawHex: String
)

data class MotorTuningParseResult(
    val mode: MotorTuningProtocolMode,
    val profiles: List<MotorTuningProfile>,
    val frameHex: String
)

object MotorTuningProtocol {
    private val v9Order = listOf(
        MotorTuningParameter.MaxPower,
        MotorTuningParameter.AssistFactor,
        MotorTuningParameter.DynamicFactor,
        MotorTuningParameter.MaxSpeed,
        MotorTuningParameter.FreePushingTime,
        MotorTuningParameter.SupportGain
    )

    fun parseFrame(frame: ByteArray): MotorTuningParseResult? {
        val unsigned = frame.map { it.toInt() and 0xFF }
        val start = unsigned.indexOf(0xFD)
        val end = unsigned.indexOfFirst { it == 0xFE }
        if (start < 0 || end <= start) return null

        val segments = mutableListOf<List<Int>>()
        var current = mutableListOf<Int>()
        for (index in (start + 1) until end) {
            val value = unsigned[index]
            if (value == 0xFD) {
                if (current.isNotEmpty()) segments += current.toList()
                current = mutableListOf()
            } else {
                current += value
            }
        }
        if (current.isNotEmpty()) segments += current.toList()
        if (segments.isEmpty()) return null

        val mode = detectMode(segments)
        val profiles = segments.mapIndexedNotNull { segmentIndex, segment ->
            val order = orderFor(mode, segment.size)
            if (order.isEmpty()) return@mapIndexedNotNull null
            val values = buildMap {
                order.forEachIndexed { valueIndex, parameter ->
                    val value = segment.getOrNull(valueIndex) ?: 0xFF
                    if (value != 0xFF) put(parameter, value)
                }
            }
            MotorTuningProfile(
                index = segmentIndex + 1,
                values = values,
                wireOrder = order,
                rawHex = segment.toHex()
            )
        }
        if (profiles.isEmpty()) return null
        return MotorTuningParseResult(mode, profiles, unsigned.subList(start, end + 1).toHex())
    }

    fun buildWritePacket(
        profile: MotorTuningProfile,
        values: Map<MotorTuningParameter, Int>,
        mode: MotorTuningProtocolMode
    ): ByteArray {
        val wireIndex = when (mode) {
            MotorTuningProtocolMode.GPST_V9_SCOOTER -> profile.index - 1
            else -> profile.index
        }.coerceIn(0, 255)

        val merged = profile.values.toMutableMap().apply { putAll(values) }
        val highestTypeId = merged.keys.maxOfOrNull { it.id } ?: 0
        return buildList {
            add(wireIndex)
            for (typeId in 0..highestTypeId) {
                val parameter = MotorTuningParameter.fromId(typeId)
                add(parameter?.let { merged[it]?.coerceIn(0, it.sdkMaximum) } ?: 0xFF)
            }
        }.map { it.toByte() }.toByteArray()
    }

    fun buildResetPacket(profileIndex: Int, mode: MotorTuningProtocolMode): ByteArray {
        // The original native ResetMotorTuning implementation writes the supplied
        // model index directly; unlike SetMotorTuning it does not apply the v9 -1 adjustment.
        val wireIndex = profileIndex.coerceIn(0, 255)
        return byteArrayOf(wireIndex.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte())
    }

    fun packetHex(packet: ByteArray): String = packet.map { it.toInt() and 0xFF }.toHex()

    private fun detectMode(segments: List<List<Int>>): MotorTuningProtocolMode {
        val sizes = segments.map { it.size }
        return when {
            sizes.all { it == 6 } -> MotorTuningProtocolMode.GPST_V9_SCOOTER
            sizes.all { it in 7..8 } -> MotorTuningProtocolMode.GPST_V2
            sizes.all { it >= 9 } -> MotorTuningProtocolMode.GPST_LEGACY
            else -> MotorTuningProtocolMode.UNKNOWN
        }
    }

    private fun orderFor(mode: MotorTuningProtocolMode, size: Int): List<MotorTuningParameter> = when (mode) {
        MotorTuningProtocolMode.GPST_V9_SCOOTER -> v9Order.take(if (size > 0) size.coerceAtMost(v9Order.size) else v9Order.size)
        MotorTuningProtocolMode.GPST_V2,
        MotorTuningProtocolMode.GPST_LEGACY,
        MotorTuningProtocolMode.UNKNOWN -> (0 until (if (size > 0) size else 11))
            .mapNotNull(MotorTuningParameter::fromId)
    }

    private fun List<Int>.toHex(): String = joinToString("-") { "%02X".format(it and 0xFF) }
}

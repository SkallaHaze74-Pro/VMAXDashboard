package de.kevin.vmaxdashboard

/**
 * Safety rules shared by local learning and cloud-provided decoder profiles.
 * Known SDK layouts may be confirmed by ride data, but never reinterpreted with
 * a different signedness/offset or rediscovered as an accessory switch.
 */
internal object VmaxDecoderPolicy {
    private data class Layout(val offset: Int, val encoding: String)

    private val canonicalLayouts = mapOf(
        ("speedKmh" to "1505") to Layout(6, "u16be"),
        ("batteryPercent" to "1509") to Layout(4, "u8"),
        ("voltageV" to "1509") to Layout(5, "u16be"),
        ("currentA" to "1509") to Layout(0, "s16be"),
        ("powerW" to "1509") to Layout(9, "u16be"),
        ("odometerKm" to "1506") to Layout(0, "u32be")
    )

    private val forbiddenAdaptiveMappings = setOf("speedKmh" to "150D")
    private val forbiddenAdaptiveLayouts = setOf(
        Triple("powerW", "1509", Layout(9, "u16be"))
    )
    private val forbiddenAdaptiveSignals = setOf("charging")
    private val diagnosticOnlyChannels = setOf("1514", "1516", "1517", "1518", "2A25")
    private val booleanSignals = setOf(
        "leftIndicator", "rightIndicator", "brakeActive", "lightOn", "charging", "lockActive"
    )
    private val blockedAdaptiveBooleanChannels = setOf(
        "1505", "1506", "1509", "150A", "150C", "150D",
        "1514", "1516", "1517", "1518",
        "2A00", "2A01", "2A02", "2A04", "2A05", "2A25", "2A28"
    )
    private val blockedLearningChannels = setOf(
        "1505", "1506", "1509", "150A", "150C",
        "1514", "1516", "1517", "1518",
        "2A00", "2A01", "2A02", "2A04", "2A05", "2A25", "2A28"
    )

    fun isAdaptiveRuleAllowed(signal: String, channel: String, offset: Int, encoding: String): Boolean {
        val key = signal to channel.uppercase()
        // Charger-induced BLE loss and an ordinary reconnect cannot establish a
        // live charging bit. Keep this signal disabled until a direct field is
        // independently identified and deliberately added as a canonical layout.
        if (signal in forbiddenAdaptiveSignals) return false
        if (key.second in diagnosticOnlyChannels) return false
        if (key in forbiddenAdaptiveMappings) return false
        if (Triple(signal, key.second, Layout(offset, encoding)) in forbiddenAdaptiveLayouts) return false
        if (signal in booleanSignals) {
            if (key.second in blockedAdaptiveBooleanChannels) return false
            if (key.second == "1508") {
                return signal == "lightOn" && offset == 0 && encoding == "u8"
            }
        }
        val canonicalChannel = canonicalLayouts.keys.firstOrNull { it.first == signal }?.second
        if (canonicalChannel != null && key.second != canonicalChannel) return false
        val canonical = canonicalLayouts[key] ?: return true
        return canonical.offset == offset && canonical.encoding == encoding
    }

    fun isSuspiciousReadLikePayload(channel: String, value: ByteArray): Boolean {
        if (channel.uppercase() != "1505" || value.size < 18) return false
        return value.drop(8).count { byte -> (byte.toInt() and 0xFF) in 0x20..0x7E } >= 6
    }

    fun isLearningCandidateAllowed(label: String, channel: String, byteIndex: Int): Boolean {
        if (byteIndex < 0) return false
        val normalizedChannel = channel.uppercase()
        if (normalizedChannel in blockedLearningChannels) return false
        return when (normalizedChannel) {
            "1508" -> when (byteIndex) {
                0 -> label.contains("Licht", ignoreCase = true)
                3 -> label.contains("Fahrmodus", ignoreCase = true) ||
                    label.contains("ECO", ignoreCase = true) ||
                    label.contains("SPORT", ignoreCase = true)
                else -> true
            }
            // Six rides show that 150D is a persisted ride-statistics block. The
            // BT638 can temporarily replace bytes 2..19 with 0xFF while keeping
            // 0..1 at zero; those unavailable frames must never become switches.
            "150D" -> false
            else -> true
        }
    }
}

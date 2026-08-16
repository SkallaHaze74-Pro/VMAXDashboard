package de.kevin.vmaxdashboard

enum class VmaxStartMode(val rawValue: Int, val label: String) {
    ZERO_START(0, "Zero-Start"),
    KICK_START(1, "Kick-Start");

    companion object {
        fun fromRaw(value: Int?): VmaxStartMode? = entries.firstOrNull { it.rawValue == value }
    }
}

/**
 * Original-VMAX protocol mapping for the legacy GPST v10 BT638 layout.
 *
 * Original APK path: LEV_SET_INITIAL_ASSIST_STATE (20) ->
 * GPSTProtocolDataManager.writeUserMessageCharacteristic(7, 0|1).
 * Native SDK possible values are ordered ZeroStart, KickStart. The legacy
 * message is eight bytes filled with 0xFF and the selected value at byte 7.
 */
internal object VmaxStartModeProtocol {
    const val LIVE_PACKET_LENGTH = 17
    const val LIVE_VALUE_OFFSET = 11
    const val WRITE_PACKET_LENGTH = 8
    const val WRITE_VALUE_OFFSET = 7

    fun decodeLive1508(value: ByteArray): VmaxStartMode? {
        if (!hasObservedLegacyNotificationFingerprint(value)) return null
        return VmaxStartMode.fromRaw(value[LIVE_VALUE_OFFSET].toInt() and 0xFF)
    }

    /**
     * Exact BT638 notification shape observed across the recorded rides.
     * Shorter READ/diagnostic responses deliberately do not confirm the
     * legacy write route even when byte 11 happens to contain zero or one.
     */
    private fun hasObservedLegacyNotificationFingerprint(value: ByteArray): Boolean {
        if (value.size != LIVE_PACKET_LENGTH) return false
        fun u8(index: Int): Int = value[index].toInt() and 0xFF
        if (u8(0) !in 0..1 || u8(1) != 0 || u8(2) != 0 || u8(3) !in 1..2) return false
        if ((4..10).any { u8(it) != 0xFF }) return false
        if ((12..16).any { u8(it) != 0 }) return false
        return u8(LIVE_VALUE_OFFSET) in 0..1
    }

    fun buildLegacyWriteFrame(mode: VmaxStartMode): ByteArray =
        ByteArray(WRITE_PACKET_LENGTH) { 0xFF.toByte() }.also {
            it[WRITE_VALUE_OFFSET] = mode.rawValue.toByte()
        }
}

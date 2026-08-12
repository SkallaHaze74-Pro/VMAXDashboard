package de.kevin.vmaxdashboard

import kotlin.math.round

/**
 * Read-only decoder for fields whose byte layout is known from the original VMAX/Hyena
 * libble native parser. This layer never writes to GATT. It is intentionally separate
 * from the adaptive decoder: SDK-known bytes are ground truth and must not be relearned
 * as switches or accessories.
 */
data class OriginalSdkRealtimeSnapshot(
    val performancePowerAW: Double? = null,
    val performancePowerBW: Double? = null,
    val performanceTorqueNm: Double? = null,
    val performanceSpeedKmh: Double? = null,
    val performanceRpm: Int? = null,
    val performanceDistanceRaw: Int? = null,
    val odometerKm: Double? = null,
    val operatingCounterRaw: Long? = null,
    val batteryCurrentA: Double? = null,
    val batteryTemperatureC: Double? = null,
    val batteryPercent: Int? = null,
    val batteryVoltageV: Double? = null,
    val secondaryBatteryCurrentA: Double? = null,
    val directPowerW: Double? = null,
    val motorCurrentA: Double? = null,
    val motorVoltageV: Double? = null,
    val motorRpm: Int? = null,
    val motorTorqueNm: Double? = null,
    val motorTemperatureC: Double? = null,
    val lightOn: Boolean? = null,
    val assistanceLevelRaw: Int? = null
) {
    val availableFieldCount: Int
        get() = listOf(
            performancePowerAW, performancePowerBW, performanceTorqueNm, performanceSpeedKmh,
            performanceRpm, performanceDistanceRaw, odometerKm, operatingCounterRaw,
            batteryCurrentA, batteryTemperatureC, batteryPercent, batteryVoltageV,
            secondaryBatteryCurrentA, directPowerW, motorCurrentA, motorVoltageV,
            motorRpm, motorTorqueNm, motorTemperatureC, lightOn, assistanceLevelRaw
        ).count { it != null }
}

object OriginalSdkRealtimeDecoder {
    fun decodePackets(rawPackets: Map<String, String>): OriginalSdkRealtimeSnapshot {
        val p1505 = parseHex(rawPackets["1505"])
        val p1506 = parseHex(rawPackets["1506"])
        val p1508 = parseHex(rawPackets["1508"])
        val p1509 = parseHex(rawPackets["1509"])
        val p150a = parseHex(rawPackets["150A"])

        val powerA = validU16BE(p1505, 0)?.let { rounded(it / 10.0) }
        val powerB = validU16BE(p1505, 2)?.let { rounded(it / 10.0) }
        val torque = validU16BE(p1505, 4)?.let { rounded(it / 100.0) }
        val speed = validU16BE(p1505, 6)?.let { rounded(it / 10.0) }
        val rpm = validU16BE(p1505, 8)?.takeIf { it in 0..50_000 }
        val distanceRaw = validU16BE(p1505, 10)

        val odometer = u32BE(p1506, 0)
            ?.takeIf { it in 0..100_000_000L }
            ?.let { rounded(it / 10.0) }
        val operatingCounter = u32BE(p1506, 4)
            ?.takeUnless { it == 0xFFFFFFFFL || it == 0x80000000L }

        val batteryCurrent = validS16BE(p1509, 0)
            ?.let { rounded(it / 1000.0) }
            ?.takeIf { it in -300.0..300.0 }
        val batteryTemp = validS16BE(p1509, 2)
            ?.let { rounded(it / 10.0) }
            ?.takeIf { it in -50.0..150.0 }
        val soc = u8(p1509, 4)?.takeIf { it in 0..100 }
        val batteryVoltage = validU16BE(p1509, 5)
            ?.let { rounded(it / 1000.0) }
            ?.takeIf { it in 0.0..100.0 }
        val secondaryCurrent = validS16BE(p1509, 7)
            ?.let { rounded(it / 1000.0) }
            ?.takeIf { it in -300.0..300.0 }
        val directPower = validU16BE(p1509, 9)
            ?.toDouble()
            ?.takeIf { it in 0.0..30_000.0 }

        val motorCurrent = validS16BE(p150a, 0)
            ?.let { rounded(it / 1000.0) }
            ?.takeIf { it in -300.0..300.0 }
        val motorVoltage = validU16BE(p150a, 2)
            ?.let { rounded(it / 1000.0) }
            ?.takeIf { it in 0.0..100.0 }
        val motorRpm = validU16BE(p150a, 4)?.takeIf { it in 0..50_000 }
        val motorTorque = validS16BE(p150a, 6)
            ?.let { rounded(it / 100.0) }
            ?.takeIf { it in -500.0..500.0 }
        val motorTemp = validS16BE(p150a, 8)
            ?.let { rounded(it / 10.0) }
            ?.takeIf { it in -50.0..220.0 }

        val lightByte = u8(p1508, 0)
        val light = when (lightByte) {
            0 -> false
            1 -> true
            else -> null
        }
        val assistance = u8(p1508, 3)

        return OriginalSdkRealtimeSnapshot(
            performancePowerAW = powerA,
            performancePowerBW = powerB,
            performanceTorqueNm = torque,
            performanceSpeedKmh = speed,
            performanceRpm = rpm,
            performanceDistanceRaw = distanceRaw,
            odometerKm = odometer,
            operatingCounterRaw = operatingCounter,
            batteryCurrentA = batteryCurrent,
            batteryTemperatureC = batteryTemp,
            batteryPercent = soc,
            batteryVoltageV = batteryVoltage,
            secondaryBatteryCurrentA = secondaryCurrent,
            directPowerW = directPower,
            motorCurrentA = motorCurrent,
            motorVoltageV = motorVoltage,
            motorRpm = motorRpm,
            motorTorqueNm = motorTorque,
            motorTemperatureC = motorTemp,
            lightOn = light,
            assistanceLevelRaw = assistance
        )
    }

    private fun parseHex(text: String?): ByteArray {
        if (text.isNullOrBlank()) return byteArrayOf()
        return runCatching {
            text.split('-', ' ', ':')
                .mapNotNull { part -> part.trim().takeIf { it.length == 2 }?.toIntOrNull(16) }
                .map(Int::toByte)
                .toByteArray()
        }.getOrDefault(byteArrayOf())
    }

    private fun u8(value: ByteArray, index: Int): Int? =
        if (index in value.indices) value[index].toInt() and 0xFF else null

    private fun u16BE(value: ByteArray, index: Int): Int? =
        if (index + 1 < value.size) ((value[index].toInt() and 0xFF) shl 8) or (value[index + 1].toInt() and 0xFF) else null

    private fun validU16BE(value: ByteArray, index: Int): Int? =
        u16BE(value, index)?.takeUnless { it == 0xFFFF || it == 0x8000 }

    private fun validS16BE(value: ByteArray, index: Int): Int? {
        val raw = u16BE(value, index) ?: return null
        if (raw == 0xFFFF || raw == 0x8000) return null
        return raw.toShort().toInt()
    }

    private fun u32BE(value: ByteArray, index: Int): Long? =
        if (index + 3 < value.size) {
            ((value[index].toLong() and 0xFF) shl 24) or
                ((value[index + 1].toLong() and 0xFF) shl 16) or
                ((value[index + 2].toLong() and 0xFF) shl 8) or
                (value[index + 3].toLong() and 0xFF)
        } else null

    private fun rounded(value: Double): Double = round(value * 1000.0) / 1000.0
}

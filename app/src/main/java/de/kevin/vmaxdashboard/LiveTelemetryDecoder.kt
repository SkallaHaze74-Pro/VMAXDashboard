package de.kevin.vmaxdashboard

import kotlin.math.round

data class DecodedTelemetry(
    val batteryPercent: Int? = null,
    val speedKmh: Double? = null,
    val driveRaw: Int? = null,
    val motorLoadRaw: Int? = null,
    val batteryStateRaw: Int? = null,
    val accessoryByte0: Int? = null,
    val accessoryByte3: Int? = null,
    val lightOn: Boolean? = null,
    val voltageV: Double? = null,
    val currentA: Double? = null,
    val motorTemperatureC: Double? = null,
    val batteryTemperatureC: Double? = null,
    val tripDistanceKm: Double? = null,
    val odometerKm: Double? = null,
    val notes: List<String> = emptyList()
)

object LiveTelemetryDecoder {
    fun decode(channel: String, value: ByteArray): DecodedTelemetry {
        if (value.isEmpty()) return DecodedTelemetry()

        val notes = mutableListOf<String>()
        var batteryPercent: Int? = null
        var speedKmh: Double? = null
        var driveRaw: Int? = null
        var motorLoadRaw: Int? = null
        var accessoryByte0: Int? = null
        var accessoryByte3: Int? = null
        var lightOn: Boolean? = null
        var voltageV: Double? = null
        var currentA: Double? = null
        var odometerKm: Double? = null

        when (channel) {
            "1502" -> {
                validUnsigned16BE(value, 0)?.let { notes += "Akku-Info RAW A: $it" }
                validUnsigned16BE(value, 6)?.let { notes += "Akku-Info RAW B: $it" }
                if (isAllPlaceholder(value)) notes += "Akku-Infoblock enthält nur Platzhalter"
            }

            "1505" -> {
                validUnsigned16BE(value, 0)?.let { notes += "Fahrleistungswert A RAW: $it" }
                validUnsigned16BE(value, 2)?.let { notes += "Fahrleistungswert B RAW: $it" }
                validUnsigned16BE(value, 6)?.let { raw ->
                    driveRaw = raw
                    speedKmh = rounded(raw / 10.0)
                    notes += "Geschwindigkeit: 1505 Byte 6-7, Big Endian, /10 km/h"
                }
                validUnsigned16BE(value, 10)?.let { notes += "1505 Byte 10-11 RAW: $it" }
            }

            "1506" -> {
                unsigned32BE(value, 0)
                    ?.takeIf { it in 0..100_000_000L }
                    ?.let { raw ->
                        odometerKm = rounded(raw / 10.0)
                        notes += "Kilometerstand: 1506 Byte 0-3, Big Endian, /10 km"
                    }
                unsigned32BE(value, 4)?.let { raw ->
                    notes += "Betriebs-/Fahrzeitzähler RAW: $raw; Skalierung offen"
                }
            }

            "1508" -> {
                accessoryByte0 = u8OrNull(value, 0)
                accessoryByte3 = u8OrNull(value, 3)
                lightOn = when (accessoryByte0) {
                    0 -> false
                    1 -> true
                    else -> null
                }
                notes += "1508 Byte 0: 0=Licht aus, 1=Licht an; Byte 3=Fahrstufe RAW"
            }

            "1509" -> {
                signed16BE(value, 0)?.let { raw ->
                    currentA = rounded(raw / 1000.0)
                    notes += "Akkustrom: 1509 Byte 0-1, mA"
                }
                u8OrNull(value, 4)?.takeIf { it in 0..100 }?.let { percent ->
                    batteryPercent = percent
                    notes += "Akkustand: 1509 Byte 4"
                }
                validUnsigned16BE(value, 5)?.let { raw ->
                    voltageV = rounded(raw / 1000.0)
                    notes += "Akkuspannung: 1509 Byte 5-6, mV"
                }
                validUnsigned16BE(value, 9)?.let { raw ->
                    motorLoadRaw = raw
                    notes += "Direkte Leistung: 1509 Byte 9-10, W"
                }
            }

            "150A" -> {
                validUnsigned16BE(value, 0)?.let { notes += "Motorstrom-/Last-Kandidat RAW: $it" }
                if (value.size > 2 && value.drop(2).all { (it.toInt() and 0xFF) == 0xFF }) {
                    notes += "150A Byte 2-${value.lastIndex} werden vom BT638 aktuell nicht geliefert"
                }
            }

            "150B" -> {
                if (isAllPlaceholder(value)) notes += "150B wird vom BT638 aktuell nicht unterstützt (nur 0xFF)"
            }

            "150D" -> {
                validUnsigned16BE(value, 0)?.let { raw ->
                    speedKmh = rounded(raw / 10.0)
                    notes += "Zweite bestätigte Geschwindigkeit: 150D Byte 0-1, /10 km/h"
                }
                validUnsigned16BE(value, 2)?.let { notes += "150D Byte 2-3 Statistikwert RAW: $it" }
            }
        }

        return DecodedTelemetry(
            batteryPercent = batteryPercent,
            speedKmh = speedKmh,
            driveRaw = driveRaw,
            motorLoadRaw = motorLoadRaw,
            accessoryByte0 = accessoryByte0,
            accessoryByte3 = accessoryByte3,
            lightOn = lightOn,
            voltageV = voltageV,
            currentA = currentA,
            odometerKm = odometerKm,
            notes = notes
        )
    }

    fun unsigned16LE(value: ByteArray, index: Int): Int? =
        if (index + 1 < value.size) u8(value, index) or (u8(value, index + 1) shl 8) else null

    fun unsigned16BE(value: ByteArray, index: Int): Int? =
        if (index + 1 < value.size) (u8(value, index) shl 8) or u8(value, index + 1) else null

    fun unsigned32LE(value: ByteArray, index: Int): Long? =
        if (index + 3 < value.size) {
            u8(value, index).toLong() or
                (u8(value, index + 1).toLong() shl 8) or
                (u8(value, index + 2).toLong() shl 16) or
                (u8(value, index + 3).toLong() shl 24)
        } else null

    fun unsigned32BE(value: ByteArray, index: Int): Long? =
        if (index + 3 < value.size) {
            (u8(value, index).toLong() shl 24) or
                (u8(value, index + 1).toLong() shl 16) or
                (u8(value, index + 2).toLong() shl 8) or
                u8(value, index + 3).toLong()
        } else null

    fun scaled(raw: Number, divisor: Double): Double = rounded(raw.toDouble() / divisor)

    private fun validUnsigned16BE(value: ByteArray, index: Int): Int? {
        val raw = unsigned16BE(value, index) ?: return null
        return raw.takeUnless { it == 0xFFFF || it == 0x8000 }
    }

    private fun signed16BE(value: ByteArray, index: Int): Int? {
        val raw = unsigned16BE(value, index) ?: return null
        if (raw == 0xFFFF || raw == 0x8000) return null
        return raw.toShort().toInt()
    }

    private fun isAllPlaceholder(value: ByteArray): Boolean =
        value.isNotEmpty() && value.all { (it.toInt() and 0xFF) == 0xFF }

    private fun u8OrNull(value: ByteArray, index: Int): Int? =
        if (index in value.indices) u8(value, index) else null

    private fun rounded(value: Double): Double = round(value * 100.0) / 100.0

    private fun u8(value: ByteArray, index: Int): Int = value[index].toInt() and 0xFF
}

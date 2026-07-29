package de.kevin.vmaxdashboard

import kotlin.math.round

/**
 * Bereits dekodierte Livewerte. Unbestätigte Felder bleiben null und die
 * vollständigen Rohpakete werden weiterhin separat gespeichert.
 */
data class DecodedTelemetry(
    val batteryPercent: Int? = null,
    val speedKmh: Double? = null,
    val driveRaw: Int? = null,
    val motorLoadRaw: Int? = null,
    val batteryStateRaw: Int? = null,
    val accessoryByte0: Int? = null,
    val accessoryByte3: Int? = null,
    val voltageV: Double? = null,
    val currentA: Double? = null,
    val motorTemperatureC: Double? = null,
    val batteryTemperatureC: Double? = null,
    val tripDistanceKm: Double? = null,
    val odometerKm: Double? = null,
    val notes: List<String> = emptyList()
)

/**
 * Decoder für die GPST/VMAX-Telemetrie.
 *
 * Die Belegung von 1505, 1506, 1508, 1509, 150A und 150C wurde mit der
 * nativen GPST-Bibliothek und BT638-Messungen abgeglichen. Werte mit dem
 * Protokollplatzhalter 0xFFFF bzw. 0x8000 werden nicht als Messwert angezeigt.
 */
object LiveTelemetryDecoder {
    fun decode(channel: String, value: ByteArray): DecodedTelemetry {
        if (value.isEmpty()) return DecodedTelemetry()

        val notes = mutableListOf<String>()
        var batteryPercent: Int? = null
        var speedKmh: Double? = null
        var driveRaw: Int? = null
        var motorLoadRaw: Int? = null
        var batteryStateRaw: Int? = null
        var accessoryByte0: Int? = null
        var accessoryByte3: Int? = null
        var voltageV: Double? = null
        var currentA: Double? = null
        var motorTemperatureC: Double? = null
        var batteryTemperatureC: Double? = null
        var tripDistanceKm: Double? = null
        var odometerKm: Double? = null

        when (channel) {
            "1505" -> {
                // BikePerformance: W/10, W/10, Nm/100, km/h/10, rpm, km.
                validUnsigned16BE(value, 6)?.let { raw ->
                    driveRaw = raw
                    speedKmh = rounded(raw / 10.0)
                    notes += "Geschwindigkeit: 1505 Byte 6-7, Big Endian, /10 km/h"
                }
                validUnsigned16BE(value, 10)?.let { raw ->
                    tripDistanceKm = raw.toDouble()
                    notes += "Streckenfeld: 1505 Byte 10-11 (SDK-Einheit km)"
                }
            }

            "1506" -> {
                // BT638 bestätigt: 00001A8E = 6798 -> 679,8 km.
                unsigned32BE(value, 0)?.takeIf { it in 0..100_000_000L }?.let { raw ->
                    odometerKm = rounded(raw / 10.0)
                    notes += "Kilometerstand: 1506 Byte 0-3, Big Endian, /10 km"
                }
                unsigned32BE(value, 4)?.takeIf { it in 0..100_000_000L }?.let { seconds ->
                    notes += "Gesamtfahr-/Betriebszeit: 1506 Byte 4-7, $seconds s"
                }
            }

            "1508" -> {
                accessoryByte0 = u8(value, 0)
                if (value.size >= 4) accessoryByte3 = u8(value, 3)
                notes += "1508 Byte 0: 0=Licht aus, 1=Licht an; Byte 3=Fahrstufe"
            }

            "1509" -> {
                // BatteryUpdate: current, battery temperature, SoC, voltage,
                // second current value and direct power.
                signed16BE(value, 0)?.let { raw ->
                    currentA = rounded(raw / 1000.0)
                    notes += "Akkustrom: 1509 Byte 0-1, mA"
                }
                temperatureTenths(value, 2)?.let { temp ->
                    batteryTemperatureC = temp
                    notes += "Akkutemperatur: 1509 Byte 2-3, /10 °C"
                }
                if (value.size >= 5) {
                    val percent = u8(value, 4)
                    if (percent in 0..100) {
                        batteryPercent = percent
                        notes += "Akkustand: 1509 Byte 4"
                    }
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
                // MotorUpdate: current mA, voltage mV, rpm, torque Nm/100,
                // motor temperature °C/10.
                temperatureTenths(value, 8)?.let { temp ->
                    motorTemperatureC = temp
                    notes += "Motortemperatur: 150A Byte 8-9, /10 °C"
                }
                validUnsigned16BE(value, 0)?.let { notes += "Motorstrom RAW: $it mA" }
                validUnsigned16BE(value, 2)?.let { notes += "Motorspannung RAW: $it mV" }
                validUnsigned16BE(value, 4)?.let { notes += "Motordrehzahl RAW: $it rpm" }
            }

            "150C" -> {
                // BatteryCellUpdate: index, cell voltage, three temperatures.
                temperatureTenths(value, 3)?.let { temp ->
                    batteryTemperatureC = temp
                    notes += "Zell-/Akkutemperatur 1: 150C Byte 3-4, /10 °C"
                }
                if (value.size >= 3) {
                    val index = u8(value, 0)
                    validUnsigned16BE(value, 1)?.let { millivolts ->
                        notes += "Zelle/Sensor $index: $millivolts mV"
                    }
                }
            }
        }

        return DecodedTelemetry(
            batteryPercent = batteryPercent,
            speedKmh = speedKmh,
            driveRaw = driveRaw,
            motorLoadRaw = motorLoadRaw,
            batteryStateRaw = batteryStateRaw,
            accessoryByte0 = accessoryByte0,
            accessoryByte3 = accessoryByte3,
            voltageV = voltageV,
            currentA = currentA,
            motorTemperatureC = motorTemperatureC,
            batteryTemperatureC = batteryTemperatureC,
            tripDistanceKm = tripDistanceKm,
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
        return raw.takeUnless { it == 0xFFFF }
    }

    private fun signed16BE(value: ByteArray, index: Int): Int? {
        val raw = unsigned16BE(value, index) ?: return null
        return raw.toShort().toInt()
    }

    private fun temperatureTenths(value: ByteArray, index: Int): Double? {
        val raw = unsigned16BE(value, index) ?: return null
        if (raw == 0xFFFF || raw == 0x8000) return null
        val temperature = raw.toShort().toInt() / 10.0
        return rounded(temperature).takeIf { it in -50.0..180.0 }
    }

    private fun rounded(value: Double): Double = round(value * 100.0) / 100.0

    private fun u8(value: ByteArray, index: Int): Int = value[index].toInt() and 0xFF
}

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
    val voltageV: Double? = null,
    val currentA: Double? = null,
    val motorTemperatureC: Double? = null,
    val batteryTemperatureC: Double? = null,
    val tripDistanceKm: Double? = null,
    val odometerKm: Double? = null,
    val notes: List<String> = emptyList()
)

/**
 * Konservativer Live-Decoder aus zwei BT638-Prüfstandsmessungen.
 *
 * Belastbar:
 * - 1509 Byte 4 = Akku in Prozent.
 *
 * Starke Kandidaten, bewusst als Rohwerte angezeigt:
 * - 1505 Byte 7 = fahr-/drehzahlabhängiger Wert (0 im Stillstand).
 * - 1509 bzw. 150A Byte 0..1 Big Endian = Motor-/Last-Rohwert.
 * - 1509 Byte 6 = Akku-/Last-Zustandsstufe.
 * - 1508 Byte 0 und 3 = Zubehörstatus-Kandidaten.
 *
 * Spannung, Strom und Leistung werden erst wieder aktiviert, wenn ihre
 * Skalierung durch weitere Referenzmessungen bestätigt ist.
 */
object LiveTelemetryDecoder {
    fun decode(channel: String, value: ByteArray): DecodedTelemetry {
        if (value.isEmpty()) return DecodedTelemetry()
        val notes = mutableListOf<String>()

        var battery: Int? = null
        var speed: Double? = null
        var driveRaw: Int? = null
        var motorLoadRaw: Int? = null
        var batteryStateRaw: Int? = null
        var accessory0: Int? = null
        var accessory3: Int? = null

        if (channel == "1505" && value.size >= 8) {
            driveRaw = u8(value, 7)
            // Vorläufige Skala für die gut lesbare Live-Anzeige. Der Rohwert
            // bleibt daneben sichtbar und wird in der CSV gespeichert.
            speed = rounded(driveRaw / 10.0)
            notes += "Fahrwert: 1505 Byte 7 (Skalierung /10 noch zu bestätigen)"
        }

        if ((channel == "1509" || channel == "150A") && value.size >= 2) {
            motorLoadRaw = unsigned16BE(value, 0)
            notes += "Motor/Last RAW: $channel Byte 0-1"
        }

        if (channel == "1509") {
            if (value.size >= 5) {
                val percent = u8(value, 4)
                if (percent in 0..100) {
                    battery = percent
                    notes += "Akku: 1509 Byte 4"
                }
            }
            if (value.size >= 7) {
                batteryStateRaw = u8(value, 6)
                notes += "Akku/Last-Stufe RAW: 1509 Byte 6"
            }
        }

        if (channel == "1508") {
            accessory0 = u8(value, 0)
            if (value.size >= 4) accessory3 = u8(value, 3)
            notes += "Zubehör RAW: 1508 Byte 0/3"
        }

        return DecodedTelemetry(
            batteryPercent = battery,
            speedKmh = speed,
            driveRaw = driveRaw,
            motorLoadRaw = motorLoadRaw,
            batteryStateRaw = batteryStateRaw,
            accessoryByte0 = accessory0,
            accessoryByte3 = accessory3,
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

    fun scaled(raw: Number, divisor: Double): Double = rounded(raw.toDouble() / divisor)

    private fun rounded(value: Double): Double = round(value * 100.0) / 100.0
    private fun u8(value: ByteArray, index: Int): Int = value[index].toInt() and 0xFF
}

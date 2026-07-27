package de.kevin.vmaxdashboard

import kotlin.math.round

data class DecodedTelemetry(
    val batteryPercent: Int? = null,
    val speedKmh: Double? = null,
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
        var battery: Int? = null

        // Aus den bisherigen echten Messungen bestätigt: 1509 Byte 4 enthält plausible 0..100 Werte.
        if (channel == "1509" && value.size >= 5) {
            val percent = u8(value, 4)
            if (percent in 0..100) {
                battery = percent
                notes += "Akku: Byte 4 (bestätigter Decoder)"
            }
        }

        // Die folgenden Felder bleiben bewusst leer, bis Messung + Bildschirmaufnahme
        // dieselbe Skalierung eindeutig bestätigen. Rohdaten und Kandidaten gehen nicht verloren.
        return DecodedTelemetry(batteryPercent = battery, notes = notes)
    }

    fun unsigned16LE(value: ByteArray, index: Int): Int? =
        if (index + 1 < value.size) u8(value, index) or (u8(value, index + 1) shl 8) else null

    fun unsigned32LE(value: ByteArray, index: Int): Long? =
        if (index + 3 < value.size) {
            u8(value, index).toLong() or
                (u8(value, index + 1).toLong() shl 8) or
                (u8(value, index + 2).toLong() shl 16) or
                (u8(value, index + 3).toLong() shl 24)
        } else null

    fun scaled(raw: Number, divisor: Double): Double = round(raw.toDouble() / divisor * 100.0) / 100.0

    private fun u8(value: ByteArray, index: Int): Int = value[index].toInt() and 0xFF
}

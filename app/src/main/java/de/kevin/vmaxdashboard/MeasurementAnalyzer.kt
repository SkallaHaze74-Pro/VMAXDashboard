package de.kevin.vmaxdashboard

import kotlin.math.abs

data class MeasurementFinding(
    val marker: String,
    val channel: String,
    val byteIndex: Int,
    val beforeValue: Int,
    val afterValue: Int,
    val confidence: Int
) {
    val description: String
        get() = "$marker • $channel Byte $byteIndex: $beforeValue → $afterValue ($confidence %)"
}

object MeasurementAnalyzer {
    private data class Packet(val t: Long, val channel: String, val bytes: IntArray)
    private data class Marker(val t: Long, val label: String)

    fun analyze(sessionRows: List<String>, markerRows: List<String>): Pair<List<MeasurementFinding>, String> {
        val packets = sessionRows.mapNotNull(::parsePacket)
        val markers = markerRows.mapNotNull(::parseMarker)
            .filterNot { it.label == "START" || it.label == "STOP" || it.label == "PAUSE" || it.label == "FORTSETZEN" }

        val findings = mutableListOf<MeasurementFinding>()
        markers.forEachIndexed { markerIndex, marker ->
            val nextMarkerAt = markers.getOrNull(markerIndex + 1)?.t ?: (marker.t + 20_000L)
            val afterEnd = minOf(marker.t + 20_000L, nextMarkerAt - 1L)
            val channels = packets.map { it.channel }.distinct()

            for (channel in channels) {
                val before = packets.lastOrNull { it.channel == channel && it.t in (marker.t - 5000L)..marker.t }
                val afterSamples = packets.filter { it.channel == channel && it.t in (marker.t + 250L)..afterEnd }
                if (before == null || afterSamples.isEmpty()) continue

                val max = minOf(before.bytes.size, afterSamples.maxOfOrNull { it.bytes.size } ?: 0)
                for (i in 0 until max) {
                    if (!isCandidateAllowed(marker.label, channel, i)) continue
                    val changedSamples = afterSamples.filter { sample ->
                        i < sample.bytes.size && sample.bytes[i] != before.bytes[i]
                    }
                    if (changedSamples.isEmpty()) continue

                    val activeValue = changedSamples
                        .groupingBy { it.bytes[i] }
                        .eachCount()
                        .maxByOrNull { it.value }
                        ?.key ?: continue
                    val stability = changedSamples.size * 100 / afterSamples.size
                    val magnitude = abs(activeValue - before.bytes[i])
                    val confidence = when {
                        marker.label == "Licht" && channel == "1508" && i == 0 -> 99
                        else -> (45 + stability / 2 + minOf(magnitude, 20)).coerceAtMost(99)
                    }
                    findings += MeasurementFinding(marker.label, channel, i, before.bytes[i], activeValue, confidence)
                }
            }
        }

        val top = findings
            .groupBy { Triple(it.marker, it.channel, it.byteIndex) }
            .map { (_, values) -> values.maxBy { it.confidence } }
            .sortedByDescending { it.confidence }
            .take(40)

        val report = buildString {
            appendLine("VMAX Automatische Messfahrt-Analyse")
            appendLine("Pakete: ${packets.size}")
            appendLine("Marker: ${markers.size}")
            appendLine("Gefundene Kandidaten: ${top.size}")
            appendLine()
            if (top.isEmpty()) {
                appendLine("Keine eindeutigen Änderungen gefunden. Marker vor der Aktion setzen und die Aktion bis zum nächsten Marker wiederholen.")
            } else {
                appendLine("Beste Kandidaten:")
                top.forEachIndexed { index, finding -> appendLine("${index + 1}. ${finding.description}") }
            }
            appendLine()
            appendLine("Bekannte Telemetriebytes wie Spannung, Strom, Watt und Kilometerstand werden nicht mehr als Schalter vorgeschlagen.")
            appendLine("Hinweis: Prozentwerte sind technische Kandidatenbewertungen, keine endgültige Bestätigung der Bedeutung.")
        }
        return top to report
    }

    private fun isCandidateAllowed(marker: String, channel: String, byteIndex: Int): Boolean = when (channel) {
        "1505", "1506", "1509", "150A", "150C" -> false
        "1508" -> when (byteIndex) {
            0 -> marker == "Licht"
            3 -> marker == "Fahrmodus"
            else -> true
        }
        else -> true
    }

    private fun parsePacket(row: String): Packet? {
        val p = row.split(';')
        if (p.size < 8) return null
        val time = p[0].toLongOrNull() ?: return null
        val channel = p[2]
        val bytes = p[7].split('-').mapNotNull { it.toIntOrNull(16) }.toIntArray()
        return Packet(time, channel, bytes)
    }

    private fun parseMarker(row: String): Marker? {
        val p = row.split(';')
        if (p.size < 3) return null
        return Marker(p[0].toLongOrNull() ?: return null, p[2])
    }
}

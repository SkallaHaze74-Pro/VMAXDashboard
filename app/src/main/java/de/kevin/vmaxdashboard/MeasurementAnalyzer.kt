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

        val markerFindings = analyzeMarkers(packets, markers)
        val automaticFindings = analyzeLongRideAutomatically(packets)

        val top = (markerFindings + automaticFindings)
            .groupBy { Triple(it.marker, it.channel, it.byteIndex) }
            .map { (_, values) -> values.maxBy { it.confidence } }
            .sortedByDescending { it.confidence }
            .take(40)

        val durationMs = packets.maxOfOrNull { it.t } ?: 0L
        val report = buildString {
            appendLine("VMAX Automatische Messfahrt-Analyse")
            appendLine("Pakete: ${packets.size}")
            appendLine("Dauer_ms: $durationMs")
            appendLine("Manuelle Marker: ${markers.size}")
            appendLine("Automatisches Langfahrt-Profil: aktiv")
            appendLine("Gefundene Kandidaten: ${top.size}")
            appendLine()
            if (top.isEmpty()) {
                appendLine("Keine eindeutigen unbekannten Schalterbytes gefunden.")
                appendLine("Die bestätigten Fahrwerte wurden trotzdem automatisch aufgezeichnet und gespeichert.")
                appendLine("Für Blinker, Bremse oder Fahrmodus zusätzlich den einzelnen Marker direkt vor der Aktion setzen.")
            } else {
                appendLine("Beste Kandidaten:")
                top.forEachIndexed { index, finding -> appendLine("${index + 1}. ${finding.description}") }
            }
            appendLine()
            appendLine("Automatik vergleicht längere Stillstands- und Fahrtphasen anhand der bestätigten Geschwindigkeit auf 1505 Byte 6-7.")
            appendLine("Bekannte Telemetriebytes wie Geschwindigkeit, Spannung, Strom, Watt und Kilometerstand werden nicht als Schalter vorgeschlagen.")
            appendLine("Hinweis: Prozentwerte sind technische Kandidatenbewertungen, keine endgültige Bestätigung der Bedeutung.")
        }
        return top to report
    }

    private fun analyzeMarkers(packets: List<Packet>, markers: List<Marker>): List<MeasurementFinding> {
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

                    val activeValue = mode(changedSamples.map { it.bytes[i] }) ?: continue
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
        return findings
    }

    /**
     * Läuft ohne Bedienung während langer Fahrten. Die bestätigte Geschwindigkeit
     * teilt die Aufnahme in Ruhe (<0,5 km/h) und Fahrt (>5 km/h). Anschließend
     * werden nur unbekannte Kanäle/Bytes auf stabile Zustandsunterschiede geprüft.
     */
    private fun analyzeLongRideAutomatically(packets: List<Packet>): List<MeasurementFinding> {
        if (packets.size < 120) return emptyList()
        val speedTimeline = packets
            .filter { it.channel == "1505" && it.bytes.size >= 8 }
            .map { packet ->
                val raw = (packet.bytes[6] shl 8) or packet.bytes[7]
                packet.t to raw / 10.0
            }
        if (speedTimeline.size < 20) return emptyList()

        fun speedAt(time: Long): Double? = speedTimeline.lastOrNull { it.first <= time }?.second

        val stopped = mutableMapOf<String, MutableList<Packet>>()
        val moving = mutableMapOf<String, MutableList<Packet>>()
        packets.forEach { packet ->
            if (packet.channel == "1505") return@forEach
            when (speedAt(packet.t)) {
                null -> Unit
                in 0.0..0.49 -> stopped.getOrPut(packet.channel) { mutableListOf() }.add(packet)
                in 5.0..200.0 -> moving.getOrPut(packet.channel) { mutableListOf() }.add(packet)
            }
        }

        val findings = mutableListOf<MeasurementFinding>()
        for (channel in stopped.keys.intersect(moving.keys)) {
            val restSamples = stopped[channel].orEmpty()
            val driveSamples = moving[channel].orEmpty()
            if (restSamples.size < 8 || driveSamples.size < 8) continue
            val maxLength = minOf(
                restSamples.minOfOrNull { it.bytes.size } ?: 0,
                driveSamples.minOfOrNull { it.bytes.size } ?: 0
            )
            for (index in 0 until maxLength) {
                if (!isCandidateAllowed("Auto Fahrt/Ruhe", channel, index)) continue
                val restValues = restSamples.map { it.bytes[index] }
                val driveValues = driveSamples.map { it.bytes[index] }
                val restMode = mode(restValues) ?: continue
                val driveMode = mode(driveValues) ?: continue
                if (restMode == driveMode) continue

                val restStability = restValues.count { it == restMode } * 100 / restValues.size
                val driveStability = driveValues.count { it == driveMode } * 100 / driveValues.size
                if (restStability < 55 || driveStability < 55) continue
                val confidence = ((restStability + driveStability) / 2).coerceIn(55, 95)
                findings += MeasurementFinding(
                    marker = "Auto Fahrt/Ruhe",
                    channel = channel,
                    byteIndex = index,
                    beforeValue = restMode,
                    afterValue = driveMode,
                    confidence = confidence
                )
            }
        }
        return findings
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

    private fun mode(values: List<Int>): Int? =
        values.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key

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

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
    private data class ByteSample(val t: Long, val value: Int)

    fun analyze(sessionRows: List<String>, markerRows: List<String>): Pair<List<MeasurementFinding>, String> {
        val packets = sessionRows.mapNotNull(::parsePacket)
        val allMarkers = markerRows.mapNotNull(::parseMarker)
        val markers = allMarkers.filterNot(::isSystemMarker)

        val markerFindings = analyzeMarkers(packets, markers)
        val rideFindings = analyzeLongRideAutomatically(packets)
        val patternFindings = analyzeGeneralPatterns(packets)

        val top = (markerFindings + rideFindings + patternFindings)
            .groupBy { Triple(it.marker, it.channel, it.byteIndex) }
            .map { (_, values) -> values.maxBy { it.confidence } }
            .sortedByDescending { it.confidence }
            .take(60)

        val telemetryFromMs = packets.minOfOrNull { it.t }
        val telemetryUntilMs = packets.maxOfOrNull { it.t }
        val measurementDurationMs = maxOf(
            telemetryUntilMs ?: 0L,
            allMarkers.maxOfOrNull { it.t } ?: 0L
        )
        val telemetrySpanMs = if (telemetryFromMs != null && telemetryUntilMs != null) {
            telemetryUntilMs - telemetryFromMs
        } else {
            0L
        }
        val gapAnchors = buildList {
            add(0L)
            addAll(packets.map { it.t }.distinct().sorted())
            add(measurementDurationMs)
        }.distinct().sorted()
        val largestDataGapMs = gapAnchors.zipWithNext { before, after -> after - before }
            .maxOrNull()
            ?: 0L
        val startDelayMs = telemetryFromMs ?: 0L
        val endGapMs = if (telemetryUntilMs != null) {
            (measurementDurationMs - telemetryUntilMs).coerceAtLeast(0L)
        } else {
            0L
        }
        val globalStreamTimes = packets.map { it.t }.distinct().sorted()
        val largestGlobalStreamGapMs = globalStreamTimes
            .zipWithNext { before, after -> after - before }
            .maxOrNull()
            ?: 0L
        val largestSameChannelCadenceGapMs = packets
            .groupBy { it.channel }
            .values
            .maxOfOrNull { channelPackets ->
                channelPackets.map { it.t }.distinct().sorted()
                    .zipWithNext { before, after -> after - before }
                    .maxOrNull()
                    ?: 0L
            }
            ?: 0L
        val automaticPatternCount = patternFindings.size
        val report = buildString {
            appendLine("VMAX Automatische Messfahrt-Analyse")
            appendLine("Pakete: ${packets.size}")
            // Dauer_ms remains as a backwards-compatible alias for consumers of
            // older reports. The explicit fields distinguish wall time from data.
            appendLine("Dauer_ms: $measurementDurationMs")
            appendLine("Messdauer_ms: $measurementDurationMs")
            appendLine("Telemetrie_von_ms: ${telemetryFromMs ?: 0L}")
            appendLine("Telemetrie_bis_ms: ${telemetryUntilMs ?: 0L}")
            appendLine("Telemetriezeitraum_ms: $telemetrySpanMs")
            appendLine("Größte_Datenlücke_ms: $largestDataGapMs")
            appendLine("Startverzögerung_ms: $startDelayMs")
            appendLine("Endlücke_ms: $endGapMs")
            appendLine("Größte_globale_Datenstromlücke_ms: $largestGlobalStreamGapMs")
            appendLine("Größte_kanalinterne_Taktlücke_ms: $largestSameChannelCadenceGapMs")
            appendLine("Manuelle Marker: ${markers.size}")
            appendLine("Automatische allgemeine Mustererkennung: aktiv")
            appendLine("Automatische Impuls-/Blink-Kandidaten: $automaticPatternCount")
            appendLine("Gefundene Kandidaten gesamt: ${top.size}")
            appendLine()
            if (top.isEmpty()) {
                appendLine("Keine eindeutigen unbekannten Schalter- oder Impulsmuster gefunden.")
                appendLine("Die bestätigten Fahrwerte wurden trotzdem automatisch aufgezeichnet und gespeichert.")
                appendLine("Für schwer erkennbare Funktionen kann zusätzlich ein einzelner Stillstandsmarker zur Bestätigung gesetzt werden.")
            } else {
                appendLine("Beste Kandidaten:")
                top.forEachIndexed { index, finding -> appendLine("${index + 1}. ${finding.description}") }
            }
            appendLine()
            appendLine("Die Automatik sucht jetzt unabhängig von Markern nach stabilen Umschaltern, kurzen Impulsen und regelmäßigen A-B-A-Mustern.")
            appendLine("Regelmäßige Wechsel mit ähnlichen Zeitabständen werden als möglicher Blinker-Kandidat markiert.")
            appendLine("Bekannte Telemetriebytes wie Geschwindigkeit, Spannung, Strom, Watt und Kilometerstand werden herausgefiltert.")
            appendLine("Stillstandstests dienen nur noch dazu, automatisch gefundene Kandidaten gezielt zu bestätigen.")
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
                    val changedSamples = afterSamples.filter { sample -> i < sample.bytes.size && sample.bytes[i] != before.bytes[i] }
                    if (changedSamples.isEmpty()) continue

                    val activeValue = mode(changedSamples.map { it.bytes[i] }) ?: continue
                    val stability = changedSamples.size * 100 / afterSamples.size
                    val magnitude = abs(activeValue - before.bytes[i])
                    val confidence = when {
                        marker.label.contains("Licht", true) && channel == "1508" && i == 0 -> 99
                        marker.label.contains("ECO", true) && channel == "1508" && i == 3 && activeValue == 1 -> 99
                        marker.label.contains("SPORT", true) && channel == "1508" && i == 3 && activeValue == 2 -> 99
                        else -> (45 + stability / 2 + minOf(magnitude, 20)).coerceAtMost(99)
                    }
                    findings += MeasurementFinding(marker.label, channel, i, before.bytes[i], activeValue, confidence)
                }
            }
        }
        return findings
    }

    private fun analyzeLongRideAutomatically(packets: List<Packet>): List<MeasurementFinding> {
        if (packets.size < 120) return emptyList()
        val speedTimeline = packets
            .filter { it.channel == "1505" && it.bytes.size >= 8 }
            .map { packet -> packet.t to (((packet.bytes[6] shl 8) or packet.bytes[7]) / 10.0) }
        if (speedTimeline.size < 20) return emptyList()

        fun speedAt(time: Long): Double? = speedTimeline.lastOrNull { it.first <= time }?.second

        val stopped = mutableMapOf<String, MutableList<Packet>>()
        val moving = mutableMapOf<String, MutableList<Packet>>()
        packets.forEach { packet ->
            if (packet.channel == "1505") return@forEach
            when (val speed = speedAt(packet.t)) {
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
            val maxLength = minOf(restSamples.minOfOrNull { it.bytes.size } ?: 0, driveSamples.minOfOrNull { it.bytes.size } ?: 0)
            for (index in 0 until maxLength) {
                if (!isCandidateAllowed("Auto Fahrt/Ruhe", channel, index)) continue
                val restValues = restSamples.map { it.bytes[index] }
                val driveValues = driveSamples.map { it.bytes[index] }
                val restMode = mode(restValues) ?: continue
                val driveMode = mode(driveValues) ?: continue
                if (restMode == driveMode) continue

                val restStability = restValues.count { it == restMode } * 100 / restValues.size
                val driveStability = driveValues.count { it == driveMode } * 100 / driveValues.size
                if (restStability < 60 || driveStability < 60) continue
                val confidence = ((restStability + driveStability) / 2).coerceIn(60, 93)
                findings += MeasurementFinding("Auto Fahrt/Ruhe", channel, index, restMode, driveMode, confidence)
            }
        }
        return findings
    }

    private fun analyzeGeneralPatterns(packets: List<Packet>): List<MeasurementFinding> {
        if (packets.size < 40) return emptyList()
        val findings = mutableListOf<MeasurementFinding>()
        val byChannel = packets.groupBy { it.channel }

        for ((channel, channelPackets) in byChannel) {
            val maxLength = channelPackets.maxOfOrNull { it.bytes.size } ?: 0
            for (index in 0 until maxLength) {
                if (!isCandidateAllowed("Auto Muster", channel, index)) continue
                val samples = channelPackets.mapNotNull { packet ->
                    if (index < packet.bytes.size) ByteSample(packet.t, packet.bytes[index]) else null
                }
                if (samples.size < 8) continue

                val transitions = mutableListOf<ByteSample>()
                var previous = samples.first()
                for (sample in samples.drop(1)) {
                    if (sample.value != previous.value) {
                        transitions += sample
                        previous = sample
                    }
                }
                if (transitions.isEmpty()) continue

                val distinctValues = samples.map { it.value }.distinct()
                if (distinctValues.size !in 2..4) continue
                val dominant = mode(samples.map { it.value }) ?: continue
                val nonDominant = samples.filter { it.value != dominant }
                if (nonDominant.isEmpty()) continue
                val alternate = mode(nonDominant.map { it.value }) ?: continue

                val transitionCount = transitions.size
                val dwellIntervals = transitions.zipWithNext { a, b -> b.t - a.t }.filter { it in 100L..10_000L }
                val medianInterval = median(dwellIntervals)
                val regularity = if (dwellIntervals.size >= 3 && medianInterval != null) {
                    val tolerance = maxOf(180L, medianInterval / 3)
                    dwellIntervals.count { abs(it - medianInterval) <= tolerance } * 100 / dwellIntervals.size
                } else 0

                val minorityShare = nonDominant.size * 100 / samples.size
                val returnPatternCount = countReturnPatterns(samples)

                when {
                    transitionCount >= 6 && regularity >= 60 && medianInterval in 250L..2500L -> {
                        val confidence = (62 + regularity / 3 + minOf(transitionCount, 15)).coerceAtMost(96)
                        findings += MeasurementFinding(
                            marker = "Auto periodisch / möglicher Blinker (${medianInterval} ms)",
                            channel = channel,
                            byteIndex = index,
                            beforeValue = dominant,
                            afterValue = alternate,
                            confidence = confidence
                        )
                    }
                    returnPatternCount >= 2 && transitionCount >= 4 && minorityShare in 2..45 -> {
                        val confidence = (58 + minOf(returnPatternCount * 7, 28) + minOf(transitionCount, 10)).coerceAtMost(92)
                        findings += MeasurementFinding(
                            marker = "Auto Impuls A-B-A",
                            channel = channel,
                            byteIndex = index,
                            beforeValue = dominant,
                            afterValue = alternate,
                            confidence = confidence
                        )
                    }
                    transitionCount in 1..5 && minorityShare in 5..55 -> {
                        val stability = samples.count { it.value == dominant } * 100 / samples.size
                        val confidence = (50 + minOf(stability / 4, 20) + transitionCount * 3).coerceAtMost(82)
                        findings += MeasurementFinding(
                            marker = "Auto Zustandswechsel",
                            channel = channel,
                            byteIndex = index,
                            beforeValue = dominant,
                            afterValue = alternate,
                            confidence = confidence
                        )
                    }
                }
            }
        }
        return findings
    }

    private fun countReturnPatterns(samples: List<ByteSample>): Int {
        if (samples.size < 3) return 0
        var count = 0
        for (i in 1 until samples.lastIndex) {
            val a = samples[i - 1].value
            val b = samples[i].value
            val c = samples[i + 1].value
            if (a == c && a != b && samples[i + 1].t - samples[i - 1].t <= 12_000L) count++
        }
        return count
    }

    private fun median(values: List<Long>): Long? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        return sorted[sorted.size / 2]
    }

    private fun isCandidateAllowed(marker: String, channel: String, byteIndex: Int): Boolean =
        VmaxDecoderPolicy.isLearningCandidateAllowed(marker, channel, byteIndex)

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

    private fun isSystemMarker(marker: Marker): Boolean {
        val label = marker.label.lowercase()
        return marker.label in setOf("START", "STOP", "PAUSE", "FORTSETZEN") ||
            ((label.startsWith("ble ") || label.startsWith("ble-link")) &&
                ("getrennt" in label || "wieder verbunden" in label)) ||
            label.startsWith("telemetrie wieder aktiv")
    }
}

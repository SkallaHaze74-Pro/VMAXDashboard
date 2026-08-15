package de.kevin.vmaxdashboard

import android.content.Context

object AdaptiveDecoderRuntime {
    @Volatile private var store: AdaptiveDecoderProfileStore? = null

    fun initialize(context: Context) {
        if (store == null) {
            synchronized(this) {
                if (store == null) store = AdaptiveDecoderProfileStore.get(context.applicationContext)
            }
        }
    }

    fun decode(channel: String, value: ByteArray): AdaptiveDecodedTelemetry =
        store?.decode(channel, value) ?: AdaptiveDecodedTelemetry()

    fun snapshot(): AdaptiveProfileSnapshot =
        store?.snapshot() ?: AdaptiveProfileSnapshot()

    fun decodePackets(rawPackets: Map<String, String>): AdaptiveDecodedTelemetry {
        val bestBySignal = linkedMapOf<String, SignalCandidate>()

        rawPackets.forEach { (channel, hex) ->
            val bytes = parseHex(hex)
            if (bytes.isEmpty()) return@forEach
            val decoded = decode(channel, bytes)
            collect(bestBySignal, "batteryPercent", decoded.batteryPercent, decoded, channel)
            collect(bestBySignal, "speedKmh", decoded.speedKmh, decoded, channel)
            collect(bestBySignal, "voltageV", decoded.voltageV, decoded, channel)
            collect(bestBySignal, "currentA", decoded.currentA, decoded, channel)
            collect(bestBySignal, "powerW", decoded.powerW, decoded, channel)
            collect(bestBySignal, "motorTemperatureC", decoded.motorTemperatureC, decoded, channel)
            collect(bestBySignal, "batteryTemperatureC", decoded.batteryTemperatureC, decoded, channel)
            collect(bestBySignal, "tripDistanceKm", decoded.tripDistanceKm, decoded, channel)
            collect(bestBySignal, "odometerKm", decoded.odometerKm, decoded, channel)
            collect(bestBySignal, "brakeActive", decoded.brakeActive, decoded, channel)
            collect(bestBySignal, "leftIndicator", decoded.leftIndicator, decoded, channel)
            collect(bestBySignal, "rightIndicator", decoded.rightIndicator, decoded, channel)
            collect(bestBySignal, "lightOn", decoded.lightOn, decoded, channel)
            collect(bestBySignal, "lockActive", decoded.lockActive, decoded, channel)
            collect(bestBySignal, "charging", decoded.charging, decoded, channel)
        }

        return AdaptiveDecodedTelemetry(
            batteryPercent = bestBySignal["batteryPercent"]?.value as? Int,
            speedKmh = bestBySignal["speedKmh"]?.value as? Double,
            voltageV = bestBySignal["voltageV"]?.value as? Double,
            currentA = bestBySignal["currentA"]?.value as? Double,
            powerW = bestBySignal["powerW"]?.value as? Double,
            motorTemperatureC = bestBySignal["motorTemperatureC"]?.value as? Double,
            batteryTemperatureC = bestBySignal["batteryTemperatureC"]?.value as? Double,
            tripDistanceKm = bestBySignal["tripDistanceKm"]?.value as? Double,
            odometerKm = bestBySignal["odometerKm"]?.value as? Double,
            brakeActive = bestBySignal["brakeActive"]?.value as? Boolean,
            leftIndicator = bestBySignal["leftIndicator"]?.value as? Boolean,
            rightIndicator = bestBySignal["rightIndicator"]?.value as? Boolean,
            lightOn = bestBySignal["lightOn"]?.value as? Boolean,
            lockActive = bestBySignal["lockActive"]?.value as? Boolean,
            charging = bestBySignal["charging"]?.value as? Boolean,
            signalConfidence = bestBySignal.mapValues { it.value.confidence },
            signalSources = bestBySignal.mapValues { it.value.source },
            signalChannels = bestBySignal.mapValues { it.value.channel }
        )
    }

    private data class SignalCandidate(
        val value: Any,
        val confidence: String,
        val source: String,
        val channel: String,
        val weight: Int
    )

    private fun collect(
        bestBySignal: MutableMap<String, SignalCandidate>,
        signal: String,
        value: Any?,
        decoded: AdaptiveDecodedTelemetry,
        fallbackChannel: String
    ) {
        if (value == null) return
        val confidence = decoded.signalConfidence[signal] ?: "experimentell"
        val source = decoded.signalSources[signal] ?: "Unbekannt"
        val channel = decoded.signalChannels[signal] ?: fallbackChannel
        val candidate = SignalCandidate(value, confidence, source, channel, weight(confidence, source))
        val existing = bestBySignal[signal]
        if (existing == null || candidate.weight > existing.weight) {
            bestBySignal[signal] = candidate
        }
    }

    private fun weight(confidence: String, source: String): Int {
        val confidenceWeight = when (confidence) {
            "hoch" -> 400
            "gut" -> 300
            "vorsichtig" -> 200
            else -> 100
        }
        val sourceWeight = when (source) {
            "GitHub-Konsens" -> 50
            "Lokales Lernen" -> 25
            else -> 0
        }
        return confidenceWeight + sourceWeight
    }

    private fun parseHex(hex: String): ByteArray =
        hex.split('-', ' ', ':')
            .mapNotNull { part -> part.trim().takeIf { it.length == 2 }?.toIntOrNull(16)?.toByte() }
            .toByteArray()
}

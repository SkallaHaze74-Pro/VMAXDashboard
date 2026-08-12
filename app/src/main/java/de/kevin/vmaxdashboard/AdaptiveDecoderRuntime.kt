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
        var batteryPercent: Int? = null
        var speedKmh: Double? = null
        var voltageV: Double? = null
        var currentA: Double? = null
        var powerW: Double? = null
        var motorTemperatureC: Double? = null
        var batteryTemperatureC: Double? = null
        var tripDistanceKm: Double? = null
        var odometerKm: Double? = null
        var brakeActive: Boolean? = null
        var leftIndicator: Boolean? = null
        var rightIndicator: Boolean? = null
        var lightOn: Boolean? = null
        var lockActive: Boolean? = null
        var charging: Boolean? = null

        rawPackets.forEach { (channel, hex) ->
            val bytes = parseHex(hex)
            if (bytes.isEmpty()) return@forEach
            val decoded = decode(channel, bytes)
            batteryPercent = batteryPercent ?: decoded.batteryPercent
            speedKmh = speedKmh ?: decoded.speedKmh
            voltageV = voltageV ?: decoded.voltageV
            currentA = currentA ?: decoded.currentA
            powerW = powerW ?: decoded.powerW
            motorTemperatureC = motorTemperatureC ?: decoded.motorTemperatureC
            batteryTemperatureC = batteryTemperatureC ?: decoded.batteryTemperatureC
            tripDistanceKm = tripDistanceKm ?: decoded.tripDistanceKm
            odometerKm = odometerKm ?: decoded.odometerKm
            brakeActive = brakeActive ?: decoded.brakeActive
            leftIndicator = leftIndicator ?: decoded.leftIndicator
            rightIndicator = rightIndicator ?: decoded.rightIndicator
            lightOn = lightOn ?: decoded.lightOn
            lockActive = lockActive ?: decoded.lockActive
            charging = charging ?: decoded.charging
        }

        return AdaptiveDecodedTelemetry(
            batteryPercent = batteryPercent,
            speedKmh = speedKmh,
            voltageV = voltageV,
            currentA = currentA,
            powerW = powerW,
            motorTemperatureC = motorTemperatureC,
            batteryTemperatureC = batteryTemperatureC,
            tripDistanceKm = tripDistanceKm,
            odometerKm = odometerKm,
            brakeActive = brakeActive,
            leftIndicator = leftIndicator,
            rightIndicator = rightIndicator,
            lightOn = lightOn,
            lockActive = lockActive,
            charging = charging
        )
    }

    private fun parseHex(hex: String): ByteArray =
        hex.split('-', ' ', ':')
            .mapNotNull { part -> part.trim().takeIf { it.length == 2 }?.toIntOrNull(16)?.toByte() }
            .toByteArray()
}

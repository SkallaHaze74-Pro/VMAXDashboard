package de.kevin.vmaxdashboard

data class BleByteStat(
    val index: Int,
    val current: Int,
    val min: Int,
    val max: Int,
    val changeCount: Int,
    val deltaFromBaseline: Int? = null
) {
    val range: Int get() = max - min
}

data class DecoderCandidate(
    val channel: String,
    val byteIndex: Int,
    val score: Int,
    val hint: String,
    val current: Int,
    val range: Int,
    val changeCount: Int
)

data class BleChannelState(
    val channel: String,
    val service: String = "",
    val properties: String = "Notify",
    val hex: String = "Noch keine Daten",
    val packetCount: Int = 0,
    val packetLength: Int = 0,
    val packetsPerSecond: Double = 0.0,
    val lastSeenMs: Long? = null,
    val changedBytes: String = "–",
    val byteStats: List<BleByteStat> = emptyList()
) {
    val active: Boolean get() = packetCount > 0
}

data class DiscoveredScooter(
    val name: String,
    val address: String,
    val rssi: Int,
    val likelyScooter: Boolean = false,
    val lastSeenMs: Long = System.currentTimeMillis()
)

data class ScooterState(
    val status: String = "Nicht verbunden",
    val connected: Boolean = false,
    val scanning: Boolean = false,
    val universalScan: Boolean = false,
    val discoveredScooters: List<DiscoveredScooter> = emptyList(),
    val rememberedDeviceName: String = "",
    val rememberedDeviceAddress: String = "",
    val autoConnectEnabled: Boolean = true,
    val reconnectAttempt: Int = 0,
    val telemetryUploadEnabled: Boolean = false,
    val testerId: String = "",
    val deviceName: String = "BT638",
    val address: String = "",
    val batteryPercent: Int? = null,
    val voltageV: Double? = null,
    val currentA: Double? = null,
    val powerW: Double? = null,
    val temperatureC: Double? = null,
    val speedKmh: Double? = null,
    val odometerKm: Double? = null,
    val tripKm: Double = 0.0,
    val maxSpeedKmh: Double = 0.0,
    val averageSpeedKmh: Double = 0.0,
    val rideSeconds: Long = 0,
    val speedHistory: List<Double> = emptyList(),
    val decoderCandidates: List<DecoderCandidate> = emptyList(),
    val packetTotal: Int = 0,
    val lastCharacteristic: String = "",
    val lastRawHex: String = "",
    val analysisPhase: String = "Leerlauf",
    val analysisPhaseNumber: Int = 0,
    val channels: List<BleChannelState> = emptyList(),
    val log: List<String> = emptyList()
)

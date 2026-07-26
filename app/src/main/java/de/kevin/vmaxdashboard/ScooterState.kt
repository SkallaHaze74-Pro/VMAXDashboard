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

data class BleChannelState(
    val channel: String,
    val hex: String,
    val packetCount: Int = 0,
    val packetLength: Int = 0,
    val changedBytes: String = "–",
    val byteStats: List<BleByteStat> = emptyList()
)

data class ScooterState(
    val status: String = "Nicht verbunden",
    val connected: Boolean = false,
    val scanning: Boolean = false,
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
    val packetTotal: Int = 0,
    val lastCharacteristic: String = "",
    val lastRawHex: String = "",
    val analysisPhase: String = "Leerlauf",
    val analysisPhaseNumber: Int = 0,
    val channels: List<BleChannelState> = emptyList(),
    val log: List<String> = emptyList()
)

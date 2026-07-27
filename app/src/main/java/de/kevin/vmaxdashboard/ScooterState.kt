package de.kevin.vmaxdashboard

data class ByteCandidate(
    val characteristic: String,
    val byteIndex: Int,
    val beforeValue: Int,
    val activeValue: Int,
    val score: Int
)

data class BleChannelState(
    val channel: String = "",
    val service: String = "",
    val hex: String = "",
    val packetCount: Int = 0,
    val active: Boolean = false
)

data class ScooterState(
    val scanning: Boolean = false,
    val connected: Boolean = false,
    val status: String = "Bereit",

    val deviceName: String = "BT638",
    val address: String = "",

    val batteryPercent: Int? = null,
    val voltageV: Double? = null,
    val temperatureC: Double? = null,
    val speedKmh: Double? = null,
    val odometerKm: Double? = null,
    val rssi: Int? = null,

    val leftIndicator: Boolean = false,
    val rightIndicator: Boolean = false,
    val lightOn: Boolean = false,
    val brakeActive: Boolean = false,

    val lastCharacteristic: String = "",
    val lastRawHex: String = "",
    val rawPackets: Map<String, String> = emptyMap(),

    val analysisPhase: String = "Bereit",
    val analysisPhaseNumber: Int = 0,
    val packetTotal: Int = 0,
    val channels: List<BleChannelState> = emptyList(),

    val labRunning: Boolean = false,
    val labPhase: String = "Bereit",
    val labAction: String = "",
    val labCandidates: List<ByteCandidate> = emptyList(),

    val encryptedReports: Int = 0,
    val log: List<String> = emptyList()
)

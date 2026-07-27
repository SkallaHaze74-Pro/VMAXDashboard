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
    val service: String = "1500",
    val title: String = "",
    val meaning: String = "",
    val knowledge: String = "",
    val hex: String = "",
    val changedBytes: String = "–",
    val packetCount: Int = 0,
    val active: Boolean = false,
    val lastSeenMs: Long = 0L
)

data class ScooterState(
    val scanning: Boolean = false,
    val connected: Boolean = false,
    val status: String = "Bereit",
    val deviceName: String = "BT638",
    val address: String = "",
    val batteryPercent: Int? = null,
    val voltageV: Double? = null,
    val currentA: Double? = null,
    val motorTemperatureC: Double? = null,
    val batteryTemperatureC: Double? = null,
    val temperatureC: Double? = null,
    val speedKmh: Double? = null,
    val tripDistanceKm: Double? = null,
    val odometerKm: Double? = null,
    val rssi: Int? = null,
    val leftIndicator: Boolean = false,
    val rightIndicator: Boolean = false,
    val lightOn: Boolean = false,
    val brakeActive: Boolean = false,
    val lockActive: Boolean? = null,
    val charging: Boolean? = null,
    val lastCharacteristic: String = "",
    val lastRawHex: String = "",
    val lastChangedBytes: String = "–",
    val rawPackets: Map<String, String> = emptyMap(),
    val analysisPhase: String = "Bereit",
    val analysisPhaseNumber: Int = 0,
    val packetTotal: Int = 0,
    val sessionStartedAt: Long = 0L,
    val channels: List<BleChannelState> = emptyList(),
    val labRunning: Boolean = false,
    val labPhase: String = "Bereit",
    val labAction: String = "",
    val labCandidates: List<ByteCandidate> = emptyList(),
    val encryptedReports: Int = 0,
    val lastExportMessage: String = "",
    val log: List<String> = emptyList()
)

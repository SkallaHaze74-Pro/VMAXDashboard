package de.kevin.vmaxdashboard

data class ByteCandidate(
    val characteristic: String,
    val byteIndex: Int,
    val beforeValue: Int,
    val activeValue: Int,
    val score: Int
)

data class ScooterState(
    val scanning: Boolean = false,
    val connected: Boolean = false,
    val status: String = "Bereit",
    val deviceName: String = "BT638",
    val address: String = "",
    val batteryPercent: Int? = null,
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
    val labRunning: Boolean = false,
    val labPhase: String = "Bereit",
    val labAction: String = "",
    val labCandidates: List<ByteCandidate> = emptyList(),
    val encryptedReports: Int = 0,
    val log: List<String> = emptyList()
)

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
    val driveRaw: Int? = null,
    val motorLoadRaw: Int? = null,
    val batteryStateRaw: Int? = null,
    val accessoryByte0: Int? = null,
    val accessoryByte3: Int? = null,
    val tripDistanceKm: Double? = null,
    val odometerKm: Double? = null,
    val rssi: Int? = null,
    val lastCharacteristic: String = "",
    val lastRawHex: String = "",
    val lastChangedBytes: String = "–",
    val rawPackets: Map<String, String> = emptyMap(),
    val analysisPhase: String = "Bereit",
    val analysisPhaseNumber: Int = 0,
    val packetTotal: Int = 0,
    val packetsPerSecond: Double = 0.0,
    val lastPacketAt: Long = 0L,
    val currentPowerW: Double? = null,
    val maxSpeedKmh: Double? = null,
    val maxPowerW: Double? = null,
    val sessionStartedAt: Long = 0L,
    val channels: List<BleChannelState> = emptyList(),
    val labRunning: Boolean = false,
    val labPhase: String = "Bereit",
    val labAction: String = "",
    val labCandidates: List<ByteCandidate> = emptyList(),
    val encryptedReports: Int = 0,
    val lastExportMessage: String = "",
    val recordingActive: Boolean = false,
    val recordingPaused: Boolean = false,
    val recordingStartedAt: Long = 0L,
    val recordingPacketCount: Int = 0,
    val markerCount: Int = 0,
    val lastMarker: String = "",
    val autoAnalysisFindings: List<MeasurementFinding> = emptyList(),
    val learningProfileCount: Int = 0,
    val sessionHistoryCount: Int = 0,
    val lastSessionFolder: String = "",
    val motorTuningSupported: Boolean = false,
    val motorTuningReadAvailable: Boolean = false,
    val motorTuningWriteAvailable: Boolean = false,
    val motorTuningBusy: Boolean = false,
    val motorTuningStatus: String = "Noch nicht geprüft",
    val motorTuningProtocol: MotorTuningProtocolMode = MotorTuningProtocolMode.UNKNOWN,
    val motorTuningProfiles: List<MotorTuningProfile> = emptyList(),
    val motorTuningOriginalProfiles: List<MotorTuningProfile> = emptyList(),
    val motorTuningLastPacket: String = "",
    val motorTuningLastReadRaw: String = "",
    val motorTuningLastVerified: Boolean? = null,
    val log: List<String> = emptyList()
) {
    private val adaptiveLive: AdaptiveDecodedTelemetry
        get() = AdaptiveDecoderRuntime.decodePackets(rawPackets)

    private val aiSnapshot: AdaptiveProfileSnapshot
        get() = AdaptiveDecoderRuntime.snapshot()

    private val originalSdkLive: OriginalSdkRealtimeSnapshot
        get() = OriginalSdkRealtimeDecoder.decodePackets(rawPackets)

    val leftIndicator: Boolean
        get() = adaptiveLive.leftIndicator ?: false

    val rightIndicator: Boolean
        get() = adaptiveLive.rightIndicator ?: false

    val brakeActive: Boolean
        get() = adaptiveLive.brakeActive ?: false

    val lightOn: Boolean
        get() = when (accessoryByte0) {
            0 -> false
            1 -> true
            else -> originalSdkLive.lightOn ?: adaptiveLive.lightOn ?: false
        }

    val lockActive: Boolean?
        get() = adaptiveLive.lockActive

    val charging: Boolean?
        get() = adaptiveLive.charging

    val aiDecoderRuleCount: Int
        get() = aiSnapshot.ruleCount

    val aiDecoderConfirmedRules: Int
        get() = aiSnapshot.confirmedRuleCount

    val aiDecoderRevision: String
        get() = aiSnapshot.revision

    val aiDecoderSource: String
        get() = aiSnapshot.source

    val aiDecoderSignals: Set<String>
        get() = aiSnapshot.signals

    // Original VMAX/Hyena SDK semantics, decoded at the incoming BLE notification rate.
    val sdkLiveFieldCount: Int
        get() = originalSdkLive.availableFieldCount

    val sdkPerformancePowerAW: Double?
        get() = originalSdkLive.performancePowerAW

    val sdkPerformancePowerBW: Double?
        get() = originalSdkLive.performancePowerBW

    val sdkPerformanceTorqueNm: Double?
        get() = originalSdkLive.performanceTorqueNm

    val sdkPerformanceRpm: Int?
        get() = originalSdkLive.performanceRpm

    val sdkPerformanceDistanceRaw: Int?
        get() = originalSdkLive.performanceDistanceRaw

    val sdkOperatingCounterRaw: Long?
        get() = originalSdkLive.operatingCounterRaw

    val sdkBatteryTemperatureC: Double?
        get() = originalSdkLive.batteryTemperatureC

    val sdkSecondaryBatteryCurrentA: Double?
        get() = originalSdkLive.secondaryBatteryCurrentA

    val sdkDirectPowerW: Double?
        get() = originalSdkLive.directPowerW

    val sdkMotorCurrentA: Double?
        get() = originalSdkLive.motorCurrentA

    val sdkMotorVoltageV: Double?
        get() = originalSdkLive.motorVoltageV

    val sdkMotorRpm: Int?
        get() = originalSdkLive.motorRpm

    val sdkMotorTorqueNm: Double?
        get() = originalSdkLive.motorTorqueNm

    val sdkMotorTemperatureC: Double?
        get() = originalSdkLive.motorTemperatureC

    val sdkAssistanceLevelRaw: Int?
        get() = originalSdkLive.assistanceLevelRaw

    val resolvedBatteryTemperatureC: Double?
        get() = batteryTemperatureC ?: originalSdkLive.batteryTemperatureC ?: adaptiveLive.batteryTemperatureC

    val resolvedMotorTemperatureC: Double?
        get() = motorTemperatureC ?: originalSdkLive.motorTemperatureC ?: adaptiveLive.motorTemperatureC

    val resolvedDirectPowerW: Double?
        get() = originalSdkLive.directPowerW ?: adaptiveLive.powerW ?: currentPowerW
}

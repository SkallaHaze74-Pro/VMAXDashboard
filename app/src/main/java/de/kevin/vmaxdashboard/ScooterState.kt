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
    val telemetryReady: Boolean = false,
    val connectionEpoch: Long = 0L,
    val speedSampleConnectionEpoch: Long = -1L,
    val lastSpeedSampleElapsedRealtimeMs: Long = 0L,
    val diagnosticGattReadRunning: Boolean = false,
    val gattOperationBusy: Boolean = false,
    val status: String = "Bereit",
    val deviceName: String = "BT638",
    val address: String = "",
    /** Load-sag-resistant value used by the dashboard. */
    val batteryPercent: Int? = null,
    /** Exact 1509/4 value retained for diagnostics and lossless exports. */
    val batteryPercentRaw: Int? = null,
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
    val startModeRaw: Int? = null,
    val startModeWriteAvailable: Boolean = false,
    val startModeBusy: Boolean = false,
    val startModeStatus: String = "Warte auf Controllerdaten",
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
    val lastBatteryTelemetryAt: Long = 0L,
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
        get() = connected && (adaptiveLive.leftIndicator ?: false)

    val rightIndicator: Boolean
        get() = connected && (adaptiveLive.rightIndicator ?: false)

    val brakeActive: Boolean
        get() = connected && (adaptiveLive.brakeActive ?: false)

    val lightOn: Boolean
        get() = connected && when (accessoryByte0) {
            0 -> false
            1 -> true
            else -> originalSdkLive.lightOn ?: adaptiveLive.lightOn ?: false
        }

    val lockActive: Boolean?
        get() = if (connected) adaptiveLive.lockActive else null

    val charging: Boolean?
        get() = if (connected) adaptiveLive.charging else null

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

    // Original GPST/VMAX SDK semantics, decoded at the incoming BLE notification rate.
    // Bundled Hyena/Hylink classes are a separate vendor evidence source, not a BT638 identity.
    // Values are hidden until the current connection has delivered fresh live telemetry.
    val sdkLiveFieldCount: Int
        get() = if (connected && telemetryReady) originalSdkLive.availableFieldCount else 0

    val sdkPerformancePowerAW: Double?
        get() = originalSdkLive.performancePowerAW.takeIf { connected && telemetryReady }

    val sdkPerformancePowerBW: Double?
        get() = originalSdkLive.performancePowerBW.takeIf { connected && telemetryReady }

    val sdkPerformanceTorqueNm: Double?
        get() = originalSdkLive.performanceTorqueNm.takeIf { connected && telemetryReady }

    val sdkPerformanceRpm: Int?
        get() = originalSdkLive.performanceRpm.takeIf { connected && telemetryReady }

    val sdkPerformanceDistanceRaw: Int?
        get() = originalSdkLive.performanceDistanceRaw.takeIf { connected && telemetryReady }

    val sdkRemainingRangeKm: Double?
        get() = originalSdkLive.remainingRangeKm.takeIf { connected && telemetryReady }

    val sdkOperatingCounterRaw: Long?
        get() = originalSdkLive.operatingCounterRaw.takeIf { connected && telemetryReady }

    val sdkBatteryTemperatureC: Double?
        get() = originalSdkLive.batteryTemperatureC.takeIf { connected && telemetryReady }

    val sdkSecondaryBatteryCurrentA: Double?
        get() = originalSdkLive.secondaryBatteryCurrentA.takeIf { connected && telemetryReady }

    val sdkDirectPowerW: Double?
        get() = originalSdkLive.directPowerW.takeIf { connected && telemetryReady }

    val sdkMotorCurrentA: Double?
        get() = originalSdkLive.motorCurrentA.takeIf { connected && telemetryReady }

    val sdkMotorVoltageV: Double?
        get() = originalSdkLive.motorVoltageV.takeIf { connected && telemetryReady }

    val sdkMotorRpm: Int?
        get() = originalSdkLive.motorRpm.takeIf { connected && telemetryReady }

    val sdkMotorTorqueNm: Double?
        get() = originalSdkLive.motorTorqueNm.takeIf { connected && telemetryReady }

    val sdkMotorTemperatureC: Double?
        get() = originalSdkLive.motorTemperatureC.takeIf { connected && telemetryReady }

    val sdkAssistanceLevelRaw: Int?
        get() = originalSdkLive.assistanceLevelRaw.takeIf { connected && telemetryReady }

    val resolvedBatteryTemperatureC: Double?
        get() = if (connected && telemetryReady) batteryTemperatureC ?: originalSdkLive.batteryTemperatureC ?: adaptiveLive.batteryTemperatureC else null

    val resolvedMotorTemperatureC: Double?
        get() = if (connected && telemetryReady) motorTemperatureC ?: originalSdkLive.motorTemperatureC ?: adaptiveLive.motorTemperatureC else null

    val resolvedDirectPowerW: Double?
        get() = if (connected && telemetryReady) originalSdkLive.directPowerW ?: adaptiveLive.powerW ?: currentPowerW else null
}

/** Drops values that are only valid for one physical GATT connection. */
internal fun ScooterState.clearConnectionScopedTelemetry(nextConnectionEpoch: Long): ScooterState = copy(
    telemetryReady = false,
    connectionEpoch = nextConnectionEpoch,
    speedSampleConnectionEpoch = -1L,
    lastSpeedSampleElapsedRealtimeMs = 0L,
    diagnosticGattReadRunning = false,
    gattOperationBusy = false,
    batteryPercent = null,
    batteryPercentRaw = null,
    voltageV = null,
    currentA = null,
    motorTemperatureC = null,
    batteryTemperatureC = null,
    temperatureC = null,
    speedKmh = null,
    driveRaw = null,
    motorLoadRaw = null,
    batteryStateRaw = null,
    accessoryByte0 = null,
    accessoryByte3 = null,
    startModeRaw = null,
    startModeWriteAvailable = false,
    startModeBusy = false,
    tripDistanceKm = null,
    odometerKm = null,
    lastCharacteristic = "",
    lastRawHex = "",
    lastChangedBytes = "–",
    rawPackets = emptyMap(),
    packetTotal = 0,
    packetsPerSecond = 0.0,
    lastPacketAt = 0L,
    lastBatteryTelemetryAt = 0L,
    currentPowerW = null,
    channels = emptyList()
)

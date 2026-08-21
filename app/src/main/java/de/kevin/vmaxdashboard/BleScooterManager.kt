package de.kevin.vmaxdashboard

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.ArrayDeque
import java.util.UUID
import kotlin.math.abs

internal fun resolveElectricalPowerW(
    decodedVoltageV: Double?,
    decodedCurrentA: Double?,
    previousVoltageV: Double?,
    previousCurrentA: Double?
): Double? {
    val voltage = decodedVoltageV ?: previousVoltageV
    val current = decodedCurrentA ?: previousCurrentA
    return if (voltage != null && current != null) voltage * current else null
}

internal fun resolveExportPowerW(
    decodedPowerW: Double?,
    electricalPowerW: Double?,
    previousDirectPowerW: Double?
): Double? = decodedPowerW ?: previousDirectPowerW ?: electricalPowerW

internal const val RAW_TELEMETRY_CSV_HEADER =
    "relative_ms;timestamp_ms;channel;meaning;length;packet_no;changed_bytes;hex;origin;connection_epoch;" +
        "service_uuid;characteristic_uuid;properties_raw"

internal fun buildRawTelemetryCsv(rows: List<String>): String =
    RAW_TELEMETRY_CSV_HEADER + "\n" + rows.joinToString("\n")

internal const val QUARANTINED_NOTIFICATION_ORIGIN = "NOTIFICATION_QUARANTINED"
internal const val DIAGNOSTIC_NOTIFICATION_ORIGIN = "NOTIFICATION_DIAGNOSTIC"

internal fun rawTelemetryRowsForAnalysis(rows: List<String>): List<String> =
    rows.filter { row -> row.split(';').getOrNull(8) == BlePacketOrigin.NOTIFICATION.name }

internal fun shouldStartBleScan(
    scanning: Boolean,
    connected: Boolean,
    connectionAllocated: Boolean
): Boolean = !scanning && !connected && !connectionAllocated

internal fun shouldAcceptBleScanResult(
    scanning: Boolean,
    activeScanIntent: Long?,
    callbackScanIntent: Long
): Boolean = scanning && activeScanIntent == callbackScanIntent

internal fun shouldForceCloseWriteEpochOnTimeout(
    writeCallbackExpected: Boolean,
    writeCallbackReceived: Boolean
): Boolean = writeCallbackExpected && !writeCallbackReceived

internal enum class NotificationSubscriptionPhase { KNOWN_BT638, REMAINING }

internal enum class NotificationSubscriptionEvent {
    STARTED,
    SUCCEEDED,
    FAILED,
    TIMEOUT,
    LOCAL_ENABLE_FAILED,
    CCCD_MISSING
}

internal data class NotificationCharacteristicKey(
    val serviceUuid: String,
    val characteristicUuid: String
)

internal data class NotificationInventoryEntry(
    val serviceUuid: String,
    val characteristicUuid: String,
    val properties: Int,
    val phase: NotificationSubscriptionPhase
)

internal fun notificationCharacteristicKey(
    serviceUuid: String,
    characteristicUuid: String
): NotificationCharacteristicKey = NotificationCharacteristicKey(
    serviceUuid = serviceUuid.lowercase(),
    characteristicUuid = characteristicUuid.lowercase()
)

internal fun notificationSubscriptionPhase(serviceUuid: String): NotificationSubscriptionPhase =
    when (normalizeGattShortUuid(serviceUuid)) {
        "1500", "1600" -> NotificationSubscriptionPhase.KNOWN_BT638
        else -> NotificationSubscriptionPhase.REMAINING
    }

internal fun notificationSubscriptionPriority(serviceUuid: String, characteristicUuid: String): Int {
    val service = normalizeGattShortUuid(serviceUuid)
    val characteristic = normalizeGattShortUuid(characteristicUuid)
    return when {
        characteristic == "1509" -> 0
        service == "1500" -> 10
        characteristic == "160C" -> 20
        service == "1600" -> 30
        service == "1E00" -> 40
        else -> 100
    }
}

private const val DA1A_UUID_SUFFIX = "-d532-4285-be94-b07a3e11a098"
private val BT638_LIVE_SERVICE_SHORT_IDS = setOf("1500", "1600", "1E00")
private val DIAGNOSTIC_ONLY_CHARACTERISTIC_SHORT_IDS =
    setOf("1514", "1516", "1517", "1518", "2A25")

/**
 * A short UUID alone is not a routing identity. Only the observed DA1A
 * service/characteristic family pairs may feed live state or decoder learning;
 * notifications from every other discovered service remain diagnostic RAW data.
 */
internal fun isBt638LiveNotificationRoute(
    serviceUuid: String,
    characteristicUuid: String
): Boolean {
    val normalizedServiceUuid = serviceUuid.lowercase()
    val normalizedCharacteristicUuid = characteristicUuid.lowercase()
    val serviceShort = normalizeGattShortUuid(normalizedServiceUuid)
    val characteristicShort = normalizeGattShortUuid(normalizedCharacteristicUuid)
    if (serviceShort !in BT638_LIVE_SERVICE_SHORT_IDS) return false
    if (characteristicShort in DIAGNOSTIC_ONLY_CHARACTERISTIC_SHORT_IDS) return false
    if (normalizedServiceUuid != "da1a${serviceShort.lowercase()}$DA1A_UUID_SUFFIX") return false
    if (normalizedCharacteristicUuid != "da1a${characteristicShort.lowercase()}$DA1A_UUID_SUFFIX") return false
    return characteristicShort.take(2) == serviceShort.take(2)
}

internal fun isDiagnosticReadAllowed(connectionEpoch: Long, reconnectRequiredEpoch: Long?): Boolean =
    reconnectRequiredEpoch != connectionEpoch

internal enum class GattConnectionStage { CONNECTING, DISCOVERING_SERVICES }

internal data class GattConnectionDeadline(
    val connectionEpoch: Long,
    val stage: GattConnectionStage
)

internal fun matchesGattConnectionDeadline(
    expected: GattConnectionDeadline,
    actualConnectionEpoch: Long,
    actualStage: GattConnectionStage
): Boolean = expected.connectionEpoch == actualConnectionEpoch && expected.stage == actualStage

internal enum class GattConnectedCallbackDisposition {
    ACCEPT,
    IGNORE_STALE_OR_DUPLICATE,
    FAIL_GATT_STATUS
}

internal fun gattConnectedCallbackDisposition(
    deadline: GattConnectionDeadline?,
    connectionEpoch: Long,
    gattSuccess: Boolean
): GattConnectedCallbackDisposition {
    val expected = GattConnectionDeadline(connectionEpoch, GattConnectionStage.CONNECTING)
    if (deadline != expected) return GattConnectedCallbackDisposition.IGNORE_STALE_OR_DUPLICATE
    return if (gattSuccess) {
        GattConnectedCallbackDisposition.ACCEPT
    } else {
        GattConnectedCallbackDisposition.FAIL_GATT_STATUS
    }
}

internal enum class GattServicesCallbackDisposition {
    ACCEPT,
    IGNORE_STALE_OR_DUPLICATE,
    FAIL_GATT_STATUS,
    FAIL_EMPTY_SERVICES
}

internal fun gattServicesCallbackDisposition(
    deadline: GattConnectionDeadline?,
    connectionEpoch: Long,
    gattSuccess: Boolean,
    serviceCount: Int
): GattServicesCallbackDisposition {
    val expected = GattConnectionDeadline(connectionEpoch, GattConnectionStage.DISCOVERING_SERVICES)
    if (deadline != expected) return GattServicesCallbackDisposition.IGNORE_STALE_OR_DUPLICATE
    if (!gattSuccess) return GattServicesCallbackDisposition.FAIL_GATT_STATUS
    if (serviceCount == 0) return GattServicesCallbackDisposition.FAIL_EMPTY_SERVICES
    return GattServicesCallbackDisposition.ACCEPT
}

internal fun linkDiagnosticRecordsToMeasurement(
    records: List<DiagnosticReadRecord>,
    measurementStartedAt: Long,
    measurementEpochByGattEpoch: Map<Long, Int>
): List<DiagnosticReadRecord> = records
    .filter { it.timestampMs >= measurementStartedAt }
    .map { record ->
        record.copy(
            measurementConnectionEpoch = measurementEpochByGattEpoch[record.connectionEpoch]
        )
    }

private fun BluetoothGattCharacteristic.toNotificationInventoryEntry(
    phase: NotificationSubscriptionPhase
): NotificationInventoryEntry = NotificationInventoryEntry(
    serviceUuid = service.uuid.toString().lowercase(),
    characteristicUuid = uuid.toString().lowercase(),
    properties = properties,
    phase = phase
)

private enum class BlePacketOrigin { NOTIFICATION, NOTIFICATION_QUARANTINED, READ }

internal data class DiagnosticGattReadSession(
    internal val gatt: BluetoothGatt,
    val connectionEpoch: Long,
    internal val operationToken: GattOperationToken
)

class BleScooterManager(private val context: Context) {
    companion object {
        val SERVICE_TELEMETRY: UUID =
            UUID.fromString("da1a1500-d532-4285-be94-b07a3e11a098")
        val BATTERY_CHARACTERISTIC: UUID =
            UUID.fromString("da1a1509-d532-4285-be94-b07a3e11a098")
        val SERVICE_MOTOR_TUNING: UUID =
            UUID.fromString("da1a1600-d532-4285-be94-b07a3e11a098")
        val MOTOR_TUNING_READ_CHARACTERISTIC: UUID =
            UUID.fromString("da1a160c-d532-4285-be94-b07a3e11a098")
        val MOTOR_TUNING_WRITE_CHARACTERISTIC: UUID =
            UUID.fromString("da1a160d-d532-4285-be94-b07a3e11a098")
        val START_MODE_LEGACY_WRITE_CHARACTERISTIC: UUID =
            UUID.fromString("da1a1608-d532-4285-be94-b07a3e11a098")
        val START_MODE_MODERN_WRITE_CHARACTERISTIC: UUID =
            UUID.fromString("da1a1a03-d532-4285-be94-b07a3e11a098")
        val CCCD: UUID =
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        const val TARGET_NAME = "BT638"
        private const val NOTIFICATION_DESCRIPTOR_TIMEOUT_MS = 3_000L
        private const val GATT_CONNECT_TIMEOUT_MS = 6_000L
        private const val GATT_SERVICE_DISCOVERY_TIMEOUT_MS = 6_000L
    }

    private data class PendingMotorTuningWrite(
        val profileIndex: Int,
        val expectedValues: Map<MotorTuningParameter, Int>,
        val packetHex: String,
        val reset: Boolean,
        val startedAt: Long,
        val gatt: BluetoothGatt,
        val connectionEpoch: Long,
        val operationToken: GattOperationToken,
        val writeCallbackExpected: Boolean,
        var writeCallbackReceived: Boolean = false,
        var domainConfirmed: Boolean = false,
        var verificationReadScheduled: Boolean = false
    )

    private data class PendingStartModeWrite(
        val mode: VmaxStartMode,
        val startedAt: Long,
        val gatt: BluetoothGatt,
        val connectionEpoch: Long,
        val operationToken: GattOperationToken,
        val writeCallbackExpected: Boolean,
        var writeCallbackReceived: Boolean = false,
        var domainConfirmed: Boolean = false
    )

    private data class ActiveGattConnection(
        val gatt: BluetoothGatt,
        val epoch: Long
    )

    private data class MeasurementExportSnapshot(
        val rawRows: List<String>,
        val markerRows: List<String>,
        val telemetryRows: List<String>,
        val startedAt: Long,
        val connectionCount: Int,
        val receivedNotifications: Int,
        val acceptedNotifications: Int,
        val rejectedReads: Int,
        val rejectedHybrids: Int,
        val diagnosticNotifications: Int,
        val diagnosticReadBundles: List<DiagnosticReadBundle>
    )

    private val bluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter: BluetoothAdapter? get() = bluetoothManager.adapter
    private val gattOperationLock = Any()
    private val gattOperationCoordinator = GattOperationCoordinator()
    @Volatile
    private var activeGattConnection: ActiveGattConnection? = null
    private val gatt: BluetoothGatt?
        get() = activeGattConnection?.gatt
    private val notificationQueue = ArrayDeque<BluetoothGattCharacteristic>()
    private val deferredKnownNotificationQueue = ArrayDeque<BluetoothGattCharacteristic>()
    private val deferredNotificationQueue = ArrayDeque<BluetoothGattCharacteristic>()
    private var notificationSubscriptionPhase = NotificationSubscriptionPhase.KNOWN_BT638
    private var descriptorWriteRunning = false
    private var descriptorWriteTimeout: Runnable? = null
    private var descriptorWriteKey: NotificationCharacteristicKey? = null
    private var gattConnectionStageTimeout: Runnable? = null
    private var gattConnectionDeadline: GattConnectionDeadline? = null
    private var notificationSetupOperation: GattOperationToken? = null
    private var motorTuningOperation: GattOperationToken? = null
    private var diagnosticGattReadSession: DiagnosticGattReadSession? = null
    private var diagnosticGattReadScanner: GattReadScanner? = null
    @Volatile private var diagnosticGattReadObserver: GattReadScanner? = null
    private var diagnosticReadReconnectRequiredEpoch: Long? = null
    private var diagnosticReadFirstEpoch: Long? = null
    private var powerCriticalNotificationEpoch: Long? = null
    private var powerCriticalNotificationSucceededEpoch: Long? = null
    private var deferredMotorTuningReadEpoch: Long? = null
    private val measurementDiagnosticReadBundles = mutableListOf<DiagnosticReadBundle>()

    private val decoderLab = DecoderLabEngine()
    private val secureStore = SecureTelemetryStore(context)
    private val learningStore = LearningProfileStore(context)
    private val historyStore = SessionHistoryStore(context)
    private val previousValues = mutableMapOf<String, ByteArray>()
    private val channelPacketCounts = mutableMapOf<String, Int>()
    private val sessionRows = mutableListOf<String>()
    private val markerRows = mutableListOf<String>()
    private val telemetryRows = mutableListOf<String>()
    private val packetTimes = ArrayDeque<Long>()
    private var sessionStartedAt = System.currentTimeMillis()
    private var measurementStartedAt = 0L
    private var recordingActive = false
    private var recordingPaused = false
    private var measurementConnectionEpoch = 0
    private val measurementEpochByGattEpoch = mutableMapOf<Long, Int>()
    private var receivedNotificationPackets = 0
    private var acceptedNotificationPackets = 0
    private var rejectedReadPackets = 0
    private var rejectedHybridPackets = 0
    private var diagnosticOnlyNotificationPackets = 0
    private var nextScanIntent = 0L
    private var activeScanIntent: Long? = null
    private var activeScanCallback: ScanCallback? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private val motorTuningBuffer = mutableListOf<Byte>()
    private var pendingMotorTuningWrite: PendingMotorTuningWrite? = null
    private var lastMotorTuningReadRequestAt = 0L
    @Volatile
    private var legacyStartModeCharacteristicAvailable = false
    @Volatile
    private var pendingStartModeWrite: PendingStartModeWrite? = null

    private val _state = MutableStateFlow(
        ScooterState(
            encryptedReports = secureStore.count(),
            learningProfileCount = learningStore.count(),
            sessionHistoryCount = historyStore.count()
        )
    )
    val state: StateFlow<ScooterState> = _state

    fun hasRequiredPermissions(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) ==
                PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        }

    internal fun registerDiagnosticGattReadObserver(scanner: GattReadScanner) {
        diagnosticGattReadObserver = scanner
        activeGattConnection?.epoch?.let(scanner::onDiagnosticConnectionOpened)
    }

    internal fun currentDiagnosticConnectionEpoch(): Long? = activeGattConnection?.epoch

    internal fun beginDiagnosticGattReadScan(scanner: GattReadScanner): DiagnosticGattReadSession? =
        synchronized(gattOperationLock) {
            val active = activeGattConnection
            val snapshot = _state.value
            val notificationQueueBlocks = notificationQueue.isNotEmpty() &&
                diagnosticReadFirstEpoch != active?.epoch
            val blocked = active == null || !snapshot.connected || descriptorWriteRunning ||
                gattConnectionDeadline != null ||
                active?.gatt?.services.isNullOrEmpty() ||
                notificationQueueBlocks || pendingStartModeWrite != null ||
                pendingMotorTuningWrite != null || snapshot.motorTuningBusy ||
                (active != null && !isDiagnosticReadAllowed(active.epoch, diagnosticReadReconnectRequiredEpoch)) ||
                gattOperationCoordinator.currentKind(active?.epoch ?: -1L) != null
            if (blocked) {
                addLog(
                    if (active != null && !isDiagnosticReadAllowed(active.epoch, diagnosticReadReconnectRequiredEpoch)) {
                        "GATT-READ-Scan gesperrt: nach Timeout ist ein Reconnect erforderlich"
                    } else {
                        "GATT-READ-Scan verschoben: anderer BLE-Vorgang aktiv"
                    }
                )
                return@synchronized null
            }
            val operation = gattOperationCoordinator.tryBegin(
                active.epoch,
                GattOperationKind.DIAGNOSTIC_READ_SCAN
            ) ?: return@synchronized null
            DiagnosticGattReadSession(active.gatt, active.epoch, operation).also { session ->
                diagnosticGattReadSession = session
                diagnosticGattReadScanner = scanner
                update {
                    it.copy(
                        diagnosticGattReadRunning = true,
                        gattOperationBusy = true
                    )
                }
            }
        }

    internal fun diagnosticGattServices(session: DiagnosticGattReadSession): List<BluetoothGattService>? =
        synchronized(gattOperationLock) {
            if (!isCurrentDiagnosticSessionLocked(session)) null
            else session.gatt.services?.toList().orEmpty()
        }

    @SuppressLint("MissingPermission")
    internal fun readDiagnosticCharacteristic(
        session: DiagnosticGattReadSession,
        characteristic: BluetoothGattCharacteristic
    ): Boolean = synchronized(gattOperationLock) {
        if (!isCurrentDiagnosticSessionLocked(session)) return@synchronized false
        runCatching { session.gatt.readCharacteristic(characteristic) }.getOrDefault(false)
    }

    internal fun finishDiagnosticGattReadScan(
        session: DiagnosticGattReadSession,
        scanner: GattReadScanner
    ) = synchronized(gattOperationLock) {
        if (diagnosticGattReadSession != session || diagnosticGattReadScanner !== scanner) return@synchronized
        gattOperationCoordinator.finish(session.operationToken)
        diagnosticGattReadSession = null
        diagnosticGattReadScanner = null
        update { it.copy(diagnosticGattReadRunning = false, gattOperationBusy = false) }
        resumeDeferredGattSetupLocked(session.connectionEpoch)
    }

    internal fun cancelDiagnosticGattReadScan(
        session: DiagnosticGattReadSession,
        scanner: GattReadScanner,
        resumeDeferredSetup: Boolean = true
    ) = synchronized(gattOperationLock) {
        if (diagnosticGattReadSession != session || diagnosticGattReadScanner !== scanner) return@synchronized
        gattOperationCoordinator.finish(session.operationToken)
        diagnosticGattReadSession = null
        diagnosticGattReadScanner = null
        if (!resumeDeferredSetup && diagnosticReadFirstEpoch == session.connectionEpoch) {
            diagnosticReadFirstEpoch = null
            notificationQueue.clear()
            deferredKnownNotificationQueue.clear()
            deferredNotificationQueue.clear()
            powerCriticalNotificationEpoch = null
            powerCriticalNotificationSucceededEpoch = null
        }
        update { it.copy(diagnosticGattReadRunning = false, gattOperationBusy = false) }
        if (resumeDeferredSetup) resumeDeferredGattSetupLocked(session.connectionEpoch)
    }

    @SuppressLint("MissingPermission")
    internal fun timeoutDiagnosticGattReadScan(
        session: DiagnosticGattReadSession,
        scanner: GattReadScanner
    ) = synchronized(gattOperationLock) {
        if (diagnosticGattReadSession != session || diagnosticGattReadScanner !== scanner) return@synchronized
        diagnosticReadReconnectRequiredEpoch = session.connectionEpoch
        gattOperationCoordinator.finish(session.operationToken)
        diagnosticGattReadSession = null
        diagnosticGattReadScanner = null
        notificationQueue.clear()
        deferredKnownNotificationQueue.clear()
        deferredNotificationQueue.clear()
        diagnosticReadFirstEpoch = null
        powerCriticalNotificationEpoch = null
        powerCriticalNotificationSucceededEpoch = null
        deferredMotorTuningReadEpoch = null
        update {
            it.copy(
                diagnosticGattReadRunning = false,
                gattOperationBusy = false,
                status = "Deep READ Timeout – für weitere READs neu verbinden"
            )
        }
        activeGattConnection?.takeIf {
            it.gatt === session.gatt && it.epoch == session.connectionEpoch
        }?.let { active ->
            forceCloseGattEpochLocked(active, "Deep READ ohne Callback")
        }
    }

    private fun resumeDeferredGattSetupLocked(connectionEpoch: Long) {
        val active = activeGattConnection
        if (active == null || active.epoch != connectionEpoch || !_state.value.connected ||
            !isDiagnosticReadAllowed(connectionEpoch, diagnosticReadReconnectRequiredEpoch)
        ) return

        if (diagnosticReadFirstEpoch == connectionEpoch) {
            val diagnosticObserver = diagnosticGattReadObserver
            if (diagnosticObserver?.hasPendingReadRequest(connectionEpoch) == true) {
                diagnosticObserver.onDiagnosticGattSlotAvailable(connectionEpoch)
                return
            }
            if (deferredKnownNotificationQueue.isNotEmpty()) {
                val operation = gattOperationCoordinator.tryBegin(
                    connectionEpoch,
                    GattOperationKind.NOTIFICATION_SETUP
                ) ?: return
                notificationSetupOperation = operation
                notificationSubscriptionPhase = NotificationSubscriptionPhase.KNOWN_BT638
                notificationQueue.clear()
                notificationQueue.addAll(deferredKnownNotificationQueue)
                deferredKnownNotificationQueue.clear()
                update {
                    it.copy(
                        status = "Deep READ gesichert – aktiviere bekannte BT638-Notifications",
                        gattOperationBusy = true
                    )
                }
                enableNextNotification(active.gatt, connectionEpoch)
                return
            }
            diagnosticReadFirstEpoch = null
            deferredMotorTuningReadEpoch = connectionEpoch
        }

        if (deferredNotificationQueue.isNotEmpty()) {
            val operation = gattOperationCoordinator.tryBegin(
                connectionEpoch,
                GattOperationKind.NOTIFICATION_SETUP
            ) ?: return
            notificationSetupOperation = operation
            notificationSubscriptionPhase = NotificationSubscriptionPhase.REMAINING
            notificationQueue.clear()
            notificationQueue.addAll(deferredNotificationQueue)
            deferredNotificationQueue.clear()
            update {
                it.copy(
                    status = "Deep READ gesichert – aktiviere übrige Notify-Kanäle",
                    gattOperationBusy = true
                )
            }
            enableNextNotification(active.gatt, connectionEpoch)
            return
        }

        val diagnosticObserver = diagnosticGattReadObserver
        if (diagnosticObserver?.hasPendingReadRequest(connectionEpoch) == true) {
            diagnosticObserver.onDiagnosticGattSlotAvailable(connectionEpoch)
            return
        }

        if (deferredMotorTuningReadEpoch == connectionEpoch) {
            deferredMotorTuningReadEpoch = null
            if (_state.value.motorTuningReadAvailable) {
                mainHandler.post { readMotorTuningValues() }
            }
        }
    }

    /** Keeps diagnostic READ evidence separate while making it available to the measurement export. */
    internal fun retainDiagnosticReadBundle(bundle: DiagnosticReadBundle) = synchronized(gattOperationLock) {
        if (bundle.records.isEmpty()) return@synchronized
        val inMeasurementRecords = linkDiagnosticRecordsToMeasurement(
            records = bundle.records,
            measurementStartedAt = measurementStartedAt,
            measurementEpochByGattEpoch = measurementEpochByGattEpoch
        )
        val frozen = bundle.copy(
            records = inMeasurementRecords,
            scanStartedAt = maxOf(bundle.scanStartedAt, measurementStartedAt)
        )
        if (recordingActive && frozen.records.isNotEmpty() &&
            frozen.scanFinishedAt >= measurementStartedAt
        ) {
            addMeasurementDiagnosticBundleLocked(frozen)
        }
    }

    private fun addMeasurementDiagnosticBundleLocked(bundle: DiagnosticReadBundle) {
        val linked = bundle.copy(records = bundle.records.toList())
        val duplicate = measurementDiagnosticReadBundles.any {
            it.scanId == linked.scanId
        }
        if (!duplicate) measurementDiagnosticReadBundles += linked
    }

    private fun isCurrentDiagnosticSessionLocked(session: DiagnosticGattReadSession): Boolean {
        val active = activeGattConnection ?: return false
        return diagnosticGattReadSession == session && active.gatt === session.gatt &&
            active.epoch == session.connectionEpoch && gattOperationCoordinator.isCurrent(session.operationToken)
    }

    private fun cancelDiagnosticGattReadScanLocked() {
        val session = diagnosticGattReadSession ?: return
        val scanner = diagnosticGattReadScanner
        gattOperationCoordinator.finish(session.operationToken)
        diagnosticGattReadSession = null
        diagnosticGattReadScanner = null
        update { it.copy(diagnosticGattReadRunning = false, gattOperationBusy = false) }
        scanner?.onDiagnosticConnectionClosed(session)
    }

    @SuppressLint("MissingPermission")
    fun startScan() {
        if (!hasRequiredPermissions()) {
            addLog("Bluetooth-Berechtigungen fehlen")
            return
        }
        if (adapter?.isEnabled != true) {
            update { it.copy(status = "Bluetooth ist ausgeschaltet") }
            return
        }
        val platformScanner = adapter?.bluetoothLeScanner
        if (platformScanner == null) {
            update { it.copy(scanning = false, status = "BLE-Scanner nicht verfügbar") }
            return
        }
        val callback = synchronized(gattOperationLock) {
            val allowed = shouldStartBleScan(
                scanning = _state.value.scanning,
                connected = _state.value.connected,
                connectionAllocated = activeGattConnection != null
            )
            if (!allowed) return@synchronized null
            val intent = ++nextScanIntent
            createScanCallback(intent).also {
                activeScanIntent = intent
                activeScanCallback = it
                update { state -> state.copy(scanning = true, status = "Suche nach $TARGET_NAME …") }
            }
        }
        if (callback == null) return
        runCatching { platformScanner.startScan(callback) }
            .onSuccess {
                val stillCurrent = synchronized(gattOperationLock) {
                    activeScanCallback === callback && _state.value.scanning
                }
                if (!stillCurrent) {
                    // stopScan/disconnect may have won between publishing the
                    // intent and Android accepting startScan.
                    runCatching { platformScanner.stopScan(callback) }
                }
            }
            .onFailure { error ->
                synchronized(gattOperationLock) {
                    if (activeScanCallback === callback) {
                        activeScanCallback = null
                        activeScanIntent = null
                        update {
                            it.copy(
                                scanning = false,
                                status = "BLE-Suche konnte nicht gestartet werden: ${error.message ?: error.javaClass.simpleName}"
                            )
                        }
                    }
                }
            }
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        synchronized(gattOperationLock) {
            stopActiveBleScanLocked()
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopActiveBleScanLocked() {
        val callback = activeScanCallback
        activeScanCallback = null
        activeScanIntent = null
        nextScanIntent++
        if (callback != null && hasRequiredPermissions()) {
            runCatching { adapter?.bluetoothLeScanner?.stopScan(callback) }
        }
        update { it.copy(scanning = false) }
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        decoderLab.cancel()
        diagnosticGattReadObserver?.finalizeForMeasurementExport(
            resumeDeferredSetup = false,
            forceAttempt = false
        )
        diagnosticGattReadObserver?.cancelPendingAttemptForUserDisconnect()
        if (recordingActive) stopMeasurementAndExportAfterDiagnosticFlush()
        mainHandler.removeCallbacksAndMessages(null)
        synchronized(gattOperationLock) {
            val active = activeGattConnection
            stopActiveBleScanLocked()
            cancelGattConnectionStageTimeoutLocked()
            cancelDiagnosticGattReadScanLocked()
            cancelDescriptorWriteTimeoutLocked()
            pendingMotorTuningWrite = null
            pendingStartModeWrite = null
            notificationSetupOperation = null
            motorTuningOperation = null
            legacyStartModeCharacteristicAvailable = false
            lastMotorTuningReadRequestAt = 0L
            motorTuningBuffer.clear()
            previousValues.clear()
            channelPacketCounts.clear()
            notificationQueue.clear()
            deferredKnownNotificationQueue.clear()
            deferredNotificationQueue.clear()
            deferredMotorTuningReadEpoch = null
            diagnosticReadReconnectRequiredEpoch = null
            diagnosticReadFirstEpoch = null
            powerCriticalNotificationEpoch = null
            powerCriticalNotificationSucceededEpoch = null
            descriptorWriteRunning = false
            active?.gatt?.disconnect()
            active?.gatt?.close()
            active?.let { gattOperationCoordinator.closeConnection(it.epoch) }
            activeGattConnection = null
            val lastEpoch = active?.epoch ?: _state.value.connectionEpoch
            update {
                it.clearConnectionScopedTelemetry(lastEpoch).copy(
                    connected = false,
                    scanning = false,
                    status = "Getrennt",
                    labRunning = false,
                    motorTuningSupported = false,
                    motorTuningReadAvailable = false,
                    motorTuningWriteAvailable = false,
                    motorTuningBusy = false,
                    motorTuningStatus = "Nicht verbunden",
                    motorTuningProfiles = emptyList(),
                    motorTuningOriginalProfiles = emptyList(),
                    motorTuningLastVerified = null,
                    startModeWriteAvailable = false,
                    startModeBusy = false,
                    startModeStatus = "Nicht verbunden"
                )
            }
        }
    }

    fun startLabBaseline(action: String) {
        if (!_state.value.connected) {
            addLog("Decoder Lab benötigt eine BLE-Verbindung")
            return
        }
        decoderLab.startBaseline(action)
        update {
            it.copy(
                labRunning = true,
                labAction = action,
                labPhase = "1/2 Grundzustand aufnehmen",
                labCandidates = emptyList()
            )
        }
        addLog("Decoder Lab: Grundzustand für '$action'")
    }

    fun startLabAction() {
        if (!_state.value.labRunning) return
        decoderLab.startActive()
        update { it.copy(labPhase = "2/2 Aktion jetzt ausführen") }
        addLog("Decoder Lab: Aktion jetzt ausführen")
    }

    fun finishLab() {
        if (!_state.value.labRunning) return
        val candidates = decoderLab.finish()
        val report = buildReport(_state.value.labAction, candidates, _state.value.rawPackets)
        runCatching { secureStore.saveEncrypted(report) }
            .onFailure { addLog("Verschlüsseltes Speichern fehlgeschlagen: ${it.message}") }

        update {
            it.copy(
                labRunning = false,
                labPhase = "Analyse fertig",
                labCandidates = candidates,
                encryptedReports = secureStore.count()
            )
        }
        addLog("Decoder Lab: ${candidates.size} Kandidaten gefunden")
    }

    @SuppressLint("MissingPermission")
    private fun connect(result: ScanResult, scanIntent: Long) {
        val device = result.device
        val observedAt = System.currentTimeMillis()
        val observedName = device.name ?: result.scanRecord?.deviceName ?: TARGET_NAME
        val advertisement = result.scanRecord?.bytes?.copyOf().orEmpty()
        synchronized(gattOperationLock) {
            if (!shouldAcceptBleScanResult(_state.value.scanning, activeScanIntent, scanIntent)) return
            // Several matching scan callbacks may already be queued when stopScan()
            // takes effect. Allocate exactly one physical GATT connection.
            if (activeGattConnection != null || _state.value.connected) return
            stopActiveBleScanLocked()
            update {
                it.copy(
                    status = "Verbinde …",
                    deviceName = observedName,
                    address = device.address,
                    motorTuningProfiles = emptyList(),
                    motorTuningOriginalProfiles = emptyList(),
                    motorTuningStatus = "Prüfe Motor-Tuning-Dienst",
                    motorTuningLastVerified = null
                )
            }
            val previous = activeGattConnection
            cancelDiagnosticGattReadScanLocked()
            previous?.gatt?.close()
            previous?.let { gattOperationCoordinator.closeConnection(it.epoch) }
            notificationSetupOperation = null
            motorTuningOperation = null
            notificationQueue.clear()
            deferredKnownNotificationQueue.clear()
            deferredNotificationQueue.clear()
            deferredMotorTuningReadEpoch = null
            diagnosticReadFirstEpoch = null
            powerCriticalNotificationEpoch = null
            powerCriticalNotificationSucceededEpoch = null
            cancelDescriptorWriteTimeoutLocked()
            descriptorWriteRunning = false
            val newGatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            val epoch = gattOperationCoordinator.openConnection()
            val active = ActiveGattConnection(newGatt, epoch)
            activeGattConnection = active
            diagnosticGattReadObserver?.onDiagnosticConnectionOpened(epoch)
            diagnosticGattReadObserver?.onDiagnosticAdvertisementObserved(
                connectionEpoch = epoch,
                timestampMs = observedAt,
                rssi = result.rssi,
                deviceName = observedName,
                payload = advertisement
            )
            scheduleGattConnectionStageTimeoutLocked(active, GattConnectionStage.CONNECTING)
        }
    }

    private fun scheduleGattConnectionStageTimeoutLocked(
        active: ActiveGattConnection,
        stage: GattConnectionStage
    ) {
        cancelGattConnectionStageTimeoutLocked()
        val deadline = GattConnectionDeadline(active.epoch, stage)
        val timeout = Runnable {
            synchronized(gattOperationLock) {
                val current = activeGattConnection ?: return@synchronized
                if (current.gatt !== active.gatt || gattConnectionDeadline != deadline ||
                    !matchesGattConnectionDeadline(deadline, current.epoch, stage)
                ) return@synchronized
                failGattConnectionStageLocked(
                    active = current,
                    stage = stage,
                    reason = when (stage) {
                        GattConnectionStage.CONNECTING ->
                            "BT638-Verbindung ohne Android-Callback"
                        GattConnectionStage.DISCOVERING_SERVICES ->
                            "BT638 Service Discovery ohne Android-Callback"
                    }
                )
            }
        }
        gattConnectionDeadline = deadline
        gattConnectionStageTimeout = timeout
        mainHandler.postDelayed(
            timeout,
            when (stage) {
                GattConnectionStage.CONNECTING -> GATT_CONNECT_TIMEOUT_MS
                GattConnectionStage.DISCOVERING_SERVICES -> GATT_SERVICE_DISCOVERY_TIMEOUT_MS
            }
        )
    }

    private fun cancelGattConnectionStageTimeoutLocked() {
        gattConnectionStageTimeout?.let(mainHandler::removeCallbacks)
        gattConnectionStageTimeout = null
        gattConnectionDeadline = null
    }

    @SuppressLint("MissingPermission")
    private fun failGattConnectionStageLocked(
        active: ActiveGattConnection,
        stage: GattConnectionStage,
        reason: String
    ) {
        val current = activeGattConnection
        if (current == null || current.gatt !== active.gatt || current.epoch != active.epoch) return
        cancelGattConnectionStageTimeoutLocked()
        diagnosticGattReadObserver?.onDiagnosticConnectionStageTimeout(active.epoch, stage, reason)
        forceCloseGattEpochLocked(active, reason)
    }

    /**
     * Permanently retires one exact poisoned Android GATT instance. Waiting only
     * for STATE_DISCONNECTED is unsafe because the platform may omit that callback.
     */
    @SuppressLint("MissingPermission")
    private fun forceCloseGattEpochLocked(active: ActiveGattConnection, reason: String) {
        val current = activeGattConnection
        if (current == null || current.gatt !== active.gatt || current.epoch != active.epoch) return
        cancelGattConnectionStageTimeoutLocked()
        val diagnosticSessionWasActive = diagnosticGattReadSession != null
        cancelDiagnosticGattReadScanLocked()
        if (!diagnosticSessionWasActive) {
            diagnosticGattReadObserver?.onDiagnosticConnectionClosedBeforeRead(active.epoch)
        }
        cancelDescriptorWriteTimeoutLocked()
        descriptorWriteRunning = false
        notificationQueue.clear()
        deferredKnownNotificationQueue.clear()
        deferredNotificationQueue.clear()
        deferredMotorTuningReadEpoch = null
        diagnosticReadFirstEpoch = null
        powerCriticalNotificationEpoch = null
        powerCriticalNotificationSucceededEpoch = null
        pendingMotorTuningWrite = null
        pendingStartModeWrite = null
        notificationSetupOperation = null
        motorTuningOperation = null
        legacyStartModeCharacteristicAvailable = false
        lastMotorTuningReadRequestAt = 0L
        gattOperationCoordinator.closeConnection(active.epoch)
        activeGattConnection = null
        update {
            it.clearConnectionScopedTelemetry(active.epoch).copy(
                connected = false,
                scanning = false,
                status = "$reason – Teil-Dump gesichert; neuer Versuch möglich",
                labRunning = false,
                motorTuningBusy = false,
                motorTuningStatus = "Verbindung wird neu aufgebaut",
                startModeWriteAvailable = false,
                startModeBusy = false,
                startModeStatus = "Verbindung wird neu aufgebaut"
            )
        }
        addLog("$reason; GATT-Epoche ${active.epoch} sicher geschlossen")
        runCatching { active.gatt.disconnect() }
        runCatching { active.gatt.close() }
    }

    private fun createScanCallback(scanIntent: Long): ScanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val accepted = synchronized(gattOperationLock) {
                activeScanCallback === this &&
                    shouldAcceptBleScanResult(_state.value.scanning, activeScanIntent, scanIntent)
            }
            if (!accepted) return
            val name = result.device.name ?: result.scanRecord?.deviceName
            if (name == TARGET_NAME) {
                addLog("$TARGET_NAME gefunden")
                update { it.copy(rssi = result.rssi) }
                connect(result, scanIntent)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            synchronized(gattOperationLock) {
                if (activeScanCallback !== this || activeScanIntent != scanIntent) return@synchronized
                activeScanCallback = null
                activeScanIntent = null
                nextScanIntent++
                update { it.copy(scanning = false, status = "Scanfehler: $errorCode") }
            }
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            synchronized(gattOperationLock) {
                if (gatt !== g) return@synchronized
                val active = activeGattConnection ?: return@synchronized
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        when (
                            gattConnectedCallbackDisposition(
                                deadline = gattConnectionDeadline,
                                connectionEpoch = active.epoch,
                                gattSuccess = status == BluetoothGatt.GATT_SUCCESS
                            )
                        ) {
                            GattConnectedCallbackDisposition.IGNORE_STALE_OR_DUPLICATE -> {
                                addLog("Verspäteter/duplizierter CONNECTED-Callback verworfen")
                                return@synchronized
                            }
                            GattConnectedCallbackDisposition.FAIL_GATT_STATUS -> {
                                failGattConnectionStageLocked(
                                    active,
                                    GattConnectionStage.CONNECTING,
                                    "BT638-Verbindung fehlgeschlagen (GATT $status)"
                                )
                                return@synchronized
                            }
                            GattConnectedCallbackDisposition.ACCEPT -> Unit
                        }
                        cancelGattConnectionStageTimeoutLocked()
                        diagnosticReadReconnectRequiredEpoch = null
                        if (recordingActive) {
                            measurementConnectionEpoch++
                            measurementEpochByGattEpoch[active.epoch] = measurementConnectionEpoch
                        }
                        sessionStartedAt = System.currentTimeMillis()
                        // A reconnect starts fresh live state, but it must not erase
                        // packets already captured by the same measurement session.
                        if (!recordingActive) sessionRows.clear()
                        previousValues.clear()
                        channelPacketCounts.clear()
                        packetTimes.clear()
                        motorTuningBuffer.clear()
                        pendingMotorTuningWrite = null
                        pendingStartModeWrite = null
                        notificationSetupOperation = null
                        motorTuningOperation = null
                        notificationQueue.clear()
                        deferredKnownNotificationQueue.clear()
                        deferredNotificationQueue.clear()
                        deferredMotorTuningReadEpoch = null
                        diagnosticReadFirstEpoch = null
                        powerCriticalNotificationEpoch = null
                        powerCriticalNotificationSucceededEpoch = null
                        cancelDescriptorWriteTimeoutLocked()
                        descriptorWriteRunning = false
                        legacyStartModeCharacteristicAvailable = false
                        lastMotorTuningReadRequestAt = 0L
                        update {
                            it.clearConnectionScopedTelemetry(active.epoch).copy(
                                connected = true,
                                status = "Verbunden – suche Dienste",
                                sessionStartedAt = sessionStartedAt,
                                packetTotal = 0,
                                motorTuningStatus = "Prüfe 160C/160D",
                                motorTuningBusy = false,
                                startModeWriteAvailable = false,
                                startModeBusy = false,
                                startModeStatus = "Prüfe Startmodus-Protokoll"
                            )
                        }
                        addLog("BLE verbunden, Status $status")
                        scheduleGattConnectionStageTimeoutLocked(
                            active,
                            GattConnectionStage.DISCOVERING_SERVICES
                        )
                        val discoveryStarted = runCatching { g.discoverServices() }.getOrDefault(false)
                        if (!discoveryStarted) {
                            failGattConnectionStageLocked(
                                active,
                                GattConnectionStage.DISCOVERING_SERVICES,
                                "BT638 Service Discovery konnte nicht gestartet werden"
                            )
                        }
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        cancelGattConnectionStageTimeoutLocked()
                        val diagnosticSessionWasActive = diagnosticGattReadSession != null
                        cancelDiagnosticGattReadScanLocked()
                        if (!diagnosticSessionWasActive) {
                            diagnosticGattReadObserver?.onDiagnosticConnectionClosedBeforeRead(active.epoch)
                        }
                        cancelDescriptorWriteTimeoutLocked()
                        descriptorWriteRunning = false
                        notificationQueue.clear()
                        deferredKnownNotificationQueue.clear()
                        deferredNotificationQueue.clear()
                        deferredMotorTuningReadEpoch = null
                        diagnosticReadFirstEpoch = null
                        powerCriticalNotificationEpoch = null
                        powerCriticalNotificationSucceededEpoch = null
                        pendingMotorTuningWrite = null
                        pendingStartModeWrite = null
                        notificationSetupOperation = null
                        motorTuningOperation = null
                        legacyStartModeCharacteristicAvailable = false
                        lastMotorTuningReadRequestAt = 0L
                        gattOperationCoordinator.closeConnection(active.epoch)
                        update {
                            it.clearConnectionScopedTelemetry(active.epoch).copy(
                                connected = false,
                                scanning = false,
                                status = "Verbindung getrennt (Status $status)",
                                labRunning = false,
                                motorTuningBusy = false,
                                motorTuningStatus = "Verbindung getrennt",
                                startModeWriteAvailable = false,
                                startModeBusy = false,
                                startModeStatus = "Verbindung getrennt"
                            )
                        }
                        addLog("BLE getrennt, Status $status")
                        g.close()
                        activeGattConnection = null
                    }
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            synchronized(gattOperationLock) {
                if (gatt !== g) return@synchronized
                val active = activeGattConnection ?: return@synchronized
                when (
                    gattServicesCallbackDisposition(
                        deadline = gattConnectionDeadline,
                        connectionEpoch = active.epoch,
                        gattSuccess = status == BluetoothGatt.GATT_SUCCESS,
                        serviceCount = g.services.size
                    )
                ) {
                    GattServicesCallbackDisposition.IGNORE_STALE_OR_DUPLICATE -> {
                        addLog("Verspäteter/duplizierter Service-Discovery-Callback verworfen")
                        return@synchronized
                    }
                    GattServicesCallbackDisposition.FAIL_GATT_STATUS -> {
                        failGattConnectionStageLocked(
                            active,
                            GattConnectionStage.DISCOVERING_SERVICES,
                            "BT638 Service Discovery fehlgeschlagen (GATT $status)"
                        )
                        return@synchronized
                    }
                    GattServicesCallbackDisposition.FAIL_EMPTY_SERVICES -> {
                        failGattConnectionStageLocked(
                            active,
                            GattConnectionStage.DISCOVERING_SERVICES,
                            "BT638 Service Discovery lieferte kein GATT-Inventar"
                        )
                        return@synchronized
                    }
                    GattServicesCallbackDisposition.ACCEPT -> Unit
                }
                cancelGattConnectionStageTimeoutLocked()
                if (g.getService(SERVICE_TELEMETRY) == null) {
                    addLog("Telemetrie-Dienst 1500 nicht gefunden – sichere übriges READ/Notify-Inventar")
                }
                val tuningService = g.getService(SERVICE_MOTOR_TUNING)
                val tuningRead = tuningService?.getCharacteristic(MOTOR_TUNING_READ_CHARACTERISTIC)
                val tuningWrite = tuningService?.getCharacteristic(MOTOR_TUNING_WRITE_CHARACTERISTIC)
                val readAvailable = tuningRead != null
                val writeAvailable = tuningWrite != null &&
                    (tuningWrite.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0 ||
                        tuningWrite.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0)
                val supported = readAvailable && writeAvailable
                val legacyStartModeWrite = tuningService
                    ?.getCharacteristic(START_MODE_LEGACY_WRITE_CHARACTERISTIC)
                val legacyStartModeWritable = legacyStartModeWrite != null &&
                    (legacyStartModeWrite.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0 ||
                        legacyStartModeWrite.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0)
                val modernStartModeRoutePresent = g.services.any { service ->
                    service.getCharacteristic(START_MODE_MODERN_WRITE_CHARACTERISTIC) != null
                }
                // Characteristic topology is the protocol gate: never send the legacy
                // eight-byte frame when the newer 1A03 settings route is exposed.
                legacyStartModeCharacteristicAvailable = legacyStartModeWritable && !modernStartModeRoutePresent

                val notifyChars = g.services
                    .flatMap { service ->
                        service.characteristics.filter { characteristic ->
                            val p = characteristic.properties
                            p and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0 ||
                                p and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0
                        }
                    }
                    // A UUID may legally occur under more than one service. Preserve
                    // the full pair so the GATT inventory never drops a channel.
                    .distinctBy { characteristic ->
                        notificationCharacteristicKey(
                            characteristic.service.uuid.toString(),
                            characteristic.uuid.toString()
                        )
                    }
                    .sortedBy { characteristic ->
                        notificationSubscriptionPriority(
                            characteristic.service.uuid.toString(),
                            characteristic.uuid.toString()
                        )
                    }

                val (knownBt638NotifyChars, remainingNotifyChars) = notifyChars.partition { characteristic ->
                    notificationSubscriptionPhase(characteristic.service.uuid.toString()) ==
                        NotificationSubscriptionPhase.KNOWN_BT638
                }
                diagnosticGattReadObserver?.onNotificationInventoryDiscovered(
                    connectionEpoch = active.epoch,
                    entries = notifyChars.map { characteristic ->
                        NotificationInventoryEntry(
                            serviceUuid = characteristic.service.uuid.toString().lowercase(),
                            characteristicUuid = characteristic.uuid.toString().lowercase(),
                            properties = characteristic.properties,
                            phase = notificationSubscriptionPhase(characteristic.service.uuid.toString())
                        )
                    }
                )

                val diagnosticObserver = diagnosticGattReadObserver
                val readBeforeNotifications =
                    diagnosticObserver?.shouldReadBeforeNotificationSetup(active.epoch) == true
                val criticalBatteryNotifyChars = knownBt638NotifyChars.filter { characteristic ->
                    characteristic.service.uuid == SERVICE_TELEMETRY &&
                        characteristic.uuid == BATTERY_CHARACTERISTIC
                }
                val postReadKnownNotifyChars = if (readBeforeNotifications) {
                    knownBt638NotifyChars.filterNot { it in criticalBatteryNotifyChars }
                } else {
                    emptyList()
                }
                notificationSubscriptionPhase = NotificationSubscriptionPhase.KNOWN_BT638
                notificationQueue.clear()
                notificationQueue.addAll(
                    if (readBeforeNotifications) criticalBatteryNotifyChars else knownBt638NotifyChars
                )
                deferredKnownNotificationQueue.clear()
                deferredKnownNotificationQueue.addAll(postReadKnownNotifyChars)
                deferredNotificationQueue.clear()
                deferredNotificationQueue.addAll(remainingNotifyChars)
                val startNotificationSetup = !readBeforeNotifications || notificationQueue.isNotEmpty()
                val notificationOperation = if (startNotificationSetup) {
                    gattOperationCoordinator.tryBegin(
                        active.epoch,
                        GattOperationKind.NOTIFICATION_SETUP
                    )
                } else {
                    null
                }
                if (startNotificationSetup && notificationOperation == null) {
                    update { it.copy(status = "Dienste gefunden – anderer BLE-Vorgang läuft") }
                    return@synchronized
                }
                notificationSetupOperation = notificationOperation
                diagnosticReadFirstEpoch = active.epoch.takeIf { readBeforeNotifications }
                powerCriticalNotificationEpoch = active.epoch.takeIf {
                    readBeforeNotifications && criticalBatteryNotifyChars.isNotEmpty()
                }
                powerCriticalNotificationSucceededEpoch = null
                addLog("${notifyChars.size} Datenkanäle gefunden")
                addLog(
                    if (readBeforeNotifications) {
                        "POWER-Priorität: ${criticalBatteryNotifyChars.size} kritisches 1509-Abo, dann Deep READ, " +
                            "danach ${postReadKnownNotifyChars.size + remainingNotifyChars.size} weitere Abos"
                    } else {
                        "Notify-Phasen: ${knownBt638NotifyChars.size} bekannte 1500/1600 zuerst, " +
                            "${remainingNotifyChars.size} weitere nach Deep READ"
                    }
                )
                addLog(
                    if (supported) "Motor-Tuning-Kanäle 160C/160D gefunden"
                    else "Motor-Tuning nicht vollständig verfügbar: Lesen=$readAvailable, Schreiben=$writeAvailable"
                )
                addLog(
                    when {
                        modernStartModeRoutePresent -> "Startmodus: neue 1A03-Route erkannt – Legacy-Schreiben gesperrt"
                        legacyStartModeCharacteristicAvailable -> "Startmodus: Legacy-Schreibkanal 1608 gefunden; warte auf 1508/11"
                        else -> "Startmodus: kein sicherer schreibbarer Legacy-Kanal 1608"
                    }
                )
                update {
                    it.copy(
                        status = if (readBeforeNotifications) {
                            if (criticalBatteryNotifyChars.isNotEmpty()) {
                                "POWER-Kurzfenster – sichere zuerst 1509-Livekanal"
                            } else {
                                "POWER-Kurzfenster – Deep READ startet sofort"
                            }
                        } else {
                            "Verbunden – aktiviere Live-Daten"
                        },
                        gattOperationBusy = startNotificationSetup,
                        motorTuningSupported = supported,
                        motorTuningReadAvailable = readAvailable,
                        motorTuningWriteAvailable = writeAvailable,
                        motorTuningStatus = if (supported) "Kanäle gefunden – lese Originalwerte" else "160C/160D nicht vollständig verfügbar",
                        startModeWriteAvailable = false,
                        startModeStatus = when {
                            modernStartModeRoutePresent -> "Neuere Protokollroute erkannt – nur Anzeige"
                            legacyStartModeCharacteristicAvailable -> "Legacy-Schreibroute erkannt – bestätige 1508/11"
                            else -> "Startmodus wird nur angezeigt"
                        }
                    )
                }
                if (readBeforeNotifications && !startNotificationSetup) {
                    diagnosticObserver?.onDiagnosticGattReady(active.epoch)
                } else {
                    enableNextNotification(g, active.epoch)
                }
            }
        }

        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            synchronized(gattOperationLock) {
                if (gatt !== g) return@synchronized
                val active = activeGattConnection ?: return@synchronized
                handleValue(
                    g,
                    active.epoch,
                    characteristic,
                    characteristic.value ?: byteArrayOf(),
                    BlePacketOrigin.NOTIFICATION
                )
            }
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            synchronized(gattOperationLock) {
                if (gatt !== g) return@synchronized
                val active = activeGattConnection ?: return@synchronized
                handleValue(g, active.epoch, characteristic, value, BlePacketOrigin.NOTIFICATION)
            }
        }

        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicRead(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            synchronized(gattOperationLock) {
                if (gatt !== g) return@synchronized
                val active = activeGattConnection ?: return@synchronized
                handleCharacteristicReadCallbackLocked(
                    g,
                    active.epoch,
                    characteristic,
                    characteristic.value ?: byteArrayOf(),
                    status
                )
            }
        }

        override fun onCharacteristicRead(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int
        ) {
            synchronized(gattOperationLock) {
                if (gatt !== g) return@synchronized
                val active = activeGattConnection ?: return@synchronized
                handleCharacteristicReadCallbackLocked(g, active.epoch, characteristic, value, status)
            }
        }

        override fun onCharacteristicWrite(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            synchronized(gattOperationLock) {
                if (gatt !== g) return@synchronized
                val active = activeGattConnection ?: return@synchronized
                if (characteristic.uuid == START_MODE_LEGACY_WRITE_CHARACTERISTIC) {
                    val pending = pendingStartModeWrite
                    if (pending == null || pending.gatt !== g || pending.connectionEpoch != active.epoch ||
                        !gattOperationCoordinator.isCurrent(pending.operationToken)
                    ) {
                        return@synchronized
                    }
                    pending.writeCallbackReceived = true
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        if (pending.domainConfirmed) {
                            finishStartModeWriteLocked("✓ ${pending.mode.label} zurückgelesen und Plattform-Write bestätigt")
                        } else {
                            update { it.copy(startModeStatus = "Gesendet – warte auf 1508/11-Rückmeldung") }
                        }
                    } else {
                        finishStartModeWriteLocked("Schreiben auf 1608 fehlgeschlagen: $status")
                    }
                    return@synchronized
                }
                val pendingMotor = pendingMotorTuningWrite
                if (characteristic.uuid != MOTOR_TUNING_WRITE_CHARACTERISTIC ||
                    pendingMotor == null || pendingMotor.gatt !== g ||
                    pendingMotor.connectionEpoch != active.epoch ||
                    motorTuningOperation != pendingMotor.operationToken ||
                    !gattOperationCoordinator.isCurrent(pendingMotor.operationToken)
                ) return@synchronized
                pendingMotor.writeCallbackReceived = true
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    if (pendingMotor.domainConfirmed) {
                        completeMotorTuningWriteIfSafeLocked(pendingMotor)
                    } else {
                        update { it.copy(motorTuningStatus = "Paket gesendet – lese Bestätigung über 160C") }
                        scheduleMotorTuningVerificationLocked(pendingMotor)
                    }
                } else {
                    finishMotorTuningFailure("Schreiben auf 160D fehlgeschlagen: $status")
                }
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            synchronized(gattOperationLock) {
                if (gatt !== g) return@synchronized
                val active = activeGattConnection ?: return@synchronized
                if (!gattOperationCoordinator.isCurrent(notificationSetupOperation)) return@synchronized
                val characteristic = descriptor.characteristic
                val callbackKey = notificationCharacteristicKey(
                    characteristic.service.uuid.toString(),
                    characteristic.uuid.toString()
                )
                if (!descriptorWriteRunning || descriptorWriteKey != callbackKey) {
                    addLog("Verspäteter/fremder CCCD-Callback ${shortUuid(characteristic.uuid)} ignoriert")
                    return@synchronized
                }
                cancelDescriptorWriteTimeoutLocked()
                descriptorWriteRunning = false
                diagnosticGattReadObserver?.onNotificationSubscriptionEvent(
                    connectionEpoch = active.epoch,
                    entry = characteristic.toNotificationInventoryEntry(notificationSubscriptionPhase),
                    event = if (status == BluetoothGatt.GATT_SUCCESS) {
                        NotificationSubscriptionEvent.SUCCEEDED
                    } else {
                        NotificationSubscriptionEvent.FAILED
                    },
                    status = status
                )
                if (status == BluetoothGatt.GATT_SUCCESS &&
                    powerCriticalNotificationEpoch == active.epoch &&
                    characteristic.service.uuid == SERVICE_TELEMETRY &&
                    characteristic.uuid == BATTERY_CHARACTERISTIC
                ) {
                    powerCriticalNotificationSucceededEpoch = active.epoch
                }
                if (status != BluetoothGatt.GATT_SUCCESS) addLog("Notify-Aktivierung fehlgeschlagen: $status")
                enableNextNotification(g, active.epoch)
            }
        }
    }

    private fun handleCharacteristicReadCallbackLocked(
        g: BluetoothGatt,
        connectionEpoch: Long,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
        status: Int
    ) {
        if (gatt !== g || activeGattConnection?.epoch != connectionEpoch) return
        val session = diagnosticGattReadSession
        val scanner = diagnosticGattReadScanner
        if (session != null && scanner != null && isCurrentDiagnosticSessionLocked(session)) {
            // A diagnostic callback belongs exclusively to the Deep-READ scanner.
            // In particular, 160C must not reach the normal motor parser first.
            scanner.onDiagnosticCharacteristicRead(session, characteristic, value.copyOf(), status)
            return
        }
        if (!isDiagnosticReadAllowed(connectionEpoch, diagnosticReadReconnectRequiredEpoch)) {
            // Android may deliver the old callback after our timeout. There is no
            // request id in BluetoothGattCallback, so this epoch stays poisoned
            // until reconnect instead of attributing stale bytes to another READ.
            addLog("Verspäteter READ ${shortUuid(characteristic.uuid)} nach Timeout verworfen")
            return
        }
        if (status == BluetoothGatt.GATT_SUCCESS) {
            if (characteristic.service.uuid == SERVICE_MOTOR_TUNING &&
                characteristic.uuid == MOTOR_TUNING_READ_CHARACTERISTIC &&
                gattOperationCoordinator.isCurrent(motorTuningOperation)
            ) {
                handleMotorTuningValue(value.copyOf())
            }
            handleValue(g, connectionEpoch, characteristic, value, BlePacketOrigin.READ)
        } else if (characteristic.uuid == MOTOR_TUNING_READ_CHARACTERISTIC &&
            gattOperationCoordinator.isCurrent(motorTuningOperation)
        ) {
            finishMotorTuningFailure("Lesen von 160C fehlgeschlagen: $status")
        }
    }

    @SuppressLint("MissingPermission")
    private fun enableNextNotification(g: BluetoothGatt, connectionEpoch: Long) {
        if (gatt !== g || activeGattConnection?.epoch != connectionEpoch ||
            !gattOperationCoordinator.isCurrent(notificationSetupOperation)
        ) return
        if (descriptorWriteRunning) return
        val characteristic = notificationQueue.pollFirst()
        if (characteristic == null) {
            gattOperationCoordinator.finish(notificationSetupOperation)
            notificationSetupOperation = null
            if (notificationSubscriptionPhase == NotificationSubscriptionPhase.KNOWN_BT638) {
                val powerCriticalAttempted = powerCriticalNotificationEpoch == connectionEpoch
                val powerCriticalSucceeded =
                    powerCriticalNotificationSucceededEpoch == connectionEpoch
                val postReadKnownCompleted = !powerCriticalAttempted &&
                    diagnosticReadFirstEpoch == connectionEpoch
                when {
                    powerCriticalAttempted -> {
                        powerCriticalNotificationEpoch = null
                        powerCriticalNotificationSucceededEpoch = null
                        update {
                            it.copy(
                                status = if (powerCriticalSucceeded) {
                                    "1509-Livekanal bestätigt – POWER-Deep-READ startet"
                                } else {
                                    "1509-Abo nicht bestätigt – POWER-Deep-READ startet trotzdem"
                                },
                                gattOperationBusy = false
                            )
                        }
                        addLog(
                            if (powerCriticalSucceeded) {
                                "Kritischer 1509-Livekanal vor dem POWER-Deep-READ bestätigt"
                            } else {
                                "Kritisches 1509-Abo nicht bestätigt; Deep READ bleibt READ-only aktiv"
                            }
                        )
                        val observer = diagnosticGattReadObserver
                        if (observer != null) observer.onDiagnosticGattReady(connectionEpoch)
                        else resumeDeferredGattSetupLocked(connectionEpoch)
                    }
                    postReadKnownCompleted -> {
                        deferredMotorTuningReadEpoch = connectionEpoch
                        diagnosticReadFirstEpoch = null
                        update {
                            it.copy(
                                status = "Deep READ und bekannte BT638-Notifications gesichert",
                                gattOperationBusy = false
                            )
                        }
                        addLog("Weitere 1500/1600-Benachrichtigungen nach dem POWER-Deep-READ aktiviert")
                        resumeDeferredGattSetupLocked(connectionEpoch)
                    }
                    else -> {
                        deferredMotorTuningReadEpoch = connectionEpoch
                        update {
                            it.copy(
                                status = "Bekannte BT638-Notifications aktiv – Deep READ startet",
                                gattOperationBusy = false
                            )
                        }
                        addLog("Bekannte 1500/1600-Benachrichtigungen aktiviert; Deep READ hat jetzt Vorrang")
                        val observer = diagnosticGattReadObserver
                        if (observer != null) observer.onDiagnosticGattReady(connectionEpoch)
                        else resumeDeferredGattSetupLocked(connectionEpoch)
                    }
                }
            } else {
                update { it.copy(status = "Live-Daten aktiv", gattOperationBusy = false) }
                addLog("Alle entdeckten Benachrichtigungen aktiviert")
                resumeDeferredGattSetupLocked(connectionEpoch)
            }
            return
        }

        if (!g.setCharacteristicNotification(characteristic, true)) {
            addLog("Kanal ${shortUuid(characteristic.uuid)} konnte nicht aktiviert werden")
            diagnosticGattReadObserver?.onNotificationSubscriptionEvent(
                connectionEpoch = connectionEpoch,
                entry = characteristic.toNotificationInventoryEntry(notificationSubscriptionPhase),
                event = NotificationSubscriptionEvent.LOCAL_ENABLE_FAILED,
                status = null
            )
            enableNextNotification(g, connectionEpoch)
            return
        }

        val descriptor = characteristic.getDescriptor(CCCD)
        if (descriptor == null) {
            diagnosticGattReadObserver?.onNotificationSubscriptionEvent(
                connectionEpoch = connectionEpoch,
                entry = characteristic.toNotificationInventoryEntry(notificationSubscriptionPhase),
                event = NotificationSubscriptionEvent.CCCD_MISSING,
                status = null
            )
            enableNextNotification(g, connectionEpoch)
            return
        }

        val value =
            if (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0)
                BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            else BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE

        descriptorWriteRunning = true
        diagnosticGattReadObserver?.onNotificationSubscriptionEvent(
            connectionEpoch = connectionEpoch,
            entry = characteristic.toNotificationInventoryEntry(notificationSubscriptionPhase),
            event = NotificationSubscriptionEvent.STARTED,
            status = null
        )
        val started =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeDescriptor(descriptor, value) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                run {
                    descriptor.value = value
                    g.writeDescriptor(descriptor)
                }
            }

        if (!started) {
            descriptorWriteRunning = false
            diagnosticGattReadObserver?.onNotificationSubscriptionEvent(
                connectionEpoch = connectionEpoch,
                entry = characteristic.toNotificationInventoryEntry(notificationSubscriptionPhase),
                event = NotificationSubscriptionEvent.FAILED,
                status = null
            )
            enableNextNotification(g, connectionEpoch)
        } else {
            scheduleDescriptorWriteTimeoutLocked(g, connectionEpoch, characteristic)
        }
    }

    @SuppressLint("MissingPermission")
    private fun scheduleDescriptorWriteTimeoutLocked(
        g: BluetoothGatt,
        connectionEpoch: Long,
        characteristic: BluetoothGattCharacteristic
    ) {
        cancelDescriptorWriteTimeoutLocked()
        val operation = notificationSetupOperation ?: return
        val key = notificationCharacteristicKey(
            characteristic.service.uuid.toString(),
            characteristic.uuid.toString()
        )
        descriptorWriteKey = key
        val timeout = Runnable {
            synchronized(gattOperationLock) {
                if (gatt !== g || activeGattConnection?.epoch != connectionEpoch ||
                    !descriptorWriteRunning || descriptorWriteKey != key ||
                    notificationSetupOperation != operation ||
                    !gattOperationCoordinator.isCurrent(operation)
                ) return@synchronized

                descriptorWriteTimeout = null
                descriptorWriteKey = null
                descriptorWriteRunning = false
                diagnosticGattReadObserver?.onNotificationSubscriptionEvent(
                    connectionEpoch = connectionEpoch,
                    entry = characteristic.toNotificationInventoryEntry(notificationSubscriptionPhase),
                    event = NotificationSubscriptionEvent.TIMEOUT,
                    status = null
                )
                notificationQueue.clear()
                deferredKnownNotificationQueue.clear()
                deferredNotificationQueue.clear()
                deferredMotorTuningReadEpoch = null
                diagnosticReadFirstEpoch = null
                powerCriticalNotificationEpoch = null
                powerCriticalNotificationSucceededEpoch = null
                diagnosticReadReconnectRequiredEpoch = connectionEpoch
                gattOperationCoordinator.finish(operation)
                notificationSetupOperation = null
                update {
                    it.copy(
                        gattOperationBusy = false,
                        status = "Notify-CCCD ohne Callback – Teil-Dump gesichert; sichere Neuverbindung"
                    )
                }
                addLog("Notify-CCCD ${shortUuid(characteristic.uuid)} Timeout; GATT wird sicher neu aufgebaut")
                activeGattConnection?.takeIf {
                    it.gatt === g && it.epoch == connectionEpoch
                }?.let { active ->
                    forceCloseGattEpochLocked(active, "Notify-CCCD ohne Callback")
                }
            }
        }
        descriptorWriteTimeout = timeout
        mainHandler.postDelayed(timeout, NOTIFICATION_DESCRIPTOR_TIMEOUT_MS)
    }

    private fun cancelDescriptorWriteTimeoutLocked() {
        descriptorWriteTimeout?.let(mainHandler::removeCallbacks)
        descriptorWriteTimeout = null
        descriptorWriteKey = null
    }

    @SuppressLint("MissingPermission")
    fun readMotorTuningValues(verificationRead: Boolean = false) = synchronized(gattOperationLock) {
        val active = activeGattConnection
        val existingOperation = motorTuningOperation
        val ownsExistingOperation = gattOperationCoordinator.isCurrent(existingOperation)
        if (active == null || !_state.value.connected) {
            finishMotorTuningFailureLocked("Motor-Tuning-Lesekanal 160C nicht verfügbar")
            return@synchronized
        }
        if (!isDiagnosticReadAllowed(active.epoch, diagnosticReadReconnectRequiredEpoch)) {
            update { it.copy(motorTuningStatus = "Nach Deep-READ-Timeout ist ein Reconnect erforderlich") }
            return@synchronized
        }
        if (gattConnectionDeadline != null) {
            update { it.copy(motorTuningStatus = "Service Discovery läuft – Lesen verschoben") }
            return@synchronized
        }
        val pendingVerification = pendingMotorTuningWrite
        if (verificationRead && (!ownsExistingOperation || pendingVerification == null ||
                pendingVerification.gatt !== active.gatt ||
                pendingVerification.connectionEpoch != active.epoch ||
                pendingVerification.operationToken != existingOperation)
        ) {
            finishMotorTuningFailureLocked("Motor-Tuning-Bestätigung gehört nicht mehr zur aktuellen Verbindung")
            return@synchronized
        }
        if (!verificationRead && (pendingStartModeWrite != null || descriptorWriteRunning ||
                notificationQueue.isNotEmpty() || gattOperationCoordinator.currentKind(active.epoch) != null)
        ) {
            update { it.copy(motorTuningStatus = "Anderer BLE-Vorgang läuft – Lesen verschoben") }
            return@synchronized
        }
        if (!ownsExistingOperation) {
            motorTuningOperation = gattOperationCoordinator.tryBegin(
                active.epoch,
                GattOperationKind.MOTOR_TUNING
            )
            if (motorTuningOperation == null) {
                update { it.copy(motorTuningStatus = "Anderer BLE-Vorgang läuft – Lesen verschoben") }
                return@synchronized
            }
        }
        val g = active.gatt
        val characteristic = g?.getService(SERVICE_MOTOR_TUNING)
            ?.getCharacteristic(MOTOR_TUNING_READ_CHARACTERISTIC)
        if (characteristic == null) {
            finishMotorTuningFailureLocked("Motor-Tuning-Lesekanal 160C nicht verfügbar")
            return@synchronized
        }
        if (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_READ == 0) {
            val awaitingWriteVerification = keepMotorTuningOperationForNotifyOnlyVerification(
                pendingMotorTuningWrite != null
            )
            if (!awaitingWriteVerification) {
                finishGattOperationLocked(motorTuningOperation)
                motorTuningOperation = null
            }
            update {
                it.copy(
                    motorTuningBusy = awaitingWriteVerification,
                    motorTuningStatus = if (awaitingWriteVerification) {
                        "160C ist nur Notify – warte auf Controllerbestätigung"
                    } else {
                        "160C ist nur Notify – warte auf Controllerdaten"
                    },
                    // Keep the operation token and the UI interlock until either
                    // the notify response or the existing eight-second timeout.
                    gattOperationBusy = awaitingWriteVerification
                )
            }
            return@synchronized
        }

        motorTuningBuffer.clear()
        val readOperation = motorTuningOperation
        if (!gattOperationCoordinator.isCurrent(readOperation)) {
            finishMotorTuningFailureLocked("Motor-Tuning-Lesevorgang besitzt keinen aktuellen BLE-Token")
            return@synchronized
        }
        val requestAt = SystemClock.elapsedRealtime()
        lastMotorTuningReadRequestAt = requestAt
        update {
            it.copy(
                motorTuningBusy = true,
                gattOperationBusy = true,
                motorTuningStatus = if (verificationRead) "Prüfe über 160C …" else "Lese Originalwerte über 160C …",
                motorTuningLastVerified = if (verificationRead) null else it.motorTuningLastVerified
            )
        }
        val started = g.readCharacteristic(characteristic)
        if (!started) {
            finishMotorTuningFailureLocked("Lesevorgang für 160C konnte nicht gestartet werden")
            return@synchronized
        }
        mainHandler.postDelayed({
            synchronized(gattOperationLock) {
                if (gatt === g && activeGattConnection?.epoch == active.epoch &&
                    lastMotorTuningReadRequestAt == requestAt &&
                    motorTuningOperation == readOperation &&
                    gattOperationCoordinator.isCurrent(readOperation)
                ) {
                    timeoutMotorTuningReadLocked(g, active.epoch, readOperation)
                }
            }
        }, 5_000L)
    }

    @SuppressLint("MissingPermission")
    private fun timeoutMotorTuningReadLocked(
        g: BluetoothGatt,
        connectionEpoch: Long,
        operation: GattOperationToken?
    ) {
        diagnosticReadReconnectRequiredEpoch = connectionEpoch
        pendingMotorTuningWrite = null
        lastMotorTuningReadRequestAt = 0L
        motorTuningBuffer.clear()
        gattOperationCoordinator.finish(operation)
        motorTuningOperation = null
        update {
            it.copy(
                motorTuningBusy = false,
                gattOperationBusy = false,
                motorTuningStatus = "✕ 160C-READ ohne Callback – sichere Neuverbindung",
                motorTuningLastVerified = false,
                status = "160C-READ Timeout – GATT wird neu aufgebaut"
            )
        }
        addLog("Motor-Tuning 160C Timeout; verspätete Callback-Zuordnung durch Reconnect verhindert")
        activeGattConnection?.takeIf {
            it.gatt === g && it.epoch == connectionEpoch
        }?.let { active ->
            forceCloseGattEpochLocked(active, "Motor-Tuning 160C ohne Callback")
        }
    }

    private fun hasCompetingGattOperationLocked(snapshot: ScooterState, connectionEpoch: Long): Boolean =
        gattConnectionDeadline != null || descriptorWriteRunning || notificationQueue.isNotEmpty() ||
            pendingMotorTuningWrite != null || snapshot.motorTuningBusy ||
            snapshot.diagnosticGattReadRunning ||
            !isDiagnosticReadAllowed(connectionEpoch, diagnosticReadReconnectRequiredEpoch) ||
            gattOperationCoordinator.currentKind(connectionEpoch) != null

    private fun startModeBlockMessage(reason: StartModeWriteBlockReason): String = when (reason) {
        StartModeWriteBlockReason.NOT_CONNECTED -> "Nicht verbunden"
        StartModeWriteBlockReason.TELEMETRY_NOT_READY -> "Warte auf Live-Telemetrie"
        StartModeWriteBlockReason.RECORDING_ACTIVE -> "Messfahrt zuerst stoppen"
        StartModeWriteBlockReason.WRITE_PENDING,
        StartModeWriteBlockReason.OPERATION_BUSY -> "Startmodus-Übertragung läuft bereits"
        StartModeWriteBlockReason.LEGACY_ROUTE_NOT_CONFIRMED ->
            "Legacy-Schreibroute des Controllers nicht sicher bestätigt"
        StartModeWriteBlockReason.GATT_BUSY -> "Anderer BLE-Vorgang läuft – bitte kurz warten"
        StartModeWriteBlockReason.SPEED_NOT_AVAILABLE,
        StartModeWriteBlockReason.SPEED_FROM_PREVIOUS_CONNECTION,
        StartModeWriteBlockReason.SPEED_SAMPLE_STALE -> "Warte auf frische 1505-Stillstandsmessung"
        StartModeWriteBlockReason.SCOOTER_MOVING -> "Startmodus nur im Stillstand ändern"
    }

    /**
     * Writes only the original VMAX legacy start-mode message. This is kept
     * completely separate from motor tuning and is enabled only after the GATT
     * topology and the 1508/11 readback layout have both been confirmed.
    */
    @SuppressLint("MissingPermission")
    fun setStartMode(mode: VmaxStartMode) = synchronized(gattOperationLock) {
        val active = activeGattConnection
        val snapshot = _state.value
        val blockReason = StartModeWriteSafetyPolicy.blockReason(
            StartModeWriteSafetyInput(
                connected = snapshot.connected,
                telemetryReady = snapshot.telemetryReady,
                recordingActive = recordingActive || snapshot.recordingActive,
                startModeBusy = snapshot.startModeBusy,
                pendingStartModeWrite = pendingStartModeWrite != null,
                legacyRouteConfirmed = legacyStartModeCharacteristicAvailable && snapshot.startModeWriteAvailable,
                gattBusy = active == null || hasCompetingGattOperationLocked(snapshot, active.epoch),
                speedKmh = snapshot.speedKmh,
                speedSampleAtElapsedMs = snapshot.lastSpeedSampleElapsedRealtimeMs,
                speedSampleConnectionEpoch = snapshot.speedSampleConnectionEpoch,
                connectionEpoch = snapshot.connectionEpoch,
                nowElapsedMs = SystemClock.elapsedRealtime()
            )
        )
        val refusal = when {
            blockReason != null -> startModeBlockMessage(blockReason)
            VmaxStartMode.fromRaw(snapshot.startModeRaw) == mode -> "${mode.label} ist bereits aktiv"
            else -> null
        }
        if (refusal != null) {
            update { it.copy(startModeStatus = refusal) }
            addLog("Startmodus nicht geändert: $refusal")
            return@synchronized
        }

        if (active == null) {
            update { it.copy(startModeWriteAvailable = false, startModeStatus = "Schreibkanal 1608 nicht verfügbar") }
            return@synchronized
        }
        val g = active.gatt
        val characteristic = g.getService(SERVICE_MOTOR_TUNING)
            ?.getCharacteristic(START_MODE_LEGACY_WRITE_CHARACTERISTIC)
        if (characteristic == null) {
            update { it.copy(startModeWriteAvailable = false, startModeStatus = "Schreibkanal 1608 nicht verfügbar") }
            return@synchronized
        }
        val writeType = when {
            characteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0 ->
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            characteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0 ->
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            else -> {
                update { it.copy(startModeWriteAvailable = false, startModeStatus = "1608 ist nicht schreibbar") }
                return@synchronized
            }
        }

        val operation = gattOperationCoordinator.tryBegin(active.epoch, GattOperationKind.START_MODE_WRITE)
        if (operation == null) {
            update { it.copy(startModeStatus = "Anderer BLE-Vorgang läuft – bitte kurz warten") }
            return@synchronized
        }

        // The lock covers both this final policy snapshot and the platform write.
        // That prevents a scanner/reconnect/other write from slipping into the gap.
        val latest = _state.value
        val latestBlockReason = StartModeWriteSafetyPolicy.blockReason(
            StartModeWriteSafetyInput(
                connected = latest.connected && gatt === g &&
                    activeGattConnection?.epoch == active.epoch &&
                    gattOperationCoordinator.isCurrent(operation),
                telemetryReady = latest.telemetryReady,
                recordingActive = recordingActive || latest.recordingActive,
                startModeBusy = latest.startModeBusy,
                pendingStartModeWrite = pendingStartModeWrite != null,
                legacyRouteConfirmed = legacyStartModeCharacteristicAvailable && latest.startModeWriteAvailable,
                gattBusy = false,
                speedKmh = latest.speedKmh,
                speedSampleAtElapsedMs = latest.lastSpeedSampleElapsedRealtimeMs,
                speedSampleConnectionEpoch = latest.speedSampleConnectionEpoch,
                connectionEpoch = active.epoch,
                nowElapsedMs = SystemClock.elapsedRealtime()
            )
        )
        if (latestBlockReason != null || VmaxStartMode.fromRaw(latest.startModeRaw) == mode) {
            finishGattOperationLocked(operation)
            val message = latestBlockReason?.let(::startModeBlockMessage) ?: "${mode.label} ist bereits aktiv"
            update { it.copy(startModeBusy = false, gattOperationBusy = false, startModeStatus = message) }
            addLog("Startmodus nicht geändert: $message")
            return@synchronized
        }

        val packet = VmaxStartModeProtocol.buildLegacyWriteFrame(mode)
        val startedAt = SystemClock.elapsedRealtime()
        val pending = PendingStartModeWrite(
            mode = mode,
            startedAt = startedAt,
            gatt = g,
            connectionEpoch = active.epoch,
            operationToken = operation,
            // Android completes both WRITE_TYPE_DEFAULT and NO_RESPONSE through
            // onCharacteristicWrite; never release the platform slot without it.
            writeCallbackExpected = true
        )
        pendingStartModeWrite = pending
        update {
            it.copy(
                startModeBusy = true,
                gattOperationBusy = true,
                startModeStatus = "Sende ${mode.label} über 1608 …"
            )
        }
        addLog("Startmodus TX 1608: ${packet.joinToString("-") { "%02X".format(it.toInt() and 0xFF) }}")

        val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(characteristic, packet, writeType) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            run {
                characteristic.writeType = writeType
                characteristic.value = packet
                g.writeCharacteristic(characteristic)
            }
        }
        if (!started) {
            finishStartModeWriteLocked("Schreiben auf 1608 konnte nicht gestartet werden")
            return@synchronized
        }

        mainHandler.postDelayed({
            synchronized(gattOperationLock) {
                if (pendingStartModeWrite === pending && gatt === pending.gatt &&
                    activeGattConnection?.epoch == pending.connectionEpoch &&
                    gattOperationCoordinator.isCurrent(pending.operationToken)
                ) {
                    if (shouldForceCloseWriteEpochOnTimeout(
                            pending.writeCallbackExpected,
                            pending.writeCallbackReceived
                        )
                    ) {
                        pendingStartModeWrite = null
                        update {
                            it.copy(
                                startModeBusy = false,
                                gattOperationBusy = false,
                                startModeStatus = "Android-Schreibcallback fehlt – sichere Neuverbindung"
                            )
                        }
                        activeGattConnection?.takeIf {
                            it.gatt === pending.gatt && it.epoch == pending.connectionEpoch
                        }?.let { current ->
                            forceCloseGattEpochLocked(current, "Startmodus-Write ohne Callback")
                        }
                    } else {
                        finishStartModeWriteLocked("Keine bestätigte 1508/11-Rückmeldung")
                    }
                }
            }
        }, 8_000L)
    }

    private fun finishStartModeWriteLocked(message: String) {
        val pending = pendingStartModeWrite
        if (pending != null) finishGattOperationLocked(pending.operationToken)
        pendingStartModeWrite = null
        update {
            it.copy(
                startModeBusy = false,
                gattOperationBusy = activeGattConnection?.let { active ->
                    gattOperationCoordinator.currentKind(active.epoch) != null
                } ?: false,
                startModeStatus = message
            )
        }
        addLog("Startmodus: $message")
    }

    fun writeMotorTuning(profileIndex: Int, requestedValues: Map<MotorTuningParameter, Int>) =
        synchronized(gattOperationLock) {
            writeMotorTuningLocked(profileIndex, requestedValues)
        }

    private fun writeMotorTuningLocked(profileIndex: Int, requestedValues: Map<MotorTuningParameter, Int>) {
        val snapshot = _state.value
        val profile = snapshot.motorTuningProfiles.firstOrNull { it.index == profileIndex }
        val original = snapshot.motorTuningOriginalProfiles.firstOrNull { it.index == profileIndex }
        if (profile == null || original == null) {
            finishMotorTuningFailure("Erst Originalwerte lesen und sichern")
            return
        }
        if (!canStartMotorTuningWrite()) return

        val merged = profile.values.toMutableMap().apply { putAll(requestedValues) }
        val changed = merged.filter { (parameter, value) -> profile.values[parameter] != value }
        if (changed.isEmpty()) {
            update { it.copy(motorTuningStatus = "Keine Änderung zum gelesenen Profil") }
            return
        }

        for ((parameter, value) in merged) {
            if (value !in 0..parameter.sdkMaximum) {
                finishMotorTuningFailure("${parameter.label}: Wert $value außerhalb 0–${parameter.sdkMaximum}")
                return
            }
        }
        for ((parameter, value) in changed) {
            val originalValue = original.values[parameter] ?: profile.values[parameter] ?: continue
            if (parameter == MotorTuningParameter.MaxSpeed && value > originalValue) {
                finishMotorTuningFailure("Erster Test: MaxSpeed darf nur gesenkt oder auf Original gestellt werden")
                return
            }
            if (abs(value - originalValue) > 5) {
                finishMotorTuningFailure("Erster Test: ${parameter.label} höchstens ±5 vom Original verändern")
                return
            }
        }
        writeMotorTuningInternal(profile, merged, reset = false)
    }

    fun restoreOriginalMotorTuning(profileIndex: Int) {
        synchronized(gattOperationLock) {
            if (!canStartMotorTuningWrite()) return@synchronized
            val current = _state.value.motorTuningProfiles.firstOrNull { it.index == profileIndex }
            val original = _state.value.motorTuningOriginalProfiles.firstOrNull { it.index == profileIndex }
            if (current == null || original == null) {
                finishMotorTuningFailure("Kein gesichertes Originalprofil vorhanden")
                return@synchronized
            }
            writeMotorTuningInternal(current, original.values, reset = false)
        }
    }

    fun resetMotorTuning(profileIndex: Int) {
        synchronized(gattOperationLock) {
            if (!canStartMotorTuningWrite()) return@synchronized
            val profile = _state.value.motorTuningProfiles.firstOrNull { it.index == profileIndex }
            if (profile == null) {
                finishMotorTuningFailure("Profil $profileIndex wurde nicht gelesen")
                return@synchronized
            }
            val packet = MotorTuningProtocol.buildResetPacket(profileIndex, _state.value.motorTuningProtocol)
            writeMotorTuningPacket(profileIndex, emptyMap(), packet, reset = true)
        }
    }

    private fun canStartMotorTuningWrite(): Boolean {
        val snapshot = _state.value
        val speed = snapshot.speedKmh
        val speedAgeMs = SystemClock.elapsedRealtime() - snapshot.lastSpeedSampleElapsedRealtimeMs
        return when {
            !snapshot.connected -> {
                finishMotorTuningFailure("Scooter nicht verbunden"); false
            }
            !snapshot.motorTuningSupported -> {
                finishMotorTuningFailure("160C/160D wurden nicht vollständig erkannt"); false
            }
            snapshot.motorTuningBusy || pendingMotorTuningWrite != null -> {
                update { it.copy(motorTuningStatus = "Motor-Tuning-Vorgang läuft bereits") }
                false
            }
            activeGattConnection?.let {
                !isDiagnosticReadAllowed(it.epoch, diagnosticReadReconnectRequiredEpoch)
            } == true -> {
                finishMotorTuningFailure("Nach Deep-READ-Timeout ist ein Reconnect erforderlich")
                false
            }
            pendingStartModeWrite != null || activeGattConnection?.let {
                hasCompetingGattOperationLocked(snapshot, it.epoch)
            } != false -> {
                finishMotorTuningFailure("Anderer BLE-Vorgang läuft – bitte kurz warten")
                false
            }
            recordingActive -> {
                finishMotorTuningFailure("Während einer Messfahrt wird nicht geschrieben"); false
            }
            !snapshot.telemetryReady || speed == null || !speed.isFinite() || speed < 0.0 ||
                snapshot.lastSpeedSampleElapsedRealtimeMs <= 0L ||
                snapshot.speedSampleConnectionEpoch != snapshot.connectionEpoch ||
                speedAgeMs !in 0..StartModeWriteSafetyPolicy.MAX_SPEED_SAMPLE_AGE_MS -> {
                finishMotorTuningFailure("Schreiben benötigt eine frische 1505-Stillstandsmessung"); false
            }
            speed > StartModeWriteSafetyPolicy.MAX_STATIONARY_SPEED_KMH -> {
                finishMotorTuningFailure("Schreiben nur im Stillstand"); false
            }
            else -> true
        }
    }

    private fun writeMotorTuningInternal(
        profile: MotorTuningProfile,
        values: Map<MotorTuningParameter, Int>,
        reset: Boolean
    ) {
        val packet = if (reset) {
            MotorTuningProtocol.buildResetPacket(profile.index, _state.value.motorTuningProtocol)
        } else {
            MotorTuningProtocol.buildWritePacket(profile, values, _state.value.motorTuningProtocol)
        }
        writeMotorTuningPacket(profile.index, values, packet, reset)
    }

    @SuppressLint("MissingPermission")
    private fun writeMotorTuningPacket(
        profileIndex: Int,
        expectedValues: Map<MotorTuningParameter, Int>,
        packet: ByteArray,
        reset: Boolean
    ) {
        val active = activeGattConnection
        val g = active?.gatt
        val characteristic = g?.getService(SERVICE_MOTOR_TUNING)
            ?.getCharacteristic(MOTOR_TUNING_WRITE_CHARACTERISTIC)
        if (active == null || g == null || characteristic == null) {
            finishMotorTuningFailure("Motor-Tuning-Schreibkanal 160D nicht verfügbar")
            return
        }

        val writeType = when {
            characteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0 ->
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            characteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0 ->
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            else -> {
                finishMotorTuningFailure("160D besitzt keine Schreibberechtigung")
                return
            }
        }

        val operation = gattOperationCoordinator.tryBegin(active.epoch, GattOperationKind.MOTOR_TUNING)
        if (operation == null || gatt !== g || activeGattConnection?.epoch != active.epoch || !_state.value.connected) {
            finishGattOperationLocked(operation)
            finishMotorTuningFailure("Anderer BLE-Vorgang läuft – bitte kurz warten")
            return
        }
        motorTuningOperation = operation

        val packetHex = MotorTuningProtocol.packetHex(packet)
        val pending = PendingMotorTuningWrite(
            profileIndex = profileIndex,
            expectedValues = expectedValues,
            packetHex = packetHex,
            reset = reset,
            startedAt = System.currentTimeMillis(),
            gatt = g,
            connectionEpoch = active.epoch,
            operationToken = operation,
            writeCallbackExpected = true
        )
        pendingMotorTuningWrite = pending
        update {
            it.copy(
                motorTuningBusy = true,
                gattOperationBusy = true,
                motorTuningStatus = if (reset) "Werkprofil-Befehl wird übertragen …" else "Testwerte werden übertragen …",
                motorTuningLastPacket = packetHex,
                motorTuningLastVerified = null
            )
        }
        addLog("Motor-Tuning TX 160D: $packetHex")

        val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(characteristic, packet, writeType) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            run {
                characteristic.writeType = writeType
                characteristic.value = packet
                g.writeCharacteristic(characteristic)
            }
        }
        if (!started) {
            finishMotorTuningFailure("Schreibvorgang auf 160D konnte nicht gestartet werden")
            return
        }
        mainHandler.postDelayed({
            synchronized(gattOperationLock) {
                if (gatt === g && activeGattConnection?.epoch == active.epoch &&
                    pendingMotorTuningWrite === pending &&
                    motorTuningOperation == pending.operationToken &&
                    gattOperationCoordinator.isCurrent(pending.operationToken)
                ) {
                    if (shouldForceCloseWriteEpochOnTimeout(
                            pending.writeCallbackExpected,
                            pending.writeCallbackReceived
                        )
                    ) {
                        pendingMotorTuningWrite = null
                        update {
                            it.copy(
                                motorTuningBusy = false,
                                gattOperationBusy = false,
                                motorTuningStatus = "✕ Android-Schreibcallback fehlt – sichere Neuverbindung",
                                motorTuningLastVerified = false
                            )
                        }
                        activeGattConnection?.takeIf {
                            it.gatt === pending.gatt && it.epoch == pending.connectionEpoch
                        }?.let { current ->
                            forceCloseGattEpochLocked(current, "Motor-Tuning-Write ohne Callback")
                        }
                    } else if (lastMotorTuningReadRequestAt <= 0L && !pending.domainConfirmed) {
                        // With a completed platform write there is no old write
                        // left in flight. A readable 160C request owns its own
                        // timeout; notify-only/no-response paths end here.
                        finishMotorTuningFailureLocked("Keine bestätigte Rückmeldung vom Controller")
                    }
                }
            }
        }, 8_000L)
    }

    private fun scheduleMotorTuningVerificationLocked(pending: PendingMotorTuningWrite) {
        if (pendingMotorTuningWrite !== pending || pending.verificationReadScheduled ||
            !gattOperationCoordinator.isCurrent(pending.operationToken)
        ) return
        if (pending.domainConfirmed) {
            completeMotorTuningWriteIfSafeLocked(pending)
            return
        }
        pending.verificationReadScheduled = true
        addLog("Motor-Tuning 160D übergeben, lese nach 1 Sekunde über 160C zurück")
        mainHandler.postDelayed({
            synchronized(gattOperationLock) {
                if (pendingMotorTuningWrite === pending && gatt === pending.gatt &&
                    activeGattConnection?.epoch == pending.connectionEpoch &&
                    motorTuningOperation == pending.operationToken &&
                    gattOperationCoordinator.isCurrent(pending.operationToken)
                ) {
                    readMotorTuningValues(verificationRead = true)
                }
            }
        }, 1_000L)
    }

    private fun completeMotorTuningWriteIfSafeLocked(pending: PendingMotorTuningWrite) {
        if (pendingMotorTuningWrite !== pending || !pending.domainConfirmed ||
            (pending.writeCallbackExpected && !pending.writeCallbackReceived)
        ) return
        pendingMotorTuningWrite = null
        finishGattOperationLocked(pending.operationToken)
        motorTuningOperation = null
        update {
            it.copy(
                motorTuningBusy = false,
                gattOperationBusy = activeGattConnection?.let { active ->
                    gattOperationCoordinator.currentKind(active.epoch) != null
                } ?: false
            )
        }
    }

    private fun handleMotorTuningValue(value: ByteArray) {
        if (value.isEmpty()) return
        motorTuningBuffer.addAll(value.toList())
        if (motorTuningBuffer.size > 512) {
            motorTuningBuffer.subList(0, motorTuningBuffer.size - 512).clear()
        }

        while (true) {
            val start = motorTuningBuffer.indexOfFirst { (it.toInt() and 0xFF) == 0xFD }
            val end = motorTuningBuffer.indexOfFirst { (it.toInt() and 0xFF) == 0xFE }
            if (end >= 0 && (start < 0 || end < start)) {
                motorTuningBuffer.subList(0, end + 1).clear()
                continue
            }
            if (start < 0 || end <= start) return
            val frame = motorTuningBuffer.subList(start, end + 1).toByteArray()
            motorTuningBuffer.subList(0, end + 1).clear()
            val parsed = MotorTuningProtocol.parseFrame(frame)
            if (parsed == null) {
                addLog("160C-Rahmen konnte nicht dekodiert werden: ${MotorTuningProtocol.packetHex(frame)}")
                continue
            }
            applyMotorTuningResult(parsed)
        }
    }

    private fun applyMotorTuningResult(result: MotorTuningParseResult) {
        lastMotorTuningReadRequestAt = 0L
        val pending = pendingMotorTuningWrite
        val original = if (_state.value.motorTuningOriginalProfiles.isEmpty()) result.profiles else _state.value.motorTuningOriginalProfiles

        var verified: Boolean? = null
        var resultStatus = "${result.profiles.size} Motorprofil(e) gelesen und Original gesichert"
        if (pending != null) {
            val returnedProfile = result.profiles.firstOrNull { it.index == pending.profileIndex }
            verified = if (pending.reset) {
                returnedProfile != null
            } else {
                returnedProfile != null && pending.expectedValues.all { (parameter, expected) ->
                    returnedProfile.values[parameter] == expected
                }
            }
            resultStatus = when {
                verified == true && pending.reset -> "Werkprofil wurde zurückgelesen"
                verified == true -> "✓ Übertragung bestätigt – Werte stimmen mit 160C überein"
                else -> "✕ Controller-Antwort stimmt nicht mit den gesendeten Werten überein"
            }
            addLog("Motor-Tuning RX 160C: ${result.frameHex}")
            addLog("Motor-Tuning Prüfung: $resultStatus")
            pending.domainConfirmed = true
        } else {
            addLog("Motor-Tuning gelesen: ${result.mode.label}, ${result.profiles.size} Profil(e)")
        }

        val writeCanComplete = pending == null ||
            (!pending.writeCallbackExpected || pending.writeCallbackReceived)
        if (pending == null) {
            finishGattOperationLocked(motorTuningOperation)
            motorTuningOperation = null
        } else if (writeCanComplete) {
            completeMotorTuningWriteIfSafeLocked(pending)
        }

        update {
            it.copy(
                motorTuningBusy = if (pending != null && !writeCanComplete) true else false,
                gattOperationBusy = activeGattConnection?.let { active ->
                    gattOperationCoordinator.currentKind(active.epoch) != null
                } ?: false,
                motorTuningStatus = if (pending != null && !writeCanComplete) {
                    "$resultStatus • warte auf Android-Schreibcallback"
                } else {
                    resultStatus
                },
                motorTuningProtocol = result.mode,
                motorTuningProfiles = result.profiles,
                motorTuningOriginalProfiles = original,
                motorTuningLastReadRaw = result.frameHex,
                motorTuningLastVerified = verified ?: it.motorTuningLastVerified
            )
        }
    }

    private fun finishMotorTuningFailure(message: String) = synchronized(gattOperationLock) {
        finishMotorTuningFailureLocked(message)
    }

    private fun finishMotorTuningFailureLocked(message: String) {
        pendingMotorTuningWrite = null
        lastMotorTuningReadRequestAt = 0L
        finishGattOperationLocked(motorTuningOperation)
        motorTuningOperation = null
        update {
            it.copy(
                motorTuningBusy = false,
                gattOperationBusy = activeGattConnection?.let { active ->
                    gattOperationCoordinator.currentKind(active.epoch) != null
                } ?: false,
                motorTuningStatus = "✕ $message",
                motorTuningLastVerified = false
            )
        }
        addLog("Motor-Tuning: $message")
    }

    private fun finishGattOperationLocked(operation: GattOperationToken?) {
        if (gattOperationCoordinator.finish(operation)) {
            scheduleGattSetupResumeLocked(operation?.connectionEpoch)
        }
    }

    private fun scheduleGattSetupResumeLocked(connectionEpoch: Long?) {
        if (connectionEpoch == null) return
        mainHandler.post {
            synchronized(gattOperationLock) {
                if (activeGattConnection?.epoch == connectionEpoch &&
                    gattOperationCoordinator.currentKind(connectionEpoch) == null
                ) {
                    resumeDeferredGattSetupLocked(connectionEpoch)
                }
            }
        }
    }

    private fun handleValue(
        callbackGatt: BluetoothGatt,
        callbackConnectionEpoch: Long,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
        origin: BlePacketOrigin
    ) {
        val uuid = characteristic.uuid
        if (gatt !== callbackGatt || activeGattConnection?.epoch != callbackConnectionEpoch ||
            !gattOperationCoordinator.isActiveConnection(callbackConnectionEpoch) || !_state.value.connected
        ) {
            addLog("${origin.name} ${shortUuid(uuid)} nach Verbindungsende verworfen")
            return
        }
        val serviceUuid = characteristic.service.uuid.toString().lowercase()
        val characteristicUuid = uuid.toString().lowercase()
        val propertiesRaw = characteristic.properties
        val packet = value.copyOf()
        val short = shortUuid(uuid)
        val hex = packet.joinToString("-") { "%02X".format(it.toInt() and 0xFF) }
        if (origin == BlePacketOrigin.READ) {
            if (recordingActive && !recordingPaused) rejectedReadPackets++
            addLog("READ $short (nur Diagnose, nicht als Live-Telemetrie): $hex")
            return
        }
        if (powerCriticalNotificationEpoch == callbackConnectionEpoch &&
            characteristic.service.uuid == SERVICE_TELEMETRY &&
            uuid == BATTERY_CHARACTERISTIC
        ) {
            powerCriticalNotificationSucceededEpoch = callbackConnectionEpoch
        }
        if (recordingActive && !recordingPaused) receivedNotificationPackets++
        if (VmaxDecoderPolicy.isSuspiciousReadLikePayload(short, packet)) {
            if (recordingActive && !recordingPaused) {
                rejectedHybridPackets++
                val now = System.currentTimeMillis()
                val measurementMs = now - measurementStartedAt
                val knowledge = VmaxProtocolCatalog.get(short)
                sessionRows += listOf(
                    measurementMs.toString(),
                    now.toString(),
                    short,
                    knowledge.title,
                    packet.size.toString(),
                    receivedNotificationPackets.toString(),
                    "–",
                    hex,
                    QUARANTINED_NOTIFICATION_ORIGIN,
                    measurementConnectionEpoch.toString(),
                    serviceUuid,
                    characteristicUuid,
                    propertiesRaw.toString()
                ).joinToString(";")
                if (sessionRows.size > 100_000) sessionRows.removeAt(0)
            }
            addLog("$short quarantänisiert: READ-/Firmware-ID-Hybrid exakt im RAW-Export gesichert")
            return
        }
        if (!isBt638LiveNotificationRoute(serviceUuid, characteristicUuid)) {
            if (recordingActive && !recordingPaused) {
                diagnosticOnlyNotificationPackets++
                val now = System.currentTimeMillis()
                val measurementMs = now - measurementStartedAt
                sessionRows += listOf(
                    measurementMs.toString(),
                    now.toString(),
                    short,
                    "Diagnose-Notification (nicht im Live-Decoder)",
                    packet.size.toString(),
                    receivedNotificationPackets.toString(),
                    "–",
                    hex,
                    DIAGNOSTIC_NOTIFICATION_ORIGIN,
                    measurementConnectionEpoch.toString(),
                    serviceUuid,
                    characteristicUuid,
                    propertiesRaw.toString()
                ).joinToString(";")
                if (sessionRows.size > 100_000) sessionRows.removeAt(0)
            }
            addLog("$short aus Service ${normalizeGattShortUuid(serviceUuid)} nur diagnostisch gesichert")
            return
        }
        if (characteristic.service.uuid == SERVICE_MOTOR_TUNING && uuid == MOTOR_TUNING_READ_CHARACTERISTIC) {
            handleMotorTuningValue(packet)
        }
        if (recordingActive && !recordingPaused) acceptedNotificationPackets++
        decoderLab.record(uuid, packet)
        val now = System.currentTimeMillis()
        val relativeMs = now - sessionStartedAt
        val previous = previousValues[short]
        val changed = changedByteIndexes(previous, packet)
        previousValues[short] = packet.copyOf()
        val count = (channelPacketCounts[short] ?: 0) + 1
        channelPacketCounts[short] = count

        val decoded = LiveTelemetryDecoder.decode(short, packet)
        val speedSampleElapsedRealtimeMs = if (short == "1505" && decoded.speedKmh != null) {
            SystemClock.elapsedRealtime()
        } else {
            null
        }
        val knowledge = VmaxProtocolCatalog.get(short)
        packetTimes.addLast(now)
        while (packetTimes.isNotEmpty() && packetTimes.first() < now - 3000L) packetTimes.removeFirst()
        val packetsPerSecond = packetTimes.size / 3.0
        val changedText = if (changed.isEmpty()) "–" else changed.joinToString(",")
        if (recordingActive && !recordingPaused) {
            val measurementMs = now - measurementStartedAt
            sessionRows += listOf(
                measurementMs.toString(), now.toString(), short, knowledge.title,
                packet.size.toString(), count.toString(), changedText, hex,
                origin.name, measurementConnectionEpoch.toString(),
                serviceUuid, characteristicUuid, propertiesRaw.toString()
            ).joinToString(";")
            val snapshot = _state.value
            val electricalPower = resolveElectricalPowerW(
                decoded.voltageV,
                decoded.currentA,
                snapshot.voltageV,
                snapshot.currentA
            )
            val previousDirectPower = snapshot.motorLoadRaw?.toDouble()
            val power = resolveExportPowerW(decoded.powerW, electricalPower, previousDirectPower)
            val powerProvenance = when {
                decoded.powerW != null -> "1509_direct"
                previousDirectPower != null -> "1509_direct_carried"
                electricalPower != null -> "voltage_x_current_fallback"
                else -> ""
            }
            telemetryRows += listOf(
                measurementMs.toString(), now.toString(),
                (decoded.speedKmh ?: snapshot.speedKmh)?.toString().orEmpty(),
                (decoded.batteryPercent ?: snapshot.batteryPercent)?.toString().orEmpty(),
                (decoded.voltageV ?: snapshot.voltageV)?.toString().orEmpty(),
                (decoded.currentA ?: snapshot.currentA)?.toString().orEmpty(),
                power?.toString().orEmpty(),
                electricalPower?.toString().orEmpty(),
                powerProvenance,
                (decoded.motorTemperatureC ?: snapshot.motorTemperatureC)?.toString().orEmpty(),
                (decoded.batteryTemperatureC ?: snapshot.batteryTemperatureC)?.toString().orEmpty(),
                (decoded.tripDistanceKm ?: snapshot.tripDistanceKm)?.toString().orEmpty(),
                (decoded.odometerKm ?: snapshot.odometerKm)?.toString().orEmpty(),
                (decoded.driveRaw ?: snapshot.driveRaw)?.toString().orEmpty(),
                (decoded.motorLoadRaw ?: snapshot.motorLoadRaw)?.toString().orEmpty(),
                (decoded.batteryStateRaw ?: snapshot.batteryStateRaw)?.toString().orEmpty(),
                (decoded.accessoryByte0 ?: snapshot.accessoryByte0)?.toString().orEmpty(),
                (decoded.accessoryByte3 ?: snapshot.accessoryByte3)?.toString().orEmpty(),
                short
            ).joinToString(";")
            if (sessionRows.size > 100_000) sessionRows.removeAt(0)
            if (telemetryRows.size > 100_000) telemetryRows.removeAt(0)
        }

        val old = _state.value
        val decodedStartMode = VmaxStartMode.fromRaw(decoded.startModeRaw)
        val pendingStartMode = pendingStartModeWrite
        val startModeDomainConfirmed = decodedStartMode != null && pendingStartMode != null &&
            pendingStartMode.mode == decodedStartMode &&
            pendingStartMode.gatt === callbackGatt &&
            pendingStartMode.connectionEpoch == callbackConnectionEpoch &&
            gattOperationCoordinator.isCurrent(pendingStartMode.operationToken)
        var startModeCompleted = false
        if (startModeDomainConfirmed && pendingStartMode != null) {
            pendingStartMode.domainConfirmed = true
            val platformResolved = !pendingStartMode.writeCallbackExpected ||
                pendingStartMode.writeCallbackReceived
            if (platformResolved) {
                finishGattOperationLocked(pendingStartMode.operationToken)
                pendingStartModeWrite = null
                startModeCompleted = true
                addLog("Startmodus bestätigt: ${decodedStartMode?.label} (1508/11 + Plattform)")
            } else {
                addLog("Startmodus 1508/11 bestätigt; warte noch auf Android-Schreibcallback")
            }
        }
        val packets = (old.rawPackets + (short to hex)).toSortedMap()
        val channels = packets.keys.map { channel ->
            val info = VmaxProtocolCatalog.get(channel)
            BleChannelState(
                channel = channel,
                title = info.title,
                meaning = info.meaning,
                knowledge = info.level.label,
                hex = packets[channel].orEmpty(),
                changedBytes = if (channel == short) changedText else old.channels.firstOrNull { it.channel == channel }?.changedBytes ?: "–",
                packetCount = channelPacketCounts[channel] ?: 0,
                active = channel == short,
                lastSeenMs = if (channel == short) now else old.channels.firstOrNull { it.channel == channel }?.lastSeenMs ?: 0L
            )
        }.sortedBy { it.channel }

        update {
            it.copy(
                batteryPercent = decoded.batteryPercent ?: it.batteryPercent,
                speedKmh = decoded.speedKmh ?: it.speedKmh,
                driveRaw = decoded.driveRaw ?: it.driveRaw,
                motorLoadRaw = decoded.motorLoadRaw ?: it.motorLoadRaw,
                batteryStateRaw = decoded.batteryStateRaw ?: it.batteryStateRaw,
                accessoryByte0 = decoded.accessoryByte0 ?: it.accessoryByte0,
                accessoryByte3 = decoded.accessoryByte3 ?: it.accessoryByte3,
                startModeRaw = decoded.startModeRaw ?: it.startModeRaw,
                startModeWriteAvailable = if (decodedStartMode != null) {
                    legacyStartModeCharacteristicAvailable
                } else {
                    it.startModeWriteAvailable
                },
                startModeBusy = if (startModeCompleted) false else it.startModeBusy,
                gattOperationBusy = if (startModeCompleted) false else it.gattOperationBusy,
                startModeStatus = when {
                    startModeCompleted -> "✓ ${decodedStartMode?.label} zurückgelesen"
                    startModeDomainConfirmed ->
                        "${decodedStartMode?.label} zurückgelesen – warte auf Android-Schreibcallback"
                    decodedStartMode != null && pendingStartMode != null ->
                        "Gesendet – warte auf ${pendingStartMode.mode.label}"
                    decodedStartMode != null && legacyStartModeCharacteristicAvailable ->
                        "Bereit • ${decodedStartMode.label}"
                    decodedStartMode != null -> "Nur Anzeige • ${decodedStartMode.label}"
                    else -> it.startModeStatus
                },
                voltageV = decoded.voltageV ?: it.voltageV,
                currentA = decoded.currentA ?: it.currentA,
                motorTemperatureC = decoded.motorTemperatureC ?: it.motorTemperatureC,
                batteryTemperatureC = decoded.batteryTemperatureC ?: it.batteryTemperatureC,
                tripDistanceKm = decoded.tripDistanceKm ?: it.tripDistanceKm,
                odometerKm = decoded.odometerKm ?: it.odometerKm,
                lastCharacteristic = short,
                lastRawHex = hex,
                lastChangedBytes = changedText,
                rawPackets = packets,
                packetTotal = it.packetTotal + 1,
                packetsPerSecond = packetsPerSecond,
                lastPacketAt = now,
                lastBatteryTelemetryAt = if (
                    decoded.batteryPercent != null || decoded.voltageV != null
                ) now else it.lastBatteryTelemetryAt,
                telemetryReady = it.telemetryReady || ConnectionTelemetryPolicy.isLiveNotificationChannel(short),
                speedSampleConnectionEpoch = if (speedSampleElapsedRealtimeMs != null) callbackConnectionEpoch else it.speedSampleConnectionEpoch,
                lastSpeedSampleElapsedRealtimeMs = speedSampleElapsedRealtimeMs ?: it.lastSpeedSampleElapsedRealtimeMs,
                currentPowerW = resolveElectricalPowerW(
                    decoded.voltageV,
                    decoded.currentA,
                    it.voltageV,
                    it.currentA
                ) ?: it.currentPowerW,
                maxSpeedKmh = maxOf(it.maxSpeedKmh ?: 0.0, decoded.speedKmh ?: it.speedKmh ?: 0.0).takeIf { value -> value > 0.0 },
                maxPowerW = maxOf(
                    it.maxPowerW ?: 0.0,
                    abs(resolveElectricalPowerW(decoded.voltageV, decoded.currentA, it.voltageV, it.currentA) ?: 0.0)
                ).takeIf { value -> value > 0.0 },
                recordingPacketCount = if (recordingActive && !recordingPaused) it.recordingPacketCount + 1 else it.recordingPacketCount,
                channels = channels,
                analysisPhase = when {
                    it.labRunning -> it.labPhase
                    recordingActive && recordingPaused -> "Messfahrt pausiert"
                    recordingActive -> "Messfahrt wird aufgezeichnet"
                    else -> "Live-Analyse bereit"
                },
                status = "Live-Daten aktiv"
            )
        }
        if (count <= 3 || changed.isNotEmpty()) addLog("$short [${knowledge.title}] Δ$changedText: $hex")
    }

    fun startMeasurement() {
        synchronized(gattOperationLock) {
            if (!_state.value.connected) {
                addLog("Messfahrt benötigt eine BLE-Verbindung")
                return@synchronized
            }
            measurementStartedAt = System.currentTimeMillis()
            recordingActive = true
            recordingPaused = false
            measurementConnectionEpoch = 0
            measurementEpochByGattEpoch.clear()
            activeGattConnection?.epoch?.let { epoch ->
                measurementEpochByGattEpoch[epoch] = measurementConnectionEpoch
            }
            receivedNotificationPackets = 0
            acceptedNotificationPackets = 0
            rejectedReadPackets = 0
            rejectedHybridPackets = 0
            diagnosticOnlyNotificationPackets = 0
            measurementDiagnosticReadBundles.clear()
            sessionRows.clear()
            markerRows.clear()
            telemetryRows.clear()
            addMarkerInternal("START", measurementStartedAt)
            update {
                it.copy(
                    recordingActive = true,
                    recordingPaused = false,
                    recordingStartedAt = measurementStartedAt,
                    recordingPacketCount = 0,
                    markerCount = 1,
                    lastMarker = "START",
                    lastExportMessage = "",
                    autoAnalysisFindings = emptyList(),
                    analysisPhase = "Messfahrt wird aufgezeichnet"
                )
            }
            addLog("Messfahrt gestartet")
        }
    }

    /** Stable reconnect backup without reflective access to the mutable row list. */
    internal fun snapshotMeasurementRowsForReconnect(): List<String>? =
        synchronized(gattOperationLock) {
            if (!recordingActive) null else sessionRows.toList()
        }

    /** Restores a legacy backup only when the manager did not retain it itself. */
    internal fun restoreMeasurementRowsForReconnect(
        backup: List<String>,
        lastBackedUpRow: String?
    ): Int = synchronized(gattOperationLock) {
        if (!recordingActive) return@synchronized 0
        if (lastBackedUpRow != null && lastBackedUpRow in sessionRows) return@synchronized 0
        if (backup.isNotEmpty()) sessionRows.addAll(0, backup)
        backup.size
    }

    fun toggleMeasurementPause() {
        synchronized(gattOperationLock) {
            if (!recordingActive) return@synchronized
            recordingPaused = !recordingPaused
            val label = if (recordingPaused) "PAUSE" else "FORTSETZEN"
            addMarkerInternal(label, System.currentTimeMillis())
            update { it.copy(recordingPaused = recordingPaused, lastMarker = label, markerCount = it.markerCount + 1) }
            addLog(if (recordingPaused) "Messfahrt pausiert" else "Messfahrt fortgesetzt")
        }
    }

    fun addMeasurementMarker(label: String) {
        synchronized(gattOperationLock) {
            if (!recordingActive) {
                addLog("Erst Messfahrt starten")
                return@synchronized
            }
            val now = System.currentTimeMillis()
            addMarkerInternal(label, now)
            update { it.copy(markerCount = it.markerCount + 1, lastMarker = label) }
            addLog("Marker: $label")
        }
    }

    fun stopMeasurementAndExport() {
        diagnosticGattReadObserver?.finalizeForMeasurementExport(resumeDeferredSetup = true)
        stopMeasurementAndExportAfterDiagnosticFlush()
    }

    private fun stopMeasurementAndExportAfterDiagnosticFlush() {
        val (stoppedAt, exportSnapshot) = synchronized(gattOperationLock) {
            if (!recordingActive) return
            val now = System.currentTimeMillis()
            addMarkerInternal("STOP", now)
            recordingActive = false
            recordingPaused = false
            update { it.copy(recordingActive = false, recordingPaused = false, markerCount = it.markerCount + 1, lastMarker = "STOP", analysisPhase = "Messfahrt beendet") }
            val snapshot = MeasurementExportSnapshot(
                rawRows = sessionRows.toList(),
                markerRows = markerRows.toList(),
                telemetryRows = telemetryRows.toList(),
                startedAt = measurementStartedAt,
                connectionCount = measurementConnectionEpoch + 1,
                receivedNotifications = receivedNotificationPackets,
                acceptedNotifications = acceptedNotificationPackets,
                rejectedReads = rejectedReadPackets,
                rejectedHybrids = rejectedHybridPackets,
                diagnosticNotifications = diagnosticOnlyNotificationPackets,
                diagnosticReadBundles = measurementDiagnosticReadBundles.map { it.copy(records = it.records.toList()) }
            )
            measurementDiagnosticReadBundles.clear()
            measurementEpochByGattEpoch.clear()
            now to snapshot
        }
        exportMeasurementBundle(stoppedAt, exportSnapshot)
    }

    private fun addMarkerInternal(label: String, now: Long) {
        val relative = if (measurementStartedAt > 0L) now - measurementStartedAt else 0L
        markerRows += listOf(relative.toString(), now.toString(), label).joinToString(";")
    }

    private fun exportMeasurementBundle(stoppedAt: Long, snapshot: MeasurementExportSnapshot) {
        val stamp = java.text.SimpleDateFormat("yyyy-MM-dd_HH-mm-ss-SSS", java.util.Locale.GERMANY).format(java.util.Date(snapshot.startedAt))
        val folder = "VMAXDashboard/Messfahrt_$stamp"
        val telemetry = buildRawTelemetryCsv(snapshot.rawRows)
        val liveTelemetry = "relative_ms;timestamp_ms;speed_kmh_candidate;battery_percent;voltage_v;current_a;power_w;electrical_power_w;power_provenance;motor_temp_c;battery_temp_c;trip_km;odometer_km;drive_raw_1505_b7;motor_load_raw_be;battery_state_raw_1509_b6;accessory_raw_b0;accessory_raw_b3;source_channel\n" + snapshot.telemetryRows.joinToString("\n")
        val markers = "relative_ms;timestamp_ms;marker\n" + snapshot.markerRows.joinToString("\n")
        // Quarantined notifications remain byte-exact in BLE_Rohdaten.csv, but only
        // accepted platform NOTIFICATION rows may influence analysis or learning.
        val analysisRows = rawTelemetryRowsForAnalysis(snapshot.rawRows)
        val (findings, analysisReport) = MeasurementAnalyzer.analyze(analysisRows, snapshot.markerRows)
        val measurementChannels = measurementChannelsFromRawRows(snapshot.rawRows)
        learningStore.merge(findings, _state.value.deviceName, stoppedAt)
        val learningJson = learningStore.exportJson()
        val diagnosticRecords = diagnosticRecordsForBundles(snapshot.diagnosticReadBundles)
        val diagnosticCounts = diagnosticReadCounts(diagnosticRecords)
        var diagnosticExportSucceeded = snapshot.diagnosticReadBundles.isEmpty()
        if (snapshot.diagnosticReadBundles.isNotEmpty()) {
            runCatching {
                // Keep the optional triple atomic from the syncer's perspective.
                // A partial triple is ignored publicly, while the core ride still exports.
                writeDownloadFile(
                    folder,
                    DIAGNOSTIC_READ_CSV_FILE,
                    "text/csv",
                    buildDiagnosticReadCsv(diagnosticRecords)
                )
                writeDownloadFile(
                    folder,
                    DIAGNOSTIC_READ_SUMMARY_FILE,
                    "text/plain",
                    buildDiagnosticReadSummary(snapshot.diagnosticReadBundles)
                )
                writeDownloadFile(
                    folder,
                    DIAGNOSTIC_READ_MANIFEST_FILE,
                    "application/json",
                    buildMeasurementDiagnosticManifest(
                        measurementName = "Messfahrt_$stamp",
                        bundles = snapshot.diagnosticReadBundles
                    ).toString(2)
                )
                diagnosticExportSucceeded = true
            }.onFailure { error ->
                addLog("Optionaler Deep-READ-Export fehlgeschlagen; Fahrtdaten werden trotzdem gespeichert: ${error.message}")
            }
        }
        val summary = buildString {
            appendLine("VMAX Dashboard Messfahrt")
            appendLine("Start: ${snapshot.startedAt}")
            appendLine("Ende: $stoppedAt")
            appendLine("Dauer_ms: ${stoppedAt - snapshot.startedAt}")
            appendLine("BLE_Pakete: ${snapshot.rawRows.size}")
            appendLine("BLE_Empfangen: ${snapshot.receivedNotifications}")
            appendLine("BLE_Akzeptiert: ${snapshot.acceptedNotifications}")
            appendLine("READ_Verworfen: ${snapshot.rejectedReads}")
            appendLine("Hybrid_Verworfen: ${snapshot.rejectedHybrids}")
            appendLine("Diagnose_Notifications_isoliert: ${snapshot.diagnosticNotifications}")
            appendLine("Verbindungsepochen: ${snapshot.connectionCount}")
            appendLine("Deep_READ_Scans: ${snapshot.diagnosticReadBundles.size}")
            // "Antworten" means actual Android onCharacteristicRead callbacks,
            // not inventory, timeout or connection-observation rows.
            appendLine("Deep_READ_Antworten: ${diagnosticCounts.callbacks}")
            appendLine(
                "Deep_READ_Export: " + when {
                    snapshot.diagnosticReadBundles.isEmpty() -> "nicht vorhanden"
                    diagnosticExportSucceeded -> "vollständig"
                    else -> "fehlgeschlagen (Fahrtdaten separat gesichert)"
                }
            )
            appendLine("Marker: ${snapshot.markerRows.size}")
            appendLine("Gerät: ${_state.value.deviceName}")
            appendLine("Kanäle: ${measurementChannels.joinToString(",")}")
        }
        runCatching {
            writeDownloadFile(folder, "BLE_Rohdaten.csv", "text/csv", telemetry)
            writeDownloadFile(folder, "Live_Telemetrie.csv", "text/csv", liveTelemetry)
            writeDownloadFile(folder, "Ereignisse.csv", "text/csv", markers)
            writeDownloadFile(folder, "Zusammenfassung.txt", "text/plain", summary)
            writeDownloadFile(folder, "Automatische_Analyse.txt", "text/plain", analysisReport)
            writeDownloadFile(folder, "Lernprofil.json", "application/json", learningJson)
            historyStore.add(
                folder,
                _state.value.deviceName,
                snapshot.startedAt,
                stoppedAt,
                snapshot.rawRows.size,
                snapshot.markerRows.size,
                measurementChannels
            )
            val location = "Downloads/$folder"
            update { it.copy(lastExportMessage = "Messfahrt gespeichert: $location", autoAnalysisFindings = findings, learningProfileCount = learningStore.count(), sessionHistoryCount = historyStore.count(), lastSessionFolder = location) }
            addLog("Messfahrt gespeichert: $location")
        }.onFailure { error ->
            update { it.copy(lastExportMessage = "Speichern fehlgeschlagen: ${error.message}") }
            addLog("Messfahrt-Export fehlgeschlagen: ${error.message}")
        }
    }

    private fun buildMeasurementDiagnosticManifest(
        measurementName: String,
        bundles: List<DiagnosticReadBundle>
    ): JSONObject {
        val records = diagnosticRecordsForBundles(bundles)
        val counts = diagnosticReadCounts(records)
        val epochs = records.map(DiagnosticReadRecord::connectionEpoch).distinct().sorted()
        val epochArray = JSONArray().apply { epochs.forEach(::put) }
        val scanIds = JSONArray().apply { bundles.map(DiagnosticReadBundle::scanId).distinct().forEach(::put) }
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        return JSONObject()
            .put("schema", "vmax-bt638-deep-read-v3")
            .put("measurement", measurementName)
            .put("embedded_in_measurement", true)
            .put("read_scans", bundles.size)
            .put("scan_ids", scanIds)
            .put("completed", bundles.all(DiagnosticReadBundle::completed))
            .put("completed_scans", bundles.count(DiagnosticReadBundle::completed))
            .put("partial_scans", bundles.count { !it.completed })
            .put("start_ms", bundles.minOfOrNull(DiagnosticReadBundle::scanStartedAt) ?: JSONObject.NULL)
            .put("end_ms", bundles.maxOfOrNull(DiagnosticReadBundle::scanFinishedAt) ?: JSONObject.NULL)
            .put("connection_epochs", epochArray)
            .put(
                "measurement_connection_epochs",
                JSONArray().apply {
                    records.mapNotNull(DiagnosticReadRecord::measurementConnectionEpoch)
                        .distinct()
                        .sorted()
                        .forEach(::put)
                }
            )
            .put("read_attempts", counts.attempts)
            .put("read_callbacks", counts.callbacks)
            // Backwards-compatible alias with strict callback semantics.
            .put("read_responses", counts.callbacks)
            .put("read_success", counts.successes)
            .put("read_payload_callbacks", counts.payloadCallbacks)
            .put("read_valid_payloads", counts.validPayloads)
            .put("advertisement_payloads", counts.observationPayloads)
            .put("observations", counts.observations)
            .put("full_uuids", true)
            .put("characteristic_properties", true)
            .put("callback_payload_direct", true)
            .put("read_only", true)
            .put("bluetooth_address_included", false)
            .put("app_version", packageInfo.versionName.orEmpty())
            .put("created_at_ms", System.currentTimeMillis())
    }

    private fun writeDownloadFile(relativeFolder: String, fileName: String, mimeType: String, content: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, mimeType)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + relativeFolder)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("Datei $fileName konnte nicht angelegt werden")
            try {
                resolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(content) }
                    ?: error("Datei $fileName konnte nicht geschrieben werden")
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                check(resolver.update(uri, values, null, null) == 1) {
                    "Datei $fileName konnte nicht veröffentlicht werden"
                }
            } catch (error: Throwable) {
                runCatching { resolver.delete(uri, null, null) }
                throw error
            }
        } else {
            val base = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: error("Speicher nicht verfügbar")
            val folder = File(base, relativeFolder).apply { mkdirs() }
            File(folder, fileName).writeText(content)
        }
    }

    fun exportSessionCsv() {
        // BLE callbacks may append rows on a binder thread while the UI starts a
        // manual export. Freeze the same immutable snapshot used by the bundle
        // exporter so the CSV cannot contain a torn/partially iterated row list.
        val rawRows = synchronized(gattOperationLock) { sessionRows.toList() }
        val content = buildRawTelemetryCsv(rawRows)
        val fileName = "VMAX_Session_${System.currentTimeMillis()}.csv"
        runCatching {
            val location = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(MediaStore.Downloads.MIME_TYPE, "text/csv")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/VMAXDashboard")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: error("Download-Datei konnte nicht angelegt werden")
                try {
                    resolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(content) }
                        ?: error("Download-Datei konnte nicht geschrieben werden")
                    values.clear()
                    values.put(MediaStore.Downloads.IS_PENDING, 0)
                    check(resolver.update(uri, values, null, null) == 1) {
                        "Download-Datei konnte nicht veröffentlicht werden"
                    }
                } catch (error: Throwable) {
                    runCatching { resolver.delete(uri, null, null) }
                    throw error
                }
                "Downloads/VMAXDashboard"
            } else {
                val folder = File(context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), "VMAXDashboard").apply { mkdirs() }
                File(folder, fileName).writeText(content)
                folder.absolutePath
            }
            update { it.copy(lastExportMessage = "$fileName in $location gespeichert") }
            addLog("CSV exportiert: $fileName")
        }.onFailure { error ->
            update { it.copy(lastExportMessage = "Export fehlgeschlagen: ${error.message}") }
            addLog("CSV-Export fehlgeschlagen: ${error.message}")
        }
    }

    private fun changedByteIndexes(previous: ByteArray?, current: ByteArray): List<Int> {
        if (previous == null) return current.indices.toList()
        val max = maxOf(previous.size, current.size)
        return (0 until max).filter { index ->
            index >= previous.size || index >= current.size || previous[index] != current[index]
        }
    }

    private fun buildReport(
        action: String,
        candidates: List<ByteCandidate>,
        packets: Map<String, String>
    ): String = buildString {
        appendLine("format=VMAX_DECODER_LAB_V1")
        appendLine("model=VX2 Gear / VX2 4G")
        appendLine("action=$action")
        appendLine("createdAt=${System.currentTimeMillis()}")
        appendLine("gpsIncluded=false")
        appendLine("bluetoothAddressIncluded=false")
        appendLine("[candidates]")
        candidates.forEach {
            appendLine("${it.characteristic},${it.byteIndex},${it.beforeValue},${it.activeValue},${it.score}")
        }
        appendLine("[latestPackets]")
        packets.forEach { (channel, value) -> appendLine("$channel=$value") }
    }

    private fun shortUuid(uuid: UUID): String =
        normalizeGattShortUuid(uuid.toString())

    private fun addLog(message: String) {
        update { it.copy(log = (listOf(message) + it.log).take(80)) }
    }

    private inline fun update(block: (ScooterState) -> ScooterState) {
        _state.update(block)
    }
}

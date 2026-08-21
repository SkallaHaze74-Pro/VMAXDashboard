package de.kevin.vmaxdashboard

import android.annotation.SuppressLint
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Shared four-hex GATT label used by BleScooterManager logs and the READ archive. */
internal fun normalizeGattShortUuid(uuid: String): String {
    val normalized = uuid.lowercase()
    Regex("^da1a([0-9a-f]{4})-d532-4285-be94-b07a3e11a098$")
        .matchEntire(normalized)
        ?.groupValues
        ?.get(1)
        ?.let { return it.uppercase() }
    Regex("^0000([0-9a-f]{4})-0000-1000-8000-00805f9b34fb$")
        .matchEntire(normalized)
        ?.groupValues
        ?.get(1)
        ?.let { return it.uppercase() }
    return normalized.takeIf { it.matches(Regex("^[0-9a-f]{4}$")) }?.uppercase()
        ?: uuid.uppercase()
}

internal fun diagnosticAdvertisementRecord(
    timestampMs: Long,
    connectionEpoch: Long,
    scanId: String,
    rssi: Int,
    deviceName: String,
    payload: ByteArray
): DiagnosticReadRecord = DiagnosticReadRecord(
    timestampMs = timestampMs,
    serviceUuid = "",
    characteristicUuid = "",
    shortId = "ADV",
    properties = "BROADCAST",
    status = null,
    length = payload.size,
    hex = payload.joinToString("-") { "%02X".format(it.toInt() and 0xFF) },
    connectionEpoch = connectionEpoch,
    measurementConnectionEpoch = null,
    evidence = CapabilityEvidence.BT638_OBSERVED.label,
    meaning = "BLE advertisement • device=${deviceName.ifBlank { BleScooterManager.TARGET_NAME }}",
    scanId = scanId,
    propertiesRaw = BluetoothGattCharacteristic.PROPERTY_BROADCAST,
    callbackReceived = false,
    recordKind = DiagnosticRecordKind.BLE_OBSERVATION,
    outcome = DiagnosticReadOutcome.ADVERTISEMENT_OBSERVED,
    payloadValid = payload.isNotEmpty(),
    rssi = rssi
)

/**
 * Short POWER windows should capture the live battery/controller state before
 * identity and long diagnostic blocks. Every readable characteristic is still
 * attempted; this function changes only the safe READ order.
 */
internal fun diagnosticReadPriority(uuid: String): Int = when (uuid.uppercase()) {
    "1509" -> 0 // Battery live state: SOC, voltage, current, temperatures
    "150C" -> 1 // BatteryCellUpdate candidate
    "1502" -> 2 // Battery/static candidate observed live
    "150A" -> 3 // Motor live layout
    "1514" -> 10 // Error
    "1516" -> 11 // SerialNumbers
    "1517" -> 12 // ErrorString
    "1518" -> 13 // DebugLog
    "160C" -> 20 // Motor tuning READ/readback
    "1E03" -> 30 // WirelessRemote
    "1E04" -> 31 // WirelessRemoteAction
    else -> 100
}

/**
 * Read-only GATT inventory and safe READ scanner.
 *
 * The existing BleScooterManager owns the BluetoothGatt connection. To avoid a
 * second connection, this helper inspects that same connection and sequentially
 * reads every characteristic that advertises PROPERTY_READ. It never writes.
 *
 * READ answers are deliberately kept separate from live telemetry and adaptive
 * decoder learning. They are archived as diagnostic evidence with UUID, status,
 * length and exact payload so Battery/Controller/Serial/Firmware candidates can be
 * compared later without reinterpreting them as notification data.
 */
data class GattCharacteristicInfo(
    val serviceUuid: String,
    val characteristicUuid: String,
    val properties: String,
    val readable: Boolean,
    val notifiable: Boolean,
    val indicatable: Boolean,
    val writable: Boolean,
    val lastReadStarted: Boolean = false,
    val lastReadStatus: Int? = null,
    val lastReadLength: Int? = null,
    val lastReadHex: String = "",
    val lastReadAtMs: Long = 0L,
    val evidence: String = CapabilityEvidence.UNKNOWN.label,
    val family: String = "Unbekannt",
    val meaning: String = "",
    val sources: List<String> = emptyList(),
    val confirmedDetails: List<String> = emptyList(),
    val unknownDetails: List<String> = emptyList(),
    val uiHint: String = "",
    val safetyNote: String = ""
)

data class GattScanState(
    val running: Boolean = false,
    val completed: Boolean = false,
    val serviceCount: Int = 0,
    val characteristicCount: Int = 0,
    val readableCount: Int = 0,
    val startedReads: Int = 0,
    val successfulReads: Int = 0,
    val payloadReads: Int = 0,
    val confirmedCount: Int = 0,
    val observedCount: Int = 0,
    val sdkKnownCount: Int = 0,
    val unknownCount: Int = 0,
    val entries: List<GattCharacteristicInfo> = emptyList(),
    val status: String = "Warte auf Verbindung"
)

/** A platform READ cannot be cancelled; finalization must retire that whole epoch. */
internal fun shouldPoisonDiagnosticEpochOnFinalize(
    hasActiveSession: Boolean,
    hasInFlightPlatformRead: Boolean
): Boolean = hasActiveSession && hasInFlightPlatformRead

class GattReadScanner(private val manager: BleScooterManager) {
    private data class ReadKey(val serviceUuid: String, val characteristicUuid: String)

    private val handler = Handler(Looper.getMainLooper())
    private val readCoordinator = SequentialGattReadCoordinator<ReadKey>()
    private val characteristicsByKey = mutableMapOf<ReadKey, BluetoothGattCharacteristic>()
    private val readRecords = mutableListOf<DiagnosticReadRecord>()
    private val _state = MutableStateFlow(GattScanState())
    val state: StateFlow<GattScanState> = _state

    private var generation = 0
    private var activeSession: DiagnosticGattReadSession? = null
    private var readTimeout: Runnable? = null
    private var preConnectionAttemptTimeout: Runnable? = null
    private var scanStartedAt = 0L
    private var currentConnectionEpoch: Long? = null
    private var currentScanId = ""
    private var observedDeviceName = BleScooterManager.TARGET_NAME
    private var armedAt = 0L
    private var archiveNoDeviceAttemptRequested = false
    @Volatile private var readBeforeNotificationsRequested = false
    @Volatile private var pendingReadConnectionEpoch: Long? = null

    init {
        manager.registerDiagnosticGattReadObserver(this)
    }

    /** Keep scanner state single-threaded without delaying callbacks already on UI/main. */
    private inline fun dispatch(crossinline action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action()
        else handler.post { action() }
    }

    /** Arms durable evidence before POWER/search, even while no GATT exists yet. */
    fun armForNextConnection(
        readBeforeNotifications: Boolean = false,
        archiveIfNoDevice: Boolean = true
    ) {
        val now = System.currentTimeMillis()
        armedAt = now
        readBeforeNotificationsRequested = readBeforeNotifications
        archiveNoDeviceAttemptRequested =
            archiveNoDeviceAttemptRequested || archiveIfNoDevice
        val epoch = manager.currentDiagnosticConnectionEpoch()
        if (epoch != null) {
            pendingReadConnectionEpoch = epoch
            ensureAttempt(epoch, now)
            return
        }
        cancelPreConnectionAttemptTimeout()
        val timeout = Runnable {
            if (currentConnectionEpoch == null && armedAt == now) {
                val keepPowerPriority = readBeforeNotificationsRequested
                if (archiveNoDeviceAttemptRequested) {
                    ensureAttempt(PRE_GATT_CONNECTION_EPOCH, now)
                    archiveCurrentAttempt(
                        connectionEpoch = PRE_GATT_CONNECTION_EPOCH,
                        finishedAt = System.currentTimeMillis(),
                        baseStatus = "POWER/Scan-Versuch ohne BT638-Verbindung gesichert",
                        completed = false,
                        completionOutcome = DiagnosticReadOutcome.SCAN_PARTIAL
                    )
                    if (keepPowerPriority) {
                        armedAt = now
                        readBeforeNotificationsRequested = true
                    }
                } else {
                    armedAt = 0L
                    readBeforeNotificationsRequested = false
                    pendingReadConnectionEpoch = null
                    _state.value = _state.value.copy(
                        status = "BT638 im letzten automatischen Scanfenster nicht gesehen"
                    )
                }
            }
        }
        preConnectionAttemptTimeout = timeout
        handler.postDelayed(timeout, PRE_CONNECTION_ATTEMPT_TIMEOUT_MS)
    }

    internal fun onDiagnosticConnectionOpened(connectionEpoch: Long) {
        dispatch {
            cancelPreConnectionAttemptTimeout()
            if (readBeforeNotificationsRequested) pendingReadConnectionEpoch = connectionEpoch
            ensureAttempt(connectionEpoch, armedAt.takeIf { it > 0L } ?: System.currentTimeMillis())
        }
    }

    /** True only for a user-armed POWER/connect attempt, never for an old epoch. */
    internal fun shouldReadBeforeNotificationSetup(
        @Suppress("UNUSED_PARAMETER") connectionEpoch: Long
    ): Boolean = readBeforeNotificationsRequested

    internal fun onDiagnosticAdvertisementObserved(
        connectionEpoch: Long,
        timestampMs: Long,
        rssi: Int,
        deviceName: String,
        payload: ByteArray
    ) {
        val exactPayload = payload.copyOf()
        dispatch {
            cancelPreConnectionAttemptTimeout()
            observedDeviceName = deviceName.ifBlank { BleScooterManager.TARGET_NAME }
            ensureAttempt(connectionEpoch, armedAt.takeIf { it > 0L } ?: timestampMs)
            readRecords += diagnosticAdvertisementRecord(
                timestampMs = timestampMs,
                connectionEpoch = connectionEpoch,
                scanId = currentScanId,
                rssi = rssi,
                deviceName = observedDeviceName,
                payload = exactPayload
            )
            _state.value = _state.value.copy(
                status = if (exactPayload.isNotEmpty()) {
                    "BT638 Advertisement exakt gesichert • RSSI $rssi dBm"
                } else {
                    "BT638 Advertisement-Metadaten gesichert (kein ScanRecord) • RSSI $rssi dBm"
                }
            )
        }
    }

    internal fun onNotificationInventoryDiscovered(
        connectionEpoch: Long,
        entries: List<NotificationInventoryEntry>
    ) {
        val frozen = entries.toList()
        dispatch {
            if (!hasUnarchivedAttempt(connectionEpoch)) return@dispatch
            frozen.forEach { entry ->
                recordNotificationEvent(
                    connectionEpoch = connectionEpoch,
                    entry = entry,
                    event = null,
                    status = null,
                    meaning = "NOTIFY_INVENTORY phase=${entry.phase.name}"
                )
            }
        }
    }

    internal fun onNotificationSubscriptionEvent(
        connectionEpoch: Long,
        entry: NotificationInventoryEntry,
        event: NotificationSubscriptionEvent,
        status: Int?
    ) {
        dispatch {
            if (!hasUnarchivedAttempt(connectionEpoch)) return@dispatch
            recordNotificationEvent(
                connectionEpoch = connectionEpoch,
                entry = entry,
                event = event,
                status = status,
                meaning = "NOTIFY_SUBSCRIPTION phase=${entry.phase.name} event=${event.name}"
            )
        }
    }

    internal fun onDiagnosticGattReady(connectionEpoch: Long) {
        dispatch {
            if (manager.currentDiagnosticConnectionEpoch() != connectionEpoch ||
                !hasUnarchivedAttempt(connectionEpoch)
            ) return@dispatch
            pendingReadConnectionEpoch = connectionEpoch
            ensureAttempt(connectionEpoch, System.currentTimeMillis())
            if (!scanAndRead()) {
                _state.value = _state.value.copy(status = "Deep READ angefordert • warte auf freien GATT-Slot")
            }
        }
    }

    internal fun hasPendingReadRequest(connectionEpoch: Long): Boolean =
        pendingReadConnectionEpoch == connectionEpoch && !_state.value.running

    private fun hasUnarchivedAttempt(connectionEpoch: Long): Boolean =
        currentConnectionEpoch == connectionEpoch && currentScanId.isNotBlank() &&
            readRecords.isNotEmpty()

    internal fun onDiagnosticGattSlotAvailable(connectionEpoch: Long) {
        dispatch {
            if (hasPendingReadRequest(connectionEpoch)) scanAndRead()
        }
    }

    /** Synchronously disarms a user-cancelled pre-connection attempt. */
    internal fun cancelPendingAttemptForUserDisconnect() {
        cancelPreConnectionAttemptTimeout()
        if (activeSession != null) return
        val epoch = currentConnectionEpoch
        when {
            epoch != null && hasUnarchivedAttempt(epoch) -> {
                val finishedAt = System.currentTimeMillis()
                recordConnectionEvent(
                    connectionEpoch = epoch,
                    timestampMs = finishedAt,
                    outcome = DiagnosticReadOutcome.SCAN_PARTIAL,
                    meaning = "User disconnected before diagnostic READ started"
                )
                archiveCurrentAttempt(
                    connectionEpoch = epoch,
                    finishedAt = finishedAt,
                    baseStatus = "Vorbereiteter Diagnoseversuch beim Trennen gesichert",
                    completed = false,
                    completionOutcome = DiagnosticReadOutcome.SCAN_PARTIAL
                )
            }
            armedAt > 0L && archiveNoDeviceAttemptRequested -> {
                val startedAt = armedAt
                ensureAttempt(PRE_GATT_CONNECTION_EPOCH, startedAt)
                archiveCurrentAttempt(
                    connectionEpoch = PRE_GATT_CONNECTION_EPOCH,
                    finishedAt = System.currentTimeMillis(),
                    baseStatus = "Abgebrochener Scanversuch ohne BT638-Verbindung gesichert",
                    completed = false,
                    completionOutcome = DiagnosticReadOutcome.SCAN_PARTIAL
                )
            }
        }
        armedAt = 0L
        readBeforeNotificationsRequested = false
        archiveNoDeviceAttemptRequested = false
        pendingReadConnectionEpoch = null
    }

    fun reset() {
        finalizeForMeasurementExport(resumeDeferredSetup = false)
        generation++
        cancelReadTimeout()
        cancelPreConnectionAttemptTimeout()
        readCoordinator.cancel()
        characteristicsByKey.clear()
        readRecords.clear()
        scanStartedAt = 0L
        currentConnectionEpoch = null
        currentScanId = ""
        armedAt = 0L
        readBeforeNotificationsRequested = false
        archiveNoDeviceAttemptRequested = false
        pendingReadConnectionEpoch = null
        val session = activeSession
        activeSession = null
        if (session != null) manager.cancelDiagnosticGattReadScan(session, this, resumeDeferredSetup = false)
        _state.value = GattScanState()
    }

    /**
     * UI disconnect updates can race the GATT disconnect callback. Never erase an
     * active scan here: the manager callback archives every answer collected so far.
     */
    fun resetIfIdle(): Boolean {
        if (_state.value.running || readRecords.isNotEmpty()) return false
        reset()
        return true
    }

    @SuppressLint("MissingPermission")
    fun scanAndRead(): Boolean {
        if (_state.value.running) return false
        manager.currentDiagnosticConnectionEpoch()?.let { epoch ->
            pendingReadConnectionEpoch = epoch
            ensureAttempt(epoch, armedAt.takeIf { it > 0L } ?: System.currentTimeMillis())
        }
        val session = manager.beginDiagnosticGattReadScan(this)
        if (session == null) {
            _state.value = _state.value.copy(status = "GATT-Verbindung noch nicht bereit")
            return false
        }
        pendingReadConnectionEpoch = null
        activeSession = session
        ensureAttempt(session.connectionEpoch, System.currentTimeMillis())

        val services = manager.diagnosticGattServices(session)
        if (services == null) {
            activeSession = null
            manager.finishDiagnosticGattReadScan(session, this)
            archiveCurrentAttempt(
                connectionEpoch = session.connectionEpoch,
                finishedAt = System.currentTimeMillis(),
                baseStatus = "GATT-Verbindung wurde während des Scans gewechselt",
                completed = false,
                completionOutcome = DiagnosticReadOutcome.SCAN_PARTIAL
            )
            _state.value = _state.value.copy(status = "GATT-Verbindung wurde während des Scans gewechselt")
            return false
        }
        val entries = services.flatMap { service ->
            val serviceShort = normalizeGattShortUuid(service.uuid.toString())
            service.characteristics.map { characteristic ->
                val characteristicShort = normalizeGattShortUuid(characteristic.uuid.toString())
                val p = characteristic.properties
                val knowledge = VmaxSdkCapabilityCatalog.classify(
                    service.uuid.toString(),
                    characteristic.uuid.toString()
                )
                GattCharacteristicInfo(
                    serviceUuid = serviceShort,
                    characteristicUuid = characteristicShort,
                    properties = propertyText(p),
                    readable = p and BluetoothGattCharacteristic.PROPERTY_READ != 0,
                    notifiable = p and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0,
                    indicatable = p and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0,
                    writable = p and BluetoothGattCharacteristic.PROPERTY_WRITE != 0 ||
                        p and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0,
                    evidence = knowledge.evidence.label,
                    family = knowledge.family,
                    meaning = knowledge.meaning,
                    sources = knowledge.sources,
                    confirmedDetails = knowledge.confirmedDetails,
                    unknownDetails = knowledge.unknownDetails,
                    uiHint = knowledge.uiHint,
                    safetyNote = knowledge.safetyNote
                )
            }
        }.sortedWith(
            compareBy<GattCharacteristicInfo>({ evidenceRank(it.evidence) }, { diagnosticReadPriority(it.characteristicUuid) }, { it.serviceUuid }, { it.characteristicUuid })
        )

        // Diagnostic/identity/battery READs go first, but every PROPERTY_READ
        // characteristic is still attempted exactly once.
        val readableCharacteristics = buildList {
            services.forEach { service ->
                service.characteristics
                    .filter { it.properties and BluetoothGattCharacteristic.PROPERTY_READ != 0 }
                    .forEach(::add)
            }
        }.sortedWith(
            compareBy<BluetoothGattCharacteristic>(
                { diagnosticReadPriority(normalizeGattShortUuid(it.uuid.toString())) },
                { normalizeGattShortUuid(it.service.uuid.toString()) },
                { normalizeGattShortUuid(it.uuid.toString()) }
            )
        )
        characteristicsByKey.clear()
        readableCharacteristics.forEach { characteristic ->
            characteristicsByKey[readKey(characteristic)] = characteristic
        }
        readCoordinator.reset(readableCharacteristics.map(::readKey))

        val confirmed = entries.count { it.evidence == CapabilityEvidence.BT638_CONFIRMED.label }
        val observed = entries.count { it.evidence == CapabilityEvidence.BT638_OBSERVED.label }
        val sdkKnown = entries.count { it.evidence == CapabilityEvidence.SDK_CONFIRMED.label }
        val unknown = entries.count { it.evidence == CapabilityEvidence.UNKNOWN.label }
        val currentGeneration = ++generation
        _state.value = GattScanState(
            running = true,
            serviceCount = services.size,
            characteristicCount = entries.size,
            readableCount = readableCharacteristics.size,
            confirmedCount = confirmed,
            observedCount = observed,
            sdkKnownCount = sdkKnown,
            unknownCount = unknown,
            entries = entries,
            status = "${services.size} Dienste, ${entries.size} Characteristics – Deep READ startet"
        )
        readNext(session, currentGeneration)
        return true
    }

    @SuppressLint("MissingPermission")
    private fun readNext(session: DiagnosticGattReadSession, expectedGeneration: Int) {
        if (expectedGeneration != generation || activeSession != session) return
        val key = readCoordinator.beginNext()
        if (key == null) {
            finishScan(session, expectedGeneration)
            return
        }
        val characteristic = characteristicsByKey[key]
        if (characteristic == null) {
            readCoordinator.complete(key)
            handler.post { readNext(session, expectedGeneration) }
            return
        }

        val readStarted = manager.readDiagnosticCharacteristic(session, characteristic)
        val service = normalizeGattShortUuid(characteristic.service.uuid.toString())
        val uuid = normalizeGattShortUuid(characteristic.uuid.toString())
        _state.value = _state.value.copy(
            startedReads = _state.value.startedReads + if (readStarted) 1 else 0,
            status = "Lese $uuid • ${readCoordinator.remaining} übrig",
            entries = _state.value.entries.map {
                if (it.serviceUuid == service && it.characteristicUuid == uuid) {
                    it.copy(lastReadStarted = readStarted)
                } else it
            }
        )

        if (!readStarted) {
            recordResult(session, characteristic, STATUS_READ_START_FAILED, "")
            readCoordinator.complete(key)
            handler.post { readNext(session, expectedGeneration) }
            return
        }

        val timeout = Runnable {
            if (expectedGeneration == generation && activeSession == session && readCoordinator.timeout(key)) {
                recordResult(session, characteristic, STATUS_READ_TIMEOUT, "")
                // Android still may own the timed-out GATT operation. Starting the
                // next characteristic would risk a cascade of false failures. Keep
                // this partial dump and let a later scan/reconnect retry safely.
                abortAndArchive(
                    session = session,
                    expectedGeneration = expectedGeneration,
                    status = "READ $uuid ohne Callback – Teil-Dump gesichert; Reconnect erforderlich",
                    requireReconnect = true
                )
            }
        }
        readTimeout = timeout
        handler.postDelayed(timeout, READ_TIMEOUT_MS)
    }

    internal fun onDiagnosticCharacteristicRead(
        session: DiagnosticGattReadSession,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
        status: Int
    ) {
        dispatch {
            if (activeSession != session) return@dispatch
            val key = readKey(characteristic)
            if (!readCoordinator.complete(key)) return@dispatch
            cancelReadTimeout()
            val uuid = normalizeGattShortUuid(characteristic.uuid.toString())
            val hex = diagnosticReadHex(status, value)
            recordResult(session, characteristic, status, hex)
            _state.value = _state.value.copy(
                status = if (status == BluetoothGatt.GATT_SUCCESS) {
                    "READ $uuid beantwortet • ${readCoordinator.remaining} übrig"
                } else {
                    "READ $uuid fehlgeschlagen ($status) • ${readCoordinator.remaining} übrig"
                }
            )
            readNext(session, generation)
        }
    }

    internal fun onDiagnosticConnectionClosed(session: DiagnosticGattReadSession) {
        dispatch {
            if (activeSession != session) return@dispatch
            generation++
            cancelReadTimeout()
            readCoordinator.inFlight
                ?.let(characteristicsByKey::get)
                ?.let { recordResult(session, it, STATUS_CONNECTION_CLOSED, "") }
            readCoordinator.cancel()
            characteristicsByKey.clear()
            activeSession = null
            val finishedAt = System.currentTimeMillis()
            recordConnectionEvent(
                connectionEpoch = session.connectionEpoch,
                timestampMs = finishedAt,
                outcome = DiagnosticReadOutcome.CONNECTION_CLOSED,
                meaning = "GATT connection closed during Deep READ"
            )
            archiveCurrentAttempt(
                connectionEpoch = session.connectionEpoch,
                finishedAt = finishedAt,
                baseStatus = "READ-Scan abgebrochen: Verbindung beendet",
                completed = false,
                completionOutcome = DiagnosticReadOutcome.SCAN_PARTIAL
            )
            _state.value = _state.value.copy(
                running = false,
                completed = false,
                status = "READ-Scan abgebrochen: Verbindung beendet • bisherige Antworten werden gesichert"
            )
        }
    }

    internal fun onDiagnosticConnectionClosedBeforeRead(connectionEpoch: Long) {
        dispatch {
            if (!hasUnarchivedAttempt(connectionEpoch)) return@dispatch
            val finishedAt = System.currentTimeMillis()
            recordConnectionEvent(
                connectionEpoch = connectionEpoch,
                timestampMs = finishedAt,
                outcome = DiagnosticReadOutcome.CONNECTION_CLOSED,
                meaning = "Connection closed before first diagnostic READ session"
            )
            // ScanRecord is queued before connectGatt and scanner callbacks are
            // serialized on this handler. Freeze immediately so a 1–3 second
            // POWER window cannot be lost to a later UI/reset lifecycle event.
            archiveCurrentAttempt(
                connectionEpoch = connectionEpoch,
                finishedAt = finishedAt,
                baseStatus = "Kurzverbindung vor erstem READ beendet – Versuchsdaten gesichert",
                completed = false,
                completionOutcome = DiagnosticReadOutcome.SCAN_PARTIAL
            )
            _state.value = _state.value.copy(
                running = false,
                completed = false,
                status = "Kurzverbindung beendet • Advertisement/Versuchsdaten gesichert"
            )
        }
    }

    /** Archives advertisement/attempt evidence when Android stalls before READ can start. */
    internal fun onDiagnosticConnectionStageTimeout(
        connectionEpoch: Long,
        stage: GattConnectionStage,
        reason: String
    ) {
        dispatch {
            if (!hasUnarchivedAttempt(connectionEpoch)) return@dispatch
            generation++
            cancelReadTimeout()
            readCoordinator.cancel()
            characteristicsByKey.clear()
            activeSession = null
            val finishedAt = System.currentTimeMillis()
            val outcome = when (stage) {
                GattConnectionStage.CONNECTING -> DiagnosticReadOutcome.CONNECTION_TIMEOUT
                GattConnectionStage.DISCOVERING_SERVICES ->
                    DiagnosticReadOutcome.SERVICE_DISCOVERY_TIMEOUT
            }
            recordConnectionEvent(
                connectionEpoch = connectionEpoch,
                timestampMs = finishedAt,
                outcome = outcome,
                meaning = "$reason; exact connection epoch closed"
            )
            archiveCurrentAttempt(
                connectionEpoch = connectionEpoch,
                finishedAt = finishedAt,
                baseStatus = "$reason – Teil-Dump gesichert",
                completed = false,
                completionOutcome = DiagnosticReadOutcome.SCAN_PARTIAL
            )
            _state.value = _state.value.copy(
                running = false,
                completed = false,
                status = "$reason • Teil-Dump gesichert"
            )
        }
    }

    /**
     * Freezes in-flight evidence synchronously before BleScooterManager snapshots a
     * measurement. This keeps a partial dump attached to the ride on Stop/Disconnect.
     */
    internal fun finalizeForMeasurementExport(
        resumeDeferredSetup: Boolean,
        forceAttempt: Boolean = false
    ): Boolean {
        val epoch = activeSession?.connectionEpoch
            ?: currentConnectionEpoch
            ?: manager.currentDiagnosticConnectionEpoch()
            ?: return false
        if (readRecords.isEmpty() && activeSession == null) {
            if (!forceAttempt || pendingReadConnectionEpoch != epoch || armedAt <= 0L) return false
            ensureAttempt(epoch, armedAt.takeIf { it > 0L } ?: System.currentTimeMillis())
        }
        generation++
        cancelReadTimeout()
        cancelPreConnectionAttemptTimeout()
        val session = activeSession
        val inFlightCharacteristic = readCoordinator.inFlight?.let(characteristicsByKey::get)
        val mustReconnect = shouldPoisonDiagnosticEpochOnFinalize(
            hasActiveSession = session != null,
            hasInFlightPlatformRead = inFlightCharacteristic != null
        )
        if (session != null && inFlightCharacteristic != null) {
            recordResult(session, inFlightCharacteristic, STATUS_CONNECTION_CLOSED, "")
        }
        readCoordinator.cancel()
        characteristicsByKey.clear()
        activeSession = null
        val finishedAt = System.currentTimeMillis()
        recordConnectionEvent(
            connectionEpoch = epoch,
            timestampMs = finishedAt,
            outcome = DiagnosticReadOutcome.SCAN_PARTIAL,
            meaning = if (inFlightCharacteristic != null) {
                "Measurement export requested during platform READ; epoch must reconnect"
            } else {
                "Diagnostic evidence frozen before measurement export"
            }
        )
        val baseStatus = if (inFlightCharacteristic != null) {
            "Teil-Dump vor Messfahrt-Export gesichert – laufender READ erzwingt Reconnect"
        } else {
            "Teil-Dump vor Messfahrt-Export gesichert"
        }
        archiveCurrentAttempt(
            connectionEpoch = epoch,
            finishedAt = finishedAt,
            baseStatus = baseStatus,
            completed = false,
            completionOutcome = DiagnosticReadOutcome.SCAN_PARTIAL
        )
        if (session != null) {
            if (mustReconnect) {
                // Android has no cancellation primitive for readCharacteristic.
                // Poison/close this epoch so a late callback cannot overlap the
                // next descriptor or motor operation.
                manager.timeoutDiagnosticGattReadScan(session, this)
            } else {
                manager.cancelDiagnosticGattReadScan(
                    session,
                    this,
                    resumeDeferredSetup = resumeDeferredSetup
                )
            }
        }
        _state.value = _state.value.copy(
            running = false,
            completed = false,
            status = baseStatus
        )
        return true
    }

    private fun recordResult(
        session: DiagnosticGattReadSession,
        characteristic: BluetoothGattCharacteristic,
        status: Int,
        hex: String
    ) {
        val now = System.currentTimeMillis()
        val serviceUuid = characteristic.service.uuid.toString().lowercase()
        val characteristicUuid = characteristic.uuid.toString().lowercase()
        val serviceShort = normalizeGattShortUuid(serviceUuid)
        val shortId = normalizeGattShortUuid(characteristicUuid)
        val knowledge = VmaxSdkCapabilityCatalog.classify(serviceUuid, characteristicUuid)
        val length = hexLength(hex)
        val callbackReceived = status >= 0
        readRecords += DiagnosticReadRecord(
            timestampMs = now,
            serviceUuid = serviceUuid,
            characteristicUuid = characteristicUuid,
            shortId = shortId,
            properties = propertyArchiveText(characteristic.properties),
            status = status,
            length = length,
            hex = hex,
            connectionEpoch = session.connectionEpoch,
            measurementConnectionEpoch = null,
            evidence = knowledge.evidence.label,
            meaning = knowledge.meaning,
            scanId = currentScanId,
            propertiesRaw = characteristic.properties,
            callbackReceived = callbackReceived,
            recordKind = if (callbackReceived) {
                DiagnosticRecordKind.GATT_READ_CALLBACK
            } else {
                DiagnosticRecordKind.GATT_READ_EVENT
            },
            outcome = diagnosticReadOutcome(
                status = status,
                callbackReceived = callbackReceived,
                recordKind = if (callbackReceived) {
                    DiagnosticRecordKind.GATT_READ_CALLBACK
                } else {
                    DiagnosticRecordKind.GATT_READ_EVENT
                }
            ),
            payloadValid = callbackReceived && status == BluetoothGatt.GATT_SUCCESS
        )
        val counts = diagnosticReadCounts(readRecords)
        _state.value = _state.value.copy(
            successfulReads = counts.successes,
            payloadReads = counts.validPayloads,
            entries = _state.value.entries.map {
                if (it.serviceUuid == serviceShort && it.characteristicUuid == shortId) {
                    it.copy(
                        lastReadStatus = status,
                        lastReadLength = length,
                        lastReadHex = hex,
                        lastReadAtMs = now
                    )
                } else it
            }
        )
    }

    private fun finishScan(session: DiagnosticGattReadSession, expectedGeneration: Int) {
        if (expectedGeneration != generation || activeSession != session || readCoordinator.running) return
        cancelReadTimeout()
        activeSession = null
        characteristicsByKey.clear()
        val finishedAt = System.currentTimeMillis()
        val baseStatus = "READ-Scan fertig: ${_state.value.successfulReads}/${_state.value.readableCount} beantwortet • ${_state.value.payloadReads} Payloads"
        _state.value = _state.value.copy(
            running = false,
            completed = true,
            status = "$baseStatus • sichere Dump-Dateien werden gespeichert"
        )
        recordConnectionEvent(
            connectionEpoch = session.connectionEpoch,
            timestampMs = finishedAt,
            outcome = DiagnosticReadOutcome.SCAN_COMPLETED,
            meaning = "Sequential PROPERTY_READ scan completed"
        )
        archiveCurrentAttempt(
            connectionEpoch = session.connectionEpoch,
            finishedAt = finishedAt,
            baseStatus = baseStatus,
            completed = true,
            completionOutcome = DiagnosticReadOutcome.SCAN_COMPLETED
        )
        manager.finishDiagnosticGattReadScan(session, this)
    }

    private fun abortAndArchive(
        session: DiagnosticGattReadSession,
        expectedGeneration: Int,
        status: String,
        requireReconnect: Boolean = false
    ) {
        if (expectedGeneration != generation || activeSession != session) return
        generation++
        cancelReadTimeout()
        readCoordinator.cancel()
        activeSession = null
        characteristicsByKey.clear()
        val finishedAt = System.currentTimeMillis()
        _state.value = _state.value.copy(
            running = false,
            completed = false,
            status = status
        )
        archiveCurrentAttempt(
            connectionEpoch = session.connectionEpoch,
            finishedAt = finishedAt,
            baseStatus = status,
            completed = false,
            completionOutcome = DiagnosticReadOutcome.SCAN_PARTIAL
        )
        if (requireReconnect) {
            manager.timeoutDiagnosticGattReadScan(session, this)
        } else {
            manager.cancelDiagnosticGattReadScan(session, this)
        }
    }

    private fun ensureAttempt(connectionEpoch: Long, startedAt: Long) {
        if (currentConnectionEpoch == connectionEpoch && currentScanId.isNotBlank()) return
        val previousEpoch = currentConnectionEpoch
        if (previousEpoch != null && readRecords.isNotEmpty()) {
            archiveCurrentAttempt(
                connectionEpoch = previousEpoch,
                finishedAt = System.currentTimeMillis(),
                baseStatus = "Vorheriger Diagnoseversuch beim Verbindungswechsel gesichert",
                completed = false,
                completionOutcome = DiagnosticReadOutcome.SCAN_PARTIAL
            )
        }
        readRecords.clear()
        currentConnectionEpoch = connectionEpoch
        scanStartedAt = startedAt
        currentScanId = diagnosticReadScanId(startedAt, connectionEpoch)
        readRecords += DiagnosticReadRecord(
            timestampMs = startedAt,
            serviceUuid = "",
            characteristicUuid = "",
            shortId = "ATTEMPT",
            properties = "-",
            status = null,
            length = 0,
            hex = "",
            connectionEpoch = connectionEpoch,
            measurementConnectionEpoch = null,
            evidence = CapabilityEvidence.BT638_OBSERVED.label,
            meaning = "Deep READ / BLE observation attempt started",
            scanId = currentScanId,
            propertiesRaw = 0,
            callbackReceived = false,
            recordKind = DiagnosticRecordKind.CONNECTION_EVENT,
            outcome = DiagnosticReadOutcome.ATTEMPT_STARTED,
            payloadValid = false
        )
    }

    private fun recordNotificationEvent(
        connectionEpoch: Long,
        entry: NotificationInventoryEntry,
        event: NotificationSubscriptionEvent?,
        status: Int?,
        meaning: String
    ) {
        val characteristicShort = normalizeGattShortUuid(entry.characteristicUuid)
        val serviceShort = normalizeGattShortUuid(entry.serviceUuid)
        val knowledge = VmaxSdkCapabilityCatalog.classify(entry.serviceUuid, entry.characteristicUuid)
        readRecords += DiagnosticReadRecord(
            timestampMs = System.currentTimeMillis(),
            serviceUuid = entry.serviceUuid,
            characteristicUuid = entry.characteristicUuid,
            shortId = characteristicShort,
            properties = propertyArchiveText(entry.properties),
            status = status,
            length = 0,
            hex = "",
            connectionEpoch = connectionEpoch,
            measurementConnectionEpoch = null,
            evidence = knowledge.evidence.label,
            meaning = if (event == null) meaning else "$meaning status=${status ?: "none"}",
            scanId = currentScanId,
            propertiesRaw = entry.properties,
            callbackReceived = false,
            recordKind = DiagnosticRecordKind.CONNECTION_EVENT,
            outcome = DiagnosticReadOutcome.OTHER,
            payloadValid = false
        )
    }

    private fun recordConnectionEvent(
        connectionEpoch: Long,
        timestampMs: Long,
        outcome: DiagnosticReadOutcome,
        meaning: String
    ) {
        ensureAttempt(connectionEpoch, timestampMs)
        readRecords += DiagnosticReadRecord(
            timestampMs = timestampMs,
            serviceUuid = "",
            characteristicUuid = "",
            shortId = "EVENT",
            properties = "-",
            status = if (outcome == DiagnosticReadOutcome.CONNECTION_CLOSED) STATUS_CONNECTION_CLOSED else null,
            length = 0,
            hex = "",
            connectionEpoch = connectionEpoch,
            measurementConnectionEpoch = null,
            evidence = CapabilityEvidence.BT638_OBSERVED.label,
            meaning = meaning,
            scanId = currentScanId,
            propertiesRaw = 0,
            callbackReceived = false,
            recordKind = DiagnosticRecordKind.CONNECTION_EVENT,
            outcome = outcome,
            payloadValid = false
        )
    }

    private fun archiveCurrentAttempt(
        connectionEpoch: Long,
        finishedAt: Long,
        baseStatus: String,
        completed: Boolean,
        completionOutcome: DiagnosticReadOutcome
    ) {
        if (readRecords.isEmpty()) return
        val startedAt = scanStartedAt.takeIf { it > 0L } ?: finishedAt
        val scanId = currentScanId.ifBlank { diagnosticReadScanId(startedAt, connectionEpoch) }
        val bundle = DiagnosticReadBundle(
            records = readRecords.map { record ->
                if (record.scanId == scanId) record else record.copy(scanId = scanId)
            },
            deviceName = observedDeviceName.ifBlank {
                manager.state.value.deviceName.ifBlank { BleScooterManager.TARGET_NAME }
            },
            scanStartedAt = startedAt,
            scanFinishedAt = finishedAt,
            connectionEpoch = connectionEpoch,
            scanId = scanId,
            completed = completed,
            completionOutcome = completionOutcome
        )
        manager.retainDiagnosticReadBundle(bundle)
        DiagnosticReadArchive.get(VMAXSyncApplication.appContext).saveAndPublish(bundle) { archiveStatus ->
            if (!_state.value.running) {
                _state.value = _state.value.copy(status = "$baseStatus • $archiveStatus")
            }
        }
        readRecords.clear()
        currentConnectionEpoch = null
        currentScanId = ""
        scanStartedAt = 0L
        armedAt = 0L
        readBeforeNotificationsRequested = false
        archiveNoDeviceAttemptRequested = false
        if (pendingReadConnectionEpoch == connectionEpoch) pendingReadConnectionEpoch = null
    }

    private fun cancelReadTimeout() {
        readTimeout?.let(handler::removeCallbacks)
        readTimeout = null
    }

    private fun cancelPreConnectionAttemptTimeout() {
        preConnectionAttemptTimeout?.let(handler::removeCallbacks)
        preConnectionAttemptTimeout = null
    }

    private fun readKey(characteristic: BluetoothGattCharacteristic): ReadKey = ReadKey(
        serviceUuid = characteristic.service.uuid.toString(),
        characteristicUuid = characteristic.uuid.toString()
    )

    private fun propertyText(properties: Int): String = buildList {
        if (properties and BluetoothGattCharacteristic.PROPERTY_READ != 0) add("READ")
        if (properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) add("WRITE")
        if (properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) add("WRITE_NR")
        if (properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) add("NOTIFY")
        if (properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) add("INDICATE")
        if (properties and BluetoothGattCharacteristic.PROPERTY_BROADCAST != 0) add("BROADCAST")
    }.ifEmpty { listOf("-") }.joinToString(" • ")

    private fun propertyArchiveText(properties: Int): String =
        propertyText(properties).replace(" • ", "|")

    private fun evidenceRank(value: String): Int = when (value) {
        CapabilityEvidence.BT638_CONFIRMED.label -> 0
        CapabilityEvidence.BT638_OBSERVED.label -> 1
        CapabilityEvidence.SDK_CONFIRMED.label -> 2
        CapabilityEvidence.BLUETOOTH_STANDARD.label -> 3
        else -> 4
    }

    private fun hexLength(hex: String): Int =
        if (hex.isBlank()) 0 else hex.split('-').count { it.length == 2 }

    private companion object {
        const val READ_TIMEOUT_MS = 3_000L
        const val PRE_CONNECTION_ATTEMPT_TIMEOUT_MS = 3_000L
        const val PRE_GATT_CONNECTION_EPOCH = -1L
        const val STATUS_READ_START_FAILED = -1001
        const val STATUS_READ_TIMEOUT = -1002
        const val STATUS_CONNECTION_CLOSED = -1003
    }
}

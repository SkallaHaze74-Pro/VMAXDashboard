package de.kevin.vmaxdashboard

import android.annotation.SuppressLint
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Shared four-hex GATT label used by BleScooterManager logs and the READ archive. */
internal fun normalizeGattShortUuid(uuid: String): String =
    uuid.takeIf { it.length >= 8 }?.substring(4, 8)?.uppercase() ?: uuid.uppercase()

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
    private var scanStartedAt = 0L

    fun reset() {
        generation++
        cancelReadTimeout()
        readCoordinator.cancel()
        characteristicsByKey.clear()
        readRecords.clear()
        scanStartedAt = 0L
        val session = activeSession
        activeSession = null
        if (session != null) manager.cancelDiagnosticGattReadScan(session, this)
        _state.value = GattScanState()
    }

    @SuppressLint("MissingPermission")
    fun scanAndRead(): Boolean {
        if (_state.value.running) return false
        val session = manager.beginDiagnosticGattReadScan(this)
        if (session == null) {
            _state.value = GattScanState(status = "GATT-Verbindung noch nicht bereit")
            return false
        }
        activeSession = session
        readRecords.clear()
        scanStartedAt = System.currentTimeMillis()

        val services = manager.diagnosticGattServices(session)
        if (services == null) {
            activeSession = null
            manager.finishDiagnosticGattReadScan(session, this)
            _state.value = GattScanState(status = "GATT-Verbindung wurde während des Scans gewechselt")
            return false
        }
        val entries = services.flatMap { service ->
            val serviceShort = normalizeGattShortUuid(service.uuid.toString())
            service.characteristics.map { characteristic ->
                val characteristicShort = normalizeGattShortUuid(characteristic.uuid.toString())
                val p = characteristic.properties
                val knowledge = VmaxSdkCapabilityCatalog.classify(serviceShort, characteristicShort)
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
            compareBy<GattCharacteristicInfo>({ evidenceRank(it.evidence) }, { readPriority(it.characteristicUuid) }, { it.serviceUuid }, { it.characteristicUuid })
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
                { readPriority(normalizeGattShortUuid(it.uuid.toString())) },
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
            recordResult(characteristic, STATUS_READ_START_FAILED, "")
            readCoordinator.complete(key)
            handler.post { readNext(session, expectedGeneration) }
            return
        }

        val timeout = Runnable {
            if (expectedGeneration == generation && activeSession == session && readCoordinator.timeout(key)) {
                recordResult(characteristic, STATUS_READ_TIMEOUT, "")
                _state.value = _state.value.copy(
                    status = "READ $uuid ohne Callback – fahre nach Timeout fort"
                )
                readNext(session, expectedGeneration)
            }
        }
        readTimeout = timeout
        handler.postDelayed(timeout, READ_TIMEOUT_MS)
    }

    internal fun onDiagnosticCharacteristicRead(
        session: DiagnosticGattReadSession,
        characteristic: BluetoothGattCharacteristic,
        status: Int
    ) {
        handler.post {
            if (activeSession != session) return@post
            val key = readKey(characteristic)
            if (!readCoordinator.complete(key)) return@post
            cancelReadTimeout()
            val uuid = normalizeGattShortUuid(characteristic.uuid.toString())
            val hex = if (status == BluetoothGatt.GATT_SUCCESS) latestManagerReadHex(uuid) else ""
            recordResult(characteristic, status, hex)
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
        handler.post {
            if (activeSession != session) return@post
            generation++
            cancelReadTimeout()
            readCoordinator.cancel()
            characteristicsByKey.clear()
            activeSession = null
            val finishedAt = System.currentTimeMillis()
            archive(finishedAt, "READ-Scan abgebrochen: Verbindung beendet")
            _state.value = _state.value.copy(
                running = false,
                completed = false,
                status = "READ-Scan abgebrochen: Verbindung beendet • bisherige Antworten werden gesichert"
            )
        }
    }

    private fun recordResult(
        characteristic: BluetoothGattCharacteristic,
        status: Int,
        hex: String
    ) {
        val now = System.currentTimeMillis()
        val service = normalizeGattShortUuid(characteristic.service.uuid.toString())
        val uuid = normalizeGattShortUuid(characteristic.uuid.toString())
        val knowledge = VmaxSdkCapabilityCatalog.classify(service, uuid)
        val length = hexLength(hex)
        readRecords += DiagnosticReadRecord(
            timestampMs = now,
            serviceUuid = service,
            characteristicUuid = uuid,
            shortId = uuid,
            status = status,
            length = length,
            hex = hex,
            evidence = knowledge.evidence.label,
            meaning = knowledge.meaning
        )
        _state.value = _state.value.copy(
            successfulReads = readRecords.count { it.status == BluetoothGatt.GATT_SUCCESS },
            payloadReads = readRecords.count { it.hex.isNotBlank() },
            entries = _state.value.entries.map {
                if (it.serviceUuid == service && it.characteristicUuid == uuid) {
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
        manager.finishDiagnosticGattReadScan(session, this)
        archive(finishedAt, baseStatus)
    }

    private fun archive(finishedAt: Long, baseStatus: String) {
        val records = readRecords.toList()
        val startedAt = scanStartedAt.takeIf { it > 0L } ?: finishedAt
        DiagnosticReadArchive.get(VMAXSyncApplication.appContext).saveAndPublish(
            records = records,
            deviceName = manager.state.value.deviceName.ifBlank { BleScooterManager.TARGET_NAME },
            scanStartedAt = startedAt,
            scanFinishedAt = finishedAt
        ) { archiveStatus ->
            if (!_state.value.running) {
                _state.value = _state.value.copy(status = "$baseStatus • $archiveStatus")
            }
        }
    }

    /**
     * BleScooterManager logs the exact byte[] supplied by Android before notifying
     * this scanner. The shared short-UUID normalizer deliberately mirrors the
     * manager's four-hex log label for DA1A, Bluetooth-standard and other UUIDs.
     */
    private fun latestManagerReadHex(uuid: String): String {
        val prefix = "READ $uuid (nur Diagnose, nicht als Live-Telemetrie):"
        return manager.state.value.log
            .firstOrNull { it.startsWith(prefix) }
            ?.substringAfter(prefix)
            ?.trim()
            .orEmpty()
    }

    private fun cancelReadTimeout() {
        readTimeout?.let(handler::removeCallbacks)
        readTimeout = null
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

    private fun evidenceRank(value: String): Int = when (value) {
        CapabilityEvidence.BT638_CONFIRMED.label -> 0
        CapabilityEvidence.BT638_OBSERVED.label -> 1
        CapabilityEvidence.SDK_CONFIRMED.label -> 2
        CapabilityEvidence.BLUETOOTH_STANDARD.label -> 3
        else -> 4
    }

    private fun readPriority(uuid: String): Int = when (uuid.uppercase()) {
        // Native GPSTProtocolHandler diagnostic/identity/battery READ targets.
        "1514" -> 0 // Error
        "1516" -> 1 // SerialNumbers
        "1517" -> 2 // ErrorString
        "1518" -> 3 // DebugLog
        "150C" -> 4 // BatteryCellUpdate candidate
        "1502" -> 5 // Battery/static candidate observed live
        "1509" -> 6 // Battery live layout
        "150A" -> 7 // Motor live layout
        "160C" -> 8 // Motor tuning READ/readback
        "1E03" -> 9 // WirelessRemote
        "1E04" -> 10 // WirelessRemoteAction
        else -> 100
    }

    private fun hexLength(hex: String): Int =
        if (hex.isBlank()) 0 else hex.split('-').count { it.length == 2 }

    private companion object {
        const val READ_TIMEOUT_MS = 3_000L
        const val STATUS_READ_START_FAILED = -1001
        const val STATUS_READ_TIMEOUT = -1002
    }
}

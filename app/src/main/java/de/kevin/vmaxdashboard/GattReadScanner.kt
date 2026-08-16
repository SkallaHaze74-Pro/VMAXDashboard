package de.kevin.vmaxdashboard

import android.annotation.SuppressLint
import android.bluetooth.BluetoothGattCharacteristic
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Read-only GATT inventory and safe READ scanner.
 *
 * The existing BleScooterManager owns the BluetoothGatt connection. To avoid a
 * second connection, this helper inspects that same connection and sequentially
 * reads every characteristic that advertises PROPERTY_READ. It never writes.
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
    private val _state = MutableStateFlow(GattScanState())
    val state: StateFlow<GattScanState> = _state

    private var generation = 0
    private var activeSession: DiagnosticGattReadSession? = null
    private var readTimeout: Runnable? = null

    fun reset() {
        generation++
        cancelReadTimeout()
        readCoordinator.cancel()
        characteristicsByKey.clear()
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

        val services = manager.diagnosticGattServices(session)
        if (services == null) {
            activeSession = null
            manager.finishDiagnosticGattReadScan(session, this)
            _state.value = GattScanState(status = "GATT-Verbindung wurde während des Scans gewechselt")
            return false
        }
        val entries = services.flatMap { service ->
            val serviceShort = shortUuid(service.uuid.toString())
            service.characteristics.map { characteristic ->
                val characteristicShort = shortUuid(characteristic.uuid.toString())
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
            compareBy<GattCharacteristicInfo>({ evidenceRank(it.evidence) }, { it.serviceUuid }, { it.characteristicUuid })
        )

        val readableCharacteristics = buildList {
            services.forEach { service ->
                service.characteristics
                    .filter { it.properties and BluetoothGattCharacteristic.PROPERTY_READ != 0 }
                    .forEach(::add)
            }
        }
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
            status = "${services.size} Dienste, ${entries.size} Characteristics – $confirmed bestätigt, $observed beobachtet, $sdkKnown SDK-bekannt, $unknown unbekannt"
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
        val uuid = shortUuid(characteristic.uuid.toString())
        _state.value = _state.value.copy(
            startedReads = _state.value.startedReads + if (readStarted) 1 else 0,
            status = "Lese $uuid • ${readCoordinator.remaining} übrig",
            entries = _state.value.entries.map {
                if (it.characteristicUuid == uuid) it.copy(lastReadStarted = readStarted) else it
            }
        )

        if (!readStarted) {
            readCoordinator.complete(key)
            handler.post { readNext(session, expectedGeneration) }
            return
        }

        val timeout = Runnable {
            if (expectedGeneration == generation && activeSession == session && readCoordinator.timeout(key)) {
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
            val uuid = shortUuid(characteristic.uuid.toString())
            _state.value = _state.value.copy(
                status = if (status == android.bluetooth.BluetoothGatt.GATT_SUCCESS) {
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
            _state.value = _state.value.copy(
                running = false,
                completed = false,
                status = "READ-Scan abgebrochen: Verbindung beendet"
            )
        }
    }

    private fun finishScan(session: DiagnosticGattReadSession, expectedGeneration: Int) {
        if (expectedGeneration != generation || activeSession != session || readCoordinator.running) return
        cancelReadTimeout()
        activeSession = null
        characteristicsByKey.clear()
        _state.value = _state.value.copy(
            running = false,
            completed = true,
            status = "READ-Scan fertig: ${_state.value.startedReads}/${_state.value.readableCount} gestartet • ${_state.value.confirmedCount} BT638 bestätigt • ${_state.value.sdkKnownCount} nur SDK"
        )
        manager.finishDiagnosticGattReadScan(session, this)
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

    private fun shortUuid(uuid: String): String =
        if (uuid.startsWith("da1a") && uuid.length >= 8) uuid.substring(4, 8).uppercase()
        else uuid.uppercase()

    private companion object {
        const val READ_TIMEOUT_MS = 3_000L
    }
}

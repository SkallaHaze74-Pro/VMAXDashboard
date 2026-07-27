package de.kevin.vmaxdashboard

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.ArrayDeque
import java.util.UUID

class BleScooterManager(private val context: Context) {
    companion object {
        val SERVICE_TELEMETRY: UUID =
            UUID.fromString("da1a1500-d532-4285-be94-b07a3e11a098")
        val BATTERY_CHARACTERISTIC: UUID =
            UUID.fromString("da1a1509-d532-4285-be94-b07a3e11a098")
        val CCCD: UUID =
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        const val TARGET_NAME = "BT638"
    }

    private val bluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter: BluetoothAdapter? get() = bluetoothManager.adapter
    private var gatt: BluetoothGatt? = null
    private val notificationQueue = ArrayDeque<BluetoothGattCharacteristic>()
    private var descriptorWriteRunning = false

    private val decoderLab = DecoderLabEngine()
    private val secureStore = SecureTelemetryStore(context)

    private val _state = MutableStateFlow(ScooterState(encryptedReports = secureStore.count()))
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
        update { it.copy(scanning = true, status = "Suche nach $TARGET_NAME …") }
        adapter?.bluetoothLeScanner?.startScan(scanCallback)
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        if (!hasRequiredPermissions()) return
        adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        update { it.copy(scanning = false) }
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        decoderLab.cancel()
        notificationQueue.clear()
        descriptorWriteRunning = false
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        update { it.copy(connected = false, scanning = false, status = "Getrennt", labRunning = false) }
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
    private fun connect(device: BluetoothDevice) {
        stopScan()
        update {
            it.copy(
                status = "Verbinde …",
                deviceName = device.name ?: TARGET_NAME,
                address = device.address
            )
        }
        gatt?.close()
        gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val name = result.device.name ?: result.scanRecord?.deviceName
            if (name == TARGET_NAME) {
                addLog("$TARGET_NAME gefunden")
                update { it.copy(rssi = result.rssi) }
                connect(result.device)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            update { it.copy(scanning = false, status = "Scanfehler: $errorCode") }
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    update { it.copy(connected = true, status = "Verbunden – suche Dienste") }
                    addLog("BLE verbunden, Status $status")
                    g.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    descriptorWriteRunning = false
                    notificationQueue.clear()
                    update {
                        it.copy(
                            connected = false,
                            scanning = false,
                            status = "Verbindung getrennt (Status $status)",
                            labRunning = false
                        )
                    }
                    addLog("BLE getrennt, Status $status")
                    g.close()
                    if (gatt === g) gatt = null
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                update { it.copy(status = "Dienste konnten nicht gelesen werden: $status") }
                return
            }
            val service = g.getService(SERVICE_TELEMETRY)
            if (service == null) {
                update { it.copy(status = "Telemetrie-Dienst nicht gefunden") }
                return
            }

            val notifyChars = service.characteristics.filter {
                val p = it.properties
                p and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0 ||
                    p and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0
            }

            notificationQueue.clear()
            notificationQueue.addAll(notifyChars.sortedByDescending { it.uuid == BATTERY_CHARACTERISTIC })
            addLog("${notifyChars.size} Datenkanäle gefunden")
            update { it.copy(status = "Verbunden – aktiviere Live-Daten") }
            enableNextNotification(g)
        }

        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            handleValue(characteristic.uuid, characteristic.value ?: byteArrayOf())
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            handleValue(characteristic.uuid, value)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            descriptorWriteRunning = false
            if (status != BluetoothGatt.GATT_SUCCESS) addLog("Notify-Aktivierung fehlgeschlagen: $status")
            enableNextNotification(g)
        }
    }

    @SuppressLint("MissingPermission")
    private fun enableNextNotification(g: BluetoothGatt) {
        if (descriptorWriteRunning) return
        val characteristic = notificationQueue.pollFirst()
        if (characteristic == null) {
            update { it.copy(status = "Live-Daten aktiv") }
            addLog("Alle verfügbaren Benachrichtigungen aktiviert")
            return
        }

        if (!g.setCharacteristicNotification(characteristic, true)) {
            addLog("Kanal ${shortUuid(characteristic.uuid)} konnte nicht aktiviert werden")
            enableNextNotification(g)
            return
        }

        val descriptor = characteristic.getDescriptor(CCCD)
        if (descriptor == null) {
            enableNextNotification(g)
            return
        }

        val value =
            if (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0)
                BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            else BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE

        descriptorWriteRunning = true
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
            enableNextNotification(g)
        }
    }

    private fun handleValue(uuid: UUID, value: ByteArray) {
        decoderLab.record(uuid, value)
        val hex = value.joinToString("-") { "%02X".format(it.toInt() and 0xFF) }
        val short = shortUuid(uuid)

        var battery = _state.value.batteryPercent
        if (uuid == BATTERY_CHARACTERISTIC && value.size >= 5) {
            val candidate = value[4].toInt() and 0xFF
            if (candidate in 0..100) battery = candidate
        }

        val packets = (_state.value.rawPackets + (short to hex)).toSortedMap()
        update {
            it.copy(
                batteryPercent = battery,
                lastCharacteristic = short,
                lastRawHex = hex,
                rawPackets = packets,
                status = "Live-Daten aktiv"
            )
        }
        addLog("$short: $hex")
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
        uuid.toString().substring(4, 8).uppercase()

    private fun addLog(message: String) {
        update { it.copy(log = (listOf(message) + it.log).take(60)) }
    }

    private inline fun update(block: (ScooterState) -> ScooterState) {
        _state.value = block(_state.value)
    }
}

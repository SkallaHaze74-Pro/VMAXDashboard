package de.kevin.vmaxdashboard

import android.annotation.SuppressLint
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.ArrayDeque

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
    val lastReadStarted: Boolean = false
)

data class GattScanState(
    val running: Boolean = false,
    val completed: Boolean = false,
    val serviceCount: Int = 0,
    val characteristicCount: Int = 0,
    val readableCount: Int = 0,
    val startedReads: Int = 0,
    val entries: List<GattCharacteristicInfo> = emptyList(),
    val status: String = "Warte auf Verbindung"
)

class GattReadScanner(private val manager: BleScooterManager) {
    private val handler = Handler(Looper.getMainLooper())
    private val queue = ArrayDeque<BluetoothGattCharacteristic>()
    private val _state = MutableStateFlow(GattScanState())
    val state: StateFlow<GattScanState> = _state

    private var generation = 0

    fun reset() {
        generation++
        handler.removeCallbacksAndMessages(null)
        queue.clear()
        _state.value = GattScanState()
    }

    @SuppressLint("MissingPermission")
    fun scanAndRead() {
        val gatt = resolveGatt()
        if (gatt == null) {
            _state.value = GattScanState(status = "GATT-Verbindung noch nicht bereit")
            return
        }

        val services = gatt.services.orEmpty()
        val entries = services.flatMap { service ->
            service.characteristics.map { characteristic ->
                val p = characteristic.properties
                GattCharacteristicInfo(
                    serviceUuid = shortUuid(service.uuid.toString()),
                    characteristicUuid = shortUuid(characteristic.uuid.toString()),
                    properties = propertyText(p),
                    readable = p and BluetoothGattCharacteristic.PROPERTY_READ != 0,
                    notifiable = p and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0,
                    indicatable = p and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0,
                    writable = p and BluetoothGattCharacteristic.PROPERTY_WRITE != 0 ||
                        p and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0
                )
            }
        }.sortedWith(compareBy({ it.serviceUuid }, { it.characteristicUuid }))

        queue.clear()
        services.forEach { service ->
            service.characteristics
                .filter { it.properties and BluetoothGattCharacteristic.PROPERTY_READ != 0 }
                .forEach(queue::addLast)
        }

        val currentGeneration = ++generation
        _state.value = GattScanState(
            running = true,
            serviceCount = services.size,
            characteristicCount = entries.size,
            readableCount = queue.size,
            entries = entries,
            status = "${services.size} Dienste, ${entries.size} Characteristics – READ-Scan läuft"
        )
        readNext(gatt, currentGeneration, 0)
    }

    @SuppressLint("MissingPermission")
    private fun readNext(gatt: BluetoothGatt, expectedGeneration: Int, started: Int) {
        if (expectedGeneration != generation) return
        val characteristic = queue.pollFirst()
        if (characteristic == null) {
            _state.value = _state.value.copy(
                running = false,
                completed = true,
                startedReads = started,
                status = "READ-Scan fertig: $started/${_state.value.readableCount} Lesevorgänge gestartet"
            )
            return
        }

        val readStarted = runCatching { gatt.readCharacteristic(characteristic) }.getOrDefault(false)
        val uuid = shortUuid(characteristic.uuid.toString())
        _state.value = _state.value.copy(
            startedReads = started + if (readStarted) 1 else 0,
            status = "Lese $uuid • ${queue.size} übrig",
            entries = _state.value.entries.map {
                if (it.characteristicUuid == uuid) it.copy(lastReadStarted = readStarted) else it
            }
        )

        // Android allows only one GATT operation at a time. The manager receives
        // read callbacks; a conservative delay keeps this scanner read-only and stable.
        handler.postDelayed(
            { readNext(gatt, expectedGeneration, started + if (readStarted) 1 else 0) },
            650L
        )
    }

    private fun resolveGatt(): BluetoothGatt? = runCatching {
        val field = BleScooterManager::class.java.getDeclaredField("gatt")
        field.isAccessible = true
        field.get(manager) as? BluetoothGatt
    }.getOrNull()

    private fun propertyText(properties: Int): String = buildList {
        if (properties and BluetoothGattCharacteristic.PROPERTY_READ != 0) add("READ")
        if (properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) add("WRITE")
        if (properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) add("WRITE_NR")
        if (properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) add("NOTIFY")
        if (properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) add("INDICATE")
        if (properties and BluetoothGattCharacteristic.PROPERTY_BROADCAST != 0) add("BROADCAST")
    }.ifEmpty { listOf("–") }.joinToString(" • ")

    private fun shortUuid(uuid: String): String =
        if (uuid.startsWith("da1a") && uuid.length >= 8) uuid.substring(4, 8).uppercase()
        else uuid.uppercase()
}

package de.kevin.vmaxdashboard

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID
import java.util.ArrayDeque

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
    private val prefs = context.getSharedPreferences("stvx_smart_connect", Context.MODE_PRIVATE)
    private val handler = Handler(Looper.getMainLooper())
    private val telemetryReporter = TelemetryReporter(context)
    private var manualDisconnect = false
    private var reconnectAttempts = 0
    private var connectingAddress: String? = null
    private val reconnectRunnable = Runnable { reconnectRememberedDevice() }

    private val notificationQueue = ArrayDeque<BluetoothGattCharacteristic>()
    private var descriptorWriteRunning = false
    private val previousPackets = mutableMapOf<String, ByteArray>()
    private val packetCounts = mutableMapOf<String, Int>()
    private val firstPacketTimes = mutableMapOf<String, Long>()
    private val characteristicServices = mutableMapOf<UUID, String>()
    private val characteristicProperties = mutableMapOf<UUID, String>()
    private val byteMinimums = mutableMapOf<String, MutableList<Int>>()
    private val byteMaximums = mutableMapOf<String, MutableList<Int>>()
    private val byteChangeCounts = mutableMapOf<String, MutableList<Int>>()
    private val baselinePackets = mutableMapOf<String, ByteArray>()
    private var rideStartedAtMs: Long? = null
    private var speedSampleSum = 0.0
    private var speedSampleCount = 0L

    private val _state = MutableStateFlow(
        ScooterState(
            rememberedDeviceName = prefs.getString("device_name", "").orEmpty(),
            rememberedDeviceAddress = prefs.getString("device_address", "").orEmpty(),
            autoConnectEnabled = prefs.getBoolean("auto_connect", true),
            telemetryUploadEnabled = telemetryReporter.uploadEnabled,
            testerId = telemetryReporter.testerId
        )
    )
    val state: StateFlow<ScooterState> = _state


    fun setTelemetryUploadEnabled(enabled: Boolean) {
        telemetryReporter.uploadEnabled = enabled
        update { it.copy(telemetryUploadEnabled = enabled) }
        addLog(
            if (enabled) "Testdaten-Upload aktiviert"
            else "Testdaten-Upload deaktiviert"
        )
    }

    fun sendTesterReport(title: String, details: String) {
        telemetryReporter.reportProblem(title, details, _state.value)
        addLog("Testerbericht gesendet: $title")
    }

    fun setAutoConnectEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("auto_connect", enabled).apply()
        update { it.copy(autoConnectEnabled = enabled) }
        if (enabled) smartConnect() else handler.removeCallbacks(reconnectRunnable)
    }

    fun forgetRememberedScooter() {
        handler.removeCallbacks(reconnectRunnable)
        prefs.edit().remove("device_name").remove("device_address").apply()
        reconnectAttempts = 0
        update {
            it.copy(
                rememberedDeviceName = "",
                rememberedDeviceAddress = "",
                reconnectAttempt = 0,
                status = if (it.connected) it.status else "Gespeicherter Scooter entfernt"
            )
        }
        addLog("Gespeicherten Scooter vergessen")
    }

    @SuppressLint("MissingPermission")
    fun smartConnect() {
        if (!hasRequiredPermissions()) return
        if (!_state.value.autoConnectEnabled || _state.value.connected || gatt != null) return
        val address = _state.value.rememberedDeviceAddress
        if (address.isBlank()) {
            startUniversalScan()
            return
        }
        manualDisconnect = false
        handler.removeCallbacks(reconnectRunnable)
        update { it.copy(status = "Suche gespeicherten Scooter …") }
        connectTo(address)
    }

    private fun rememberDevice(device: BluetoothDevice) {
        val name = runCatching { device.name }.getOrNull() ?: _state.value.deviceName
        prefs.edit().putString("device_name", name).putString("device_address", device.address).apply()
        update {
            it.copy(
                rememberedDeviceName = name,
                rememberedDeviceAddress = device.address,
                reconnectAttempt = 0
            )
        }
    }

    @SuppressLint("MissingPermission")
    private fun reconnectRememberedDevice() {
        if (manualDisconnect || !_state.value.autoConnectEnabled || _state.value.connected || gatt != null) return
        val address = _state.value.rememberedDeviceAddress
        if (address.isBlank() || adapter?.isEnabled != true || !hasRequiredPermissions()) return
        reconnectAttempts += 1
        update { it.copy(status = "Automatische Wiederverbindung #$reconnectAttempts …", reconnectAttempt = reconnectAttempts) }
        connectTo(address)
    }

    private fun scheduleReconnect() {
        if (manualDisconnect || !_state.value.autoConnectEnabled || _state.value.rememberedDeviceAddress.isBlank()) return
        handler.removeCallbacks(reconnectRunnable)
        val delay = (2_000L * (reconnectAttempts + 1)).coerceAtMost(15_000L)
        update { it.copy(status = "Verbindung verloren – neuer Versuch in ${delay / 1000}s") }
        handler.postDelayed(reconnectRunnable, delay)
    }

    fun setAnalysisPhase(label: String) {
        baselinePackets.clear()
        previousPackets.forEach { (channel, packet) -> baselinePackets[channel] = packet.copyOf() }
        update {
            it.copy(
                analysisPhase = label,
                analysisPhaseNumber = it.analysisPhaseNumber + 1,
                leftIndicator = label.contains("Blinker links", ignoreCase = true),
                rightIndicator = label.contains("Blinker rechts", ignoreCase = true),
                lightOn = label.contains("Licht EIN", ignoreCase = true),
                brakeActive = label.contains("Brems", ignoreCase = true)
            )
        }
        addLog("TESTPHASE: $label – Ausgangswerte gespeichert")
    }

    fun resetAnalyzer() {
        previousPackets.clear()
        packetCounts.clear()
        firstPacketTimes.clear()
        byteMinimums.clear()
        byteMaximums.clear()
        byteChangeCounts.clear()
        baselinePackets.clear()
        update {
            it.copy(
                packetTotal = 0,
                maxSpeedKmh = 0.0,
                averageSpeedKmh = 0.0,
                rideSeconds = 0,
                speedHistory = emptyList(),
                decoderCandidates = emptyList(),
                channels = emptyList(),
                log = emptyList(),
                lastCharacteristic = "",
                lastRawHex = "",
                analysisPhase = "Leerlauf",
                analysisPhaseNumber = 0,
                leftIndicator = false,
                rightIndicator = false,
                lightOn = false,
                brakeActive = false
            )
        }
        rideStartedAtMs = null
        speedSampleSum = 0.0
        speedSampleCount = 0
        addLog("BLE-Analyse zurückgesetzt")
    }

    fun hasRequiredPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) ==
                PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        }
    }

    @SuppressLint("MissingPermission")
    fun startScan() {
        startScanInternal(universal = false)
    }

    @SuppressLint("MissingPermission")
    fun startUniversalScan() {
        startScanInternal(universal = true)
    }

    @SuppressLint("MissingPermission")
    private fun startScanInternal(universal: Boolean) {
        if (!hasRequiredPermissions()) {
            addLog("Bluetooth-Berechtigungen fehlen")
            return
        }
        if (adapter?.isEnabled != true) {
            update { it.copy(status = "Bluetooth ist ausgeschaltet") }
            return
        }

        update {
            it.copy(
                scanning = true,
                universalScan = universal,
                discoveredScooters = if (universal) emptyList() else it.discoveredScooters,
                status = if (universal) "Suche kompatible BLE-Geräte …" else "Suche nach $TARGET_NAME …"
            )
        }
        adapter?.bluetoothLeScanner?.startScan(scanCallback)
    }

    @SuppressLint("MissingPermission")
    fun connectTo(address: String) {
        manualDisconnect = false
        if (!hasRequiredPermissions()) {
            addLog("Bluetooth-Berechtigungen fehlen")
            return
        }
        runCatching { adapter?.getRemoteDevice(address) }
            .onSuccess { device -> if (device != null) connect(device) }
            .onFailure { addLog("Gerät konnte nicht geöffnet werden: ${it.message}") }
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        if (!hasRequiredPermissions()) return
        adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        update { it.copy(scanning = false, universalScan = false) }
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        manualDisconnect = true
        handler.removeCallbacks(reconnectRunnable)
        reconnectAttempts = 0
        notificationQueue.clear()
        descriptorWriteRunning = false
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        connectingAddress = null
        update { it.copy(connected = false, scanning = false, reconnectAttempt = 0, status = "Getrennt") }
    }

    @SuppressLint("MissingPermission")
    private fun connect(device: BluetoothDevice) {
        stopScan()
        handler.removeCallbacks(reconnectRunnable)
        connectingAddress = device.address
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
            val name = result.device.name ?: result.scanRecord?.deviceName ?: "Unbekanntes BLE-Gerät"
            val current = _state.value
            if (current.universalScan) {
                val normalized = name.uppercase()
                val likelyScooter = listOf("VMAX", "VX", "SCOOT", "BT63", "BT64", "E-SCOOTER")
                    .any { normalized.contains(it) }
                val candidate = DiscoveredScooter(
                    name = name,
                    address = result.device.address,
                    rssi = result.rssi,
                    likelyScooter = likelyScooter
                )
                val list = (current.discoveredScooters.filterNot { it.address == candidate.address } + candidate)
                    .sortedWith(compareByDescending<DiscoveredScooter> { it.likelyScooter }.thenByDescending { it.rssi })
                    .take(40)
                val likelyCount = list.count { it.likelyScooter }
                update {
                    it.copy(
                        discoveredScooters = list,
                        status = if (likelyCount > 0)
                            "$likelyCount mögliche Scooter · ${list.size} BLE-Geräte"
                        else "${list.size} BLE-Geräte gefunden"
                    )
                }
            } else if (name == TARGET_NAME) {
                addLog("$TARGET_NAME gefunden: ${result.device.address}")
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
                    reconnectAttempts = 0
                    manualDisconnect = false
                    connectingAddress = g.device.address
                    rememberDevice(g.device)
                    update { it.copy(connected = true, reconnectAttempt = 0, status = "Verbunden – suche Dienste") }
                    addLog("BLE verbunden, Status $status")
                    discoverServicesCompat(g)
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    descriptorWriteRunning = false
                    notificationQueue.clear()
                    update {
                        it.copy(
                            connected = false,
                            scanning = false,
                            status = "Verbindung getrennt (Status $status)"
                        )
                    }
                    addLog("BLE getrennt, Status $status")
                    g.close()
                    if (gatt === g) gatt = null
                    connectingAddress = null
                    scheduleReconnect()
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                update { it.copy(status = "Dienste konnten nicht gelesen werden: $status") }
                return
            }

            val notifyChars = g.services.flatMap { service ->
                service.characteristics.filter { characteristic ->
                    val p = characteristic.properties
                    val supportsLiveData =
                        p and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0 ||
                            p and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0
                    if (supportsLiveData) {
                        characteristicServices[characteristic.uuid] = shortUuid(service.uuid)
                        characteristicProperties[characteristic.uuid] = buildString {
                            if (p and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) append("Notify")
                            if (p and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) {
                                if (isNotEmpty()) append(" + ")
                                append("Indicate")
                            }
                        }
                    }
                    supportsLiveData
                }
            }.distinctBy { it.uuid }

            if (notifyChars.isEmpty()) {
                update { it.copy(status = "Keine Live-Datenkanäle gefunden") }
                return
            }

            val discoveredChannels = notifyChars.map { characteristic ->
                BleChannelState(
                    channel = shortUuid(characteristic.uuid),
                    service = characteristicServices[characteristic.uuid].orEmpty(),
                    properties = characteristicProperties[characteristic.uuid] ?: "Notify"
                )
            }.sortedWith(compareBy<BleChannelState> { it.service }.thenBy { it.channel })

            notificationQueue.clear()
            notificationQueue.addAll(
                notifyChars.sortedByDescending { it.uuid == BATTERY_CHARACTERISTIC }
            )
            addLog("${notifyChars.size} Live-Kanäle in ${g.services.size} Diensten gefunden")
            update { it.copy(status = "Verbunden – aktiviere alle Live-Daten", channels = discoveredChannels) }
            enableNextNotification(g)
        }

        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            handleValue(characteristic.uuid, characteristic.value ?: byteArrayOf())
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            handleValue(characteristic.uuid, value)
        }

        override fun onDescriptorWrite(
            g: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            descriptorWriteRunning = false
            if (status != BluetoothGatt.GATT_SUCCESS) {
                addLog("Notify-Aktivierung fehlgeschlagen: $status")
            }
            enableNextNotification(g)
        }
    }

    @SuppressLint("MissingPermission")
    private fun discoverServicesCompat(g: BluetoothGatt) {
        g.discoverServices()
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

        val enabled = g.setCharacteristicNotification(characteristic, true)
        if (!enabled) {
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
            else
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE

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
        val hex = value.joinToString("-") { "%02X".format(it.toInt() and 0xFF) }
        val short = shortUuid(uuid)

        var battery = _state.value.batteryPercent
        var speed = _state.value.speedKmh

        if (uuid == BATTERY_CHARACTERISTIC && value.size >= 5) {
            val candidate = value[4].toInt() and 0xFF
            if (candidate in 0..100) battery = candidate
        }

        // Vorläufig bestätigt: Kanal 1505, Byte 8, Faktor 0,1 km/h.
        if (short == "1505" && value.size >= 8) {
            val rawSpeed = value[7].toInt() and 0xFF
            speed = if (rawSpeed < 3) 0.0 else rawSpeed / 10.0
        }

        val previous = previousPackets[short]
        val changes = value.indices
            .filter { index -> previous == null || index >= previous.size || previous[index] != value[index] }
            .joinToString(", ") { index ->
                val old = previous?.getOrNull(index)?.toInt()?.and(0xFF)?.let { "%02X".format(it) } ?: "--"
                val now = "%02X".format(value[index].toInt() and 0xFF)
                "B${index + 1}:$old→$now"
            }
            .ifBlank { "keine" }
        val oldPacket = previousPackets[short]
        val minimums = byteMinimums.getOrPut(short) { MutableList(value.size) { 255 } }
        val maximums = byteMaximums.getOrPut(short) { MutableList(value.size) { 0 } }
        val changeCounts = byteChangeCounts.getOrPut(short) { MutableList(value.size) { 0 } }
        while (minimums.size < value.size) minimums.add(255)
        while (maximums.size < value.size) maximums.add(0)
        while (changeCounts.size < value.size) changeCounts.add(0)

        value.indices.forEach { index ->
            val current = value[index].toInt() and 0xFF
            minimums[index] = minOf(minimums[index], current)
            maximums[index] = maxOf(maximums[index], current)
            if (oldPacket != null && index < oldPacket.size && oldPacket[index] != value[index]) {
                changeCounts[index] += 1
            }
        }

        previousPackets[short] = value.copyOf()
        val nowMs = System.currentTimeMillis()
        packetCounts[short] = (packetCounts[short] ?: 0) + 1
        firstPacketTimes.putIfAbsent(short, nowMs)
        val elapsedSeconds = ((nowMs - (firstPacketTimes[short] ?: nowMs)).coerceAtLeast(1L)) / 1000.0
        val packetRate = (packetCounts[short] ?: 1) / elapsedSeconds.coerceAtLeast(1.0)
        val baseline = baselinePackets[short]
        val stats = value.indices.map { index ->
            val current = value[index].toInt() and 0xFF
            BleByteStat(
                index = index + 1,
                current = current,
                min = minimums[index],
                max = maximums[index],
                changeCount = changeCounts[index],
                deltaFromBaseline = baseline?.getOrNull(index)?.let { current - (it.toInt() and 0xFF) }
            )
        }

        val existing = _state.value.channels.firstOrNull { it.channel == short }
        val channel = BleChannelState(
            channel = short,
            service = characteristicServices[uuid] ?: existing?.service.orEmpty(),
            properties = characteristicProperties[uuid] ?: existing?.properties ?: "Notify",
            hex = hex,
            packetCount = packetCounts[short] ?: 1,
            packetLength = value.size,
            packetsPerSecond = packetRate,
            lastSeenMs = nowMs,
            changedBytes = changes,
            byteStats = stats
        )
        val channels = (_state.value.channels.filterNot { it.channel == short } + channel)
            .sortedWith(compareBy<BleChannelState> { it.service }.thenBy { it.channel })

        val speedValue = speed ?: 0.0
        if (speedValue >= 0.3) {
            if (rideStartedAtMs == null) rideStartedAtMs = System.currentTimeMillis()
            speedSampleSum += speedValue
            speedSampleCount += 1
        }
        val rideSeconds = rideStartedAtMs?.let { (System.currentTimeMillis() - it) / 1000 } ?: 0
        val averageSpeed = if (speedSampleCount > 0) speedSampleSum / speedSampleCount else 0.0
        val history = (_state.value.speedHistory + speedValue).takeLast(60)
        val decoderCandidates = buildDecoderCandidates(channels)

        update {
            it.copy(
                batteryPercent = battery,
                speedKmh = speed,
                maxSpeedKmh = maxOf(it.maxSpeedKmh, speedValue),
                averageSpeedKmh = averageSpeed,
                rideSeconds = rideSeconds,
                speedHistory = history,
                decoderCandidates = decoderCandidates,
                packetTotal = it.packetTotal + 1,
                channels = channels,
                lastCharacteristic = short,
                lastRawHex = hex,
                status = "Live-Daten aktiv"
            )
        }
        telemetryReporter.recordPacket(
            channel = short,
            service = channel.service,
            hex = hex,
            packetLength = value.size,
            packetCount = channel.packetCount,
            packetsPerSecond = channel.packetsPerSecond,
            analysisPhase = _state.value.analysisPhase,
            deviceName = _state.value.deviceName,
            deviceAddress = _state.value.address
        )
        addLog("$short: $hex")
    }


    private fun buildDecoderCandidates(channels: List<BleChannelState>): List<DecoderCandidate> =
        channels.flatMap { channel ->
            channel.byteStats.mapNotNull { stat ->
                if (stat.changeCount < 2 && stat.range < 2) return@mapNotNull null
                val score = (stat.changeCount * 3 + stat.range).coerceAtMost(100)
                val hint = when {
                    channel.channel == "1505" && stat.index == 8 -> "Geschwindigkeit × 0,1 km/h (bestätigt)"
                    stat.range in 1..100 && stat.current in 0..100 -> "Prozentwert / Status"
                    stat.range >= 20 && stat.changeCount >= 5 -> "Dynamischer Fahrwert"
                    stat.range in 2..15 -> "Temperatur, Modus oder Sensor"
                    else -> "Unbekannter Telemetriewert"
                }
                DecoderCandidate(channel.channel, stat.index, score, hint, stat.current, stat.range, stat.changeCount)
            }
        }.sortedByDescending { it.score }.take(12)

    private fun shortUuid(uuid: UUID): String =
        uuid.toString().substring(4, 8).uppercase()

    private fun addLog(message: String) {
        update {
            it.copy(log = (listOf(message) + it.log).take(40))
        }
    }

    private inline fun update(block: (ScooterState) -> ScooterState) {
        _state.value = block(_state.value)
    }
}

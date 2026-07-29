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
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.util.ArrayDeque
import java.util.UUID
import kotlin.math.abs

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
        val CCCD: UUID =
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        const val TARGET_NAME = "BT638"
    }

    private data class PendingMotorTuningWrite(
        val profileIndex: Int,
        val expectedValues: Map<MotorTuningParameter, Int>,
        val packetHex: String,
        val reset: Boolean,
        val startedAt: Long
    )

    private val bluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter: BluetoothAdapter? get() = bluetoothManager.adapter
    private var gatt: BluetoothGatt? = null
    private val notificationQueue = ArrayDeque<BluetoothGattCharacteristic>()
    private var descriptorWriteRunning = false

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

    private val mainHandler = Handler(Looper.getMainLooper())
    private val motorTuningBuffer = mutableListOf<Byte>()
    private var pendingMotorTuningWrite: PendingMotorTuningWrite? = null
    private var lastMotorTuningReadRequestAt = 0L

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
        if (recordingActive) stopMeasurementAndExport()
        mainHandler.removeCallbacksAndMessages(null)
        pendingMotorTuningWrite = null
        lastMotorTuningReadRequestAt = 0L
        motorTuningBuffer.clear()
        previousValues.clear()
        channelPacketCounts.clear()
        notificationQueue.clear()
        descriptorWriteRunning = false
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        update {
            it.copy(
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
                motorTuningLastVerified = null
            )
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
    private fun connect(device: BluetoothDevice) {
        stopScan()
        update {
            it.copy(
                status = "Verbinde …",
                deviceName = device.name ?: TARGET_NAME,
                address = device.address,
                motorTuningProfiles = emptyList(),
                motorTuningOriginalProfiles = emptyList(),
                motorTuningStatus = "Prüfe Motor-Tuning-Dienst",
                motorTuningLastVerified = null
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
                    sessionStartedAt = System.currentTimeMillis()
                    sessionRows.clear()
                    previousValues.clear()
                    channelPacketCounts.clear()
                    packetTimes.clear()
                    motorTuningBuffer.clear()
                    pendingMotorTuningWrite = null
                    update {
                        it.copy(
                            connected = true,
                            status = "Verbunden – suche Dienste",
                            sessionStartedAt = sessionStartedAt,
                            packetTotal = 0,
                            motorTuningStatus = "Prüfe 160C/160D",
                            motorTuningBusy = false
                        )
                    }
                    addLog("BLE verbunden, Status $status")
                    g.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    descriptorWriteRunning = false
                    notificationQueue.clear()
                    pendingMotorTuningWrite = null
                    update {
                        it.copy(
                            connected = false,
                            scanning = false,
                            status = "Verbindung getrennt (Status $status)",
                            labRunning = false,
                            motorTuningBusy = false,
                            motorTuningStatus = "Verbindung getrennt"
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
            val telemetryService = g.getService(SERVICE_TELEMETRY)
            if (telemetryService == null) {
                update { it.copy(status = "Telemetrie-Dienst nicht gefunden") }
                return
            }

            val tuningService = g.getService(SERVICE_MOTOR_TUNING)
            val tuningRead = tuningService?.getCharacteristic(MOTOR_TUNING_READ_CHARACTERISTIC)
            val tuningWrite = tuningService?.getCharacteristic(MOTOR_TUNING_WRITE_CHARACTERISTIC)
            val readAvailable = tuningRead != null
            val writeAvailable = tuningWrite != null &&
                (tuningWrite.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0 ||
                    tuningWrite.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0)
            val supported = readAvailable && writeAvailable

            val notifyChars = buildList {
                addAll(telemetryService.characteristics.filter { characteristic ->
                    val p = characteristic.properties
                    p and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0 ||
                        p and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0
                })
                tuningService?.characteristics?.filterTo(this) { characteristic ->
                    val p = characteristic.properties
                    p and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0 ||
                        p and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0
                }
            }.distinctBy { it.uuid }

            notificationQueue.clear()
            notificationQueue.addAll(
                notifyChars.sortedWith(
                    compareByDescending<BluetoothGattCharacteristic> { it.uuid == BATTERY_CHARACTERISTIC }
                        .thenByDescending { it.uuid == MOTOR_TUNING_READ_CHARACTERISTIC }
                )
            )
            addLog("${notifyChars.size} Datenkanäle gefunden")
            addLog(
                if (supported) "Motor-Tuning-Kanäle 160C/160D gefunden"
                else "Motor-Tuning nicht vollständig verfügbar: Lesen=$readAvailable, Schreiben=$writeAvailable"
            )
            update {
                it.copy(
                    status = "Verbunden – aktiviere Live-Daten",
                    motorTuningSupported = supported,
                    motorTuningReadAvailable = readAvailable,
                    motorTuningWriteAvailable = writeAvailable,
                    motorTuningStatus = if (supported) "Kanäle gefunden – lese Originalwerte" else "160C/160D nicht vollständig verfügbar"
                )
            }
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

        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicRead(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                handleValue(characteristic.uuid, characteristic.value ?: byteArrayOf())
            } else if (characteristic.uuid == MOTOR_TUNING_READ_CHARACTERISTIC) {
                finishMotorTuningFailure("Lesen von 160C fehlgeschlagen: $status")
            }
        }

        override fun onCharacteristicRead(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                handleValue(characteristic.uuid, value)
            } else if (characteristic.uuid == MOTOR_TUNING_READ_CHARACTERISTIC) {
                finishMotorTuningFailure("Lesen von 160C fehlgeschlagen: $status")
            }
        }

        override fun onCharacteristicWrite(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (characteristic.uuid != MOTOR_TUNING_WRITE_CHARACTERISTIC) return
            if (status == BluetoothGatt.GATT_SUCCESS) {
                update { it.copy(motorTuningStatus = "Paket gesendet – lese Bestätigung über 160C") }
                addLog("Motor-Tuning 160D geschrieben, lese nach 1 Sekunde zurück")
                mainHandler.postDelayed({ readMotorTuningValues(verificationRead = true) }, 1_000L)
            } else {
                finishMotorTuningFailure("Schreiben auf 160D fehlgeschlagen: $status")
            }
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
            if (_state.value.motorTuningReadAvailable) readMotorTuningValues()
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

    @SuppressLint("MissingPermission")
    fun readMotorTuningValues(verificationRead: Boolean = false) {
        val g = gatt
        val characteristic = g?.getService(SERVICE_MOTOR_TUNING)
            ?.getCharacteristic(MOTOR_TUNING_READ_CHARACTERISTIC)
        if (g == null || characteristic == null) {
            finishMotorTuningFailure("Motor-Tuning-Lesekanal 160C nicht verfügbar")
            return
        }
        if (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_READ == 0) {
            update {
                it.copy(
                    motorTuningBusy = pendingMotorTuningWrite != null,
                    motorTuningStatus = "160C ist nur Notify – warte auf Controllerdaten"
                )
            }
            return
        }

        motorTuningBuffer.clear()
        val requestAt = System.currentTimeMillis()
        lastMotorTuningReadRequestAt = requestAt
        update {
            it.copy(
                motorTuningBusy = true,
                motorTuningStatus = if (verificationRead) "Prüfe über 160C …" else "Lese Originalwerte über 160C …",
                motorTuningLastVerified = if (verificationRead) null else it.motorTuningLastVerified
            )
        }
        val started = g.readCharacteristic(characteristic)
        if (!started) {
            finishMotorTuningFailure("Lesevorgang für 160C konnte nicht gestartet werden")
            return
        }
        mainHandler.postDelayed({
            if (lastMotorTuningReadRequestAt == requestAt && _state.value.motorTuningBusy) {
                finishMotorTuningFailure("Keine vollständige 160C-Antwort empfangen")
            }
        }, 5_000L)
    }

    fun writeMotorTuning(profileIndex: Int, requestedValues: Map<MotorTuningParameter, Int>) {
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
        if (!canStartMotorTuningWrite()) return
        val current = _state.value.motorTuningProfiles.firstOrNull { it.index == profileIndex }
        val original = _state.value.motorTuningOriginalProfiles.firstOrNull { it.index == profileIndex }
        if (current == null || original == null) {
            finishMotorTuningFailure("Kein gesichertes Originalprofil vorhanden")
            return
        }
        writeMotorTuningInternal(current, original.values, reset = false)
    }

    fun resetMotorTuning(profileIndex: Int) {
        if (!canStartMotorTuningWrite()) return
        val profile = _state.value.motorTuningProfiles.firstOrNull { it.index == profileIndex }
        if (profile == null) {
            finishMotorTuningFailure("Profil $profileIndex wurde nicht gelesen")
            return
        }
        val packet = MotorTuningProtocol.buildResetPacket(profileIndex, _state.value.motorTuningProtocol)
        writeMotorTuningPacket(profileIndex, emptyMap(), packet, reset = true)
    }

    private fun canStartMotorTuningWrite(): Boolean {
        val snapshot = _state.value
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
            recordingActive -> {
                finishMotorTuningFailure("Während einer Messfahrt wird nicht geschrieben"); false
            }
            (snapshot.speedKmh ?: 0.0) > 0.5 -> {
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
        val g = gatt
        val characteristic = g?.getService(SERVICE_MOTOR_TUNING)
            ?.getCharacteristic(MOTOR_TUNING_WRITE_CHARACTERISTIC)
        if (g == null || characteristic == null) {
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

        val packetHex = MotorTuningProtocol.packetHex(packet)
        val pending = PendingMotorTuningWrite(
            profileIndex = profileIndex,
            expectedValues = expectedValues,
            packetHex = packetHex,
            reset = reset,
            startedAt = System.currentTimeMillis()
        )
        pendingMotorTuningWrite = pending
        update {
            it.copy(
                motorTuningBusy = true,
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
            if (pendingMotorTuningWrite?.startedAt == pending.startedAt) {
                finishMotorTuningFailure("Keine bestätigte Rückmeldung vom Controller")
            }
        }, 8_000L)
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
            pendingMotorTuningWrite = null
        } else {
            addLog("Motor-Tuning gelesen: ${result.mode.label}, ${result.profiles.size} Profil(e)")
        }

        update {
            it.copy(
                motorTuningBusy = false,
                motorTuningStatus = resultStatus,
                motorTuningProtocol = result.mode,
                motorTuningProfiles = result.profiles,
                motorTuningOriginalProfiles = original,
                motorTuningLastReadRaw = result.frameHex,
                motorTuningLastVerified = verified ?: it.motorTuningLastVerified
            )
        }
    }

    private fun finishMotorTuningFailure(message: String) {
        pendingMotorTuningWrite = null
        lastMotorTuningReadRequestAt = 0L
        update {
            it.copy(
                motorTuningBusy = false,
                motorTuningStatus = "✕ $message",
                motorTuningLastVerified = false
            )
        }
        addLog("Motor-Tuning: $message")
    }

    private fun handleValue(uuid: UUID, value: ByteArray) {
        if (uuid == MOTOR_TUNING_READ_CHARACTERISTIC) handleMotorTuningValue(value)
        decoderLab.record(uuid, value)
        val hex = value.joinToString("-") { "%02X".format(it.toInt() and 0xFF) }
        val short = shortUuid(uuid)
        val now = System.currentTimeMillis()
        val relativeMs = now - sessionStartedAt
        val previous = previousValues[short]
        val changed = changedByteIndexes(previous, value)
        previousValues[short] = value.copyOf()
        val count = (channelPacketCounts[short] ?: 0) + 1
        channelPacketCounts[short] = count

        val decoded = LiveTelemetryDecoder.decode(short, value)
        val knowledge = VmaxProtocolCatalog.get(short)
        packetTimes.addLast(now)
        while (packetTimes.isNotEmpty() && packetTimes.first() < now - 3000L) packetTimes.removeFirst()
        val packetsPerSecond = packetTimes.size / 3.0
        val changedText = if (changed.isEmpty()) "–" else changed.joinToString(",")
        if (recordingActive && !recordingPaused) {
            val measurementMs = now - measurementStartedAt
            sessionRows += listOf(
                measurementMs.toString(), now.toString(), short, knowledge.title,
                value.size.toString(), count.toString(), changedText, hex
            ).joinToString(";")
            val snapshot = _state.value
            val power = if (snapshot.voltageV != null && snapshot.currentA != null) snapshot.voltageV * snapshot.currentA else null
            telemetryRows += listOf(
                measurementMs.toString(), now.toString(),
                (decoded.speedKmh ?: snapshot.speedKmh)?.toString().orEmpty(),
                (decoded.batteryPercent ?: snapshot.batteryPercent)?.toString().orEmpty(),
                (decoded.voltageV ?: snapshot.voltageV)?.toString().orEmpty(),
                (decoded.currentA ?: snapshot.currentA)?.toString().orEmpty(),
                power?.toString().orEmpty(),
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
                currentPowerW = if ((decoded.voltageV ?: it.voltageV) != null && (decoded.currentA ?: it.currentA) != null) (decoded.voltageV ?: it.voltageV)!! * (decoded.currentA ?: it.currentA)!! else it.currentPowerW,
                maxSpeedKmh = maxOf(it.maxSpeedKmh ?: 0.0, decoded.speedKmh ?: it.speedKmh ?: 0.0).takeIf { value -> value > 0.0 },
                maxPowerW = maxOf(it.maxPowerW ?: 0.0, abs(if ((decoded.voltageV ?: it.voltageV) != null && (decoded.currentA ?: it.currentA) != null) (decoded.voltageV ?: it.voltageV)!! * (decoded.currentA ?: it.currentA)!! else 0.0)).takeIf { value -> value > 0.0 },
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
        if (!_state.value.connected) {
            addLog("Messfahrt benötigt eine BLE-Verbindung")
            return
        }
        measurementStartedAt = System.currentTimeMillis()
        recordingActive = true
        recordingPaused = false
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

    fun toggleMeasurementPause() {
        if (!recordingActive) return
        recordingPaused = !recordingPaused
        val label = if (recordingPaused) "PAUSE" else "FORTSETZEN"
        addMarkerInternal(label, System.currentTimeMillis())
        update { it.copy(recordingPaused = recordingPaused, lastMarker = label, markerCount = it.markerCount + 1) }
        addLog(if (recordingPaused) "Messfahrt pausiert" else "Messfahrt fortgesetzt")
    }

    fun addMeasurementMarker(label: String) {
        if (!recordingActive) {
            addLog("Erst Messfahrt starten")
            return
        }
        val now = System.currentTimeMillis()
        addMarkerInternal(label, now)
        update { it.copy(markerCount = it.markerCount + 1, lastMarker = label) }
        addLog("Marker: $label")
    }

    fun stopMeasurementAndExport() {
        if (!recordingActive) return
        val stoppedAt = System.currentTimeMillis()
        addMarkerInternal("STOP", stoppedAt)
        recordingActive = false
        recordingPaused = false
        update { it.copy(recordingActive = false, recordingPaused = false, markerCount = it.markerCount + 1, lastMarker = "STOP", analysisPhase = "Messfahrt beendet") }
        exportMeasurementBundle(stoppedAt)
    }

    private fun addMarkerInternal(label: String, now: Long) {
        val relative = if (measurementStartedAt > 0L) now - measurementStartedAt else 0L
        markerRows += listOf(relative.toString(), now.toString(), label).joinToString(";")
    }

    private fun exportMeasurementBundle(stoppedAt: Long) {
        val stamp = java.text.SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", java.util.Locale.GERMANY).format(java.util.Date(measurementStartedAt))
        val folder = "VMAXDashboard/Messfahrt_$stamp"
        val telemetry = "relative_ms;timestamp_ms;channel;meaning;length;packet_no;changed_bytes;hex\n" + sessionRows.joinToString("\n")
        val liveTelemetry = "relative_ms;timestamp_ms;speed_kmh_candidate;battery_percent;voltage_v;current_a;power_w;motor_temp_c;battery_temp_c;trip_km;odometer_km;drive_raw_1505_b7;motor_load_raw_be;battery_state_raw_1509_b6;accessory_raw_b0;accessory_raw_b3;source_channel\n" + telemetryRows.joinToString("\n")
        val markers = "relative_ms;timestamp_ms;marker\n" + markerRows.joinToString("\n")
        val (findings, analysisReport) = MeasurementAnalyzer.analyze(sessionRows, markerRows)
        learningStore.merge(findings, _state.value.deviceName, stoppedAt)
        val learningJson = learningStore.exportJson()
        val summary = buildString {
            appendLine("VMAX Dashboard Messfahrt")
            appendLine("Start: $measurementStartedAt")
            appendLine("Ende: $stoppedAt")
            appendLine("Dauer_ms: ${stoppedAt - measurementStartedAt}")
            appendLine("BLE_Pakete: ${sessionRows.size}")
            appendLine("Marker: ${markerRows.size}")
            appendLine("Gerät: ${_state.value.deviceName}")
            appendLine("Kanäle: ${_state.value.channels.joinToString(",") { it.channel }}")
        }
        runCatching {
            writeDownloadFile(folder, "BLE_Rohdaten.csv", "text/csv", telemetry)
            writeDownloadFile(folder, "Live_Telemetrie.csv", "text/csv", liveTelemetry)
            writeDownloadFile(folder, "Ereignisse.csv", "text/csv", markers)
            writeDownloadFile(folder, "Zusammenfassung.txt", "text/plain", summary)
            writeDownloadFile(folder, "Automatische_Analyse.txt", "text/plain", analysisReport)
            writeDownloadFile(folder, "Lernprofil.json", "application/json", learningJson)
            historyStore.add(folder, _state.value.deviceName, measurementStartedAt, stoppedAt, sessionRows.size, markerRows.size, _state.value.channels.map { it.channel })
            val location = "Downloads/$folder"
            update { it.copy(lastExportMessage = "Messfahrt gespeichert: $location", autoAnalysisFindings = findings, learningProfileCount = learningStore.count(), sessionHistoryCount = historyStore.count(), lastSessionFolder = location) }
            addLog("Messfahrt gespeichert: $location")
        }.onFailure { error ->
            update { it.copy(lastExportMessage = "Speichern fehlgeschlagen: ${error.message}") }
            addLog("Messfahrt-Export fehlgeschlagen: ${error.message}")
        }
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
            resolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(content) }
                ?: error("Datei $fileName konnte nicht geschrieben werden")
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } else {
            val base = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: error("Speicher nicht verfügbar")
            val folder = File(base, relativeFolder).apply { mkdirs() }
            File(folder, fileName).writeText(content)
        }
    }

    fun exportSessionCsv() {
        val header = "relative_ms;timestamp_ms;channel;meaning;length;packet_no;changed_bytes;hex\n"
        val content = header + sessionRows.joinToString("\n")
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
                resolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(content) }
                    ?: error("Download-Datei konnte nicht geschrieben werden")
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
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
        uuid.toString().substring(4, 8).uppercase()

    private fun addLog(message: String) {
        update { it.copy(log = (listOf(message) + it.log).take(80)) }
    }

    private inline fun update(block: (ScooterState) -> ScooterState) {
        _state.value = block(_state.value)
    }
}

package de.kevin.vmaxdashboard

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.ContentValues
import android.provider.MediaStore
import android.os.Environment
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.ArrayDeque
import java.util.UUID
import java.io.File

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

    private val _state = MutableStateFlow(ScooterState(encryptedReports = secureStore.count(), learningProfileCount = learningStore.count(), sessionHistoryCount = historyStore.count()))
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
        previousValues.clear()
        channelPacketCounts.clear()
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
                    sessionStartedAt = System.currentTimeMillis()
                    sessionRows.clear()
                    previousValues.clear()
                    channelPacketCounts.clear()
                    packetTimes.clear()
                    update { it.copy(connected = true, status = "Verbunden – suche Dienste", sessionStartedAt = sessionStartedAt, packetTotal = 0) }
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
                (decoded.odometerKm ?: snapshot.odometerKm)?.toString().orEmpty(), short
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
                maxPowerW = maxOf(it.maxPowerW ?: 0.0, kotlin.math.abs(if ((decoded.voltageV ?: it.voltageV) != null && (decoded.currentA ?: it.currentA) != null) (decoded.voltageV ?: it.voltageV)!! * (decoded.currentA ?: it.currentA)!! else 0.0)).takeIf { value -> value > 0.0 },
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
        val liveTelemetry = "relative_ms;timestamp_ms;speed_kmh;battery_percent;voltage_v;current_a;power_w;motor_temp_c;battery_temp_c;trip_km;odometer_km;source_channel\n" + telemetryRows.joinToString("\n")
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
        update { it.copy(log = (listOf(message) + it.log).take(60)) }
    }

    private inline fun update(block: (ScooterState) -> ScooterState) {
        _state.value = block(_state.value)
    }
}

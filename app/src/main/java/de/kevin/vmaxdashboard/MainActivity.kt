@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package de.kevin.vmaxdashboard

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlin.math.abs

class MainActivity : ComponentActivity() {
    private lateinit var bleManager: BleScooterManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        bleManager = BleScooterManager(applicationContext)
        setContent { MaterialTheme { VmaxApp(bleManager) } }
    }

    override fun onDestroy() {
        bleManager.disconnect()
        super.onDestroy()
    }
}

@Composable
private fun VmaxApp(manager: BleScooterManager) {
    val state by manager.state.collectAsStateWithLifecycle()
    var selectedAction by remember { mutableStateOf("Bremse") }
    var expertMode by remember { mutableStateOf(true) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results -> if (results.values.all { it }) manager.startScan() }

    fun connect() {
        if (manager.hasRequiredPermissions()) manager.startScan()
        else {
            val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
            } else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
            permissionLauncher.launch(permissions)
        }
    }

    Scaffold(topBar = { TopAppBar(title = { Text("VMAX Dashboard • Version 7 Expert") }) }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(vertical = 14.dp)
        ) {
            item { StatusCard(state) }
            item { SpeedDashboard(state) }

            item { SectionTitle("Bestätigte Fahrdaten") }
            item {
                MetricRow(
                    "Akku", state.batteryPercent?.let { "$it %" } ?: "–",
                    "Kilometer", state.odometerKm?.let { "%.1f km".format(it) } ?: "–"
                )
            }
            item {
                MetricRow(
                    "Spannung", state.voltageV?.let { "%.2f V".format(it) } ?: "–",
                    "Strom", state.currentA?.let { "%.2f A".format(it) } ?: "–"
                )
            }
            item {
                MetricRow(
                    "Leistung direkt", state.motorLoadRaw?.let { "$it W" } ?: "–",
                    "Leistung V×A", state.currentPowerW?.let { "%.0f W".format(it) } ?: "–"
                )
            }
            item {
                MetricRow(
                    "Max. Tempo", state.maxSpeedKmh?.let { "%.1f km/h".format(it) } ?: "–",
                    "Max. Leistung", state.maxPowerW?.let { "%.0f W".format(it) } ?: "–"
                )
            }
            item {
                MetricRow(
                    "Signal", state.rssi?.let { "$it dBm" } ?: "–",
                    "Pakete/s", "%.1f".format(state.packetsPerSecond)
                )
            }

            item { AccessoryCard(state) }
            item { TemperatureAndTripCard(state) }

            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = { connect() },
                        enabled = !state.scanning && !state.connected,
                        modifier = Modifier.weight(1f)
                    ) { Text(if (state.scanning) "Suche …" else "Verbinden") }
                    OutlinedButton(
                        onClick = manager::disconnect,
                        enabled = state.connected || state.scanning,
                        modifier = Modifier.weight(1f)
                    ) { Text("Trennen") }
                }
            }

            item {
                AutomaticRideCard(
                    state = state,
                    onStart = manager::startMeasurement,
                    onStop = manager::stopMeasurementAndExport,
                    onPause = manager::toggleMeasurementPause,
                    onExport = manager::exportSessionCsv
                )
            }

            item { BrakePushTestCard(state, manager::addMeasurementMarker) }
            item { MarkerTestCard(state, manager::addMeasurementMarker) }

            item {
                DecoderLabCard(
                    state = state,
                    selectedAction = selectedAction,
                    onActionSelected = { selectedAction = it },
                    onBaseline = { manager.startLabBaseline(selectedAction) },
                    onActive = manager::startLabAction,
                    onFinish = manager::finishLab
                )
            }

            item {
                Card(shape = RoundedCornerShape(18.dp)) {
                    Row(
                        Modifier.fillMaxWidth().padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Expertenmodus", fontWeight = FontWeight.Bold)
                            Text("Alle Kanäle, Rohwerte, Kandidaten und Platzhalter anzeigen", style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(checked = expertMode, onCheckedChange = { expertMode = it })
                    }
                }
            }

            if (expertMode) {
                item { SectionTitle("Controller & Diagnose") }
                item { ConfirmedRawCard(state) }
                item { DeviceControllerCard(state) }
                item { SectionTitle("Alle BLE-Kanäle") }
                if (state.channels.isEmpty()) {
                    item { InfoCard("Noch keine BLE-Daten", "Nach der Verbindung erscheinen Rohbytes, Paketanzahl und geänderte Bytepositionen.") }
                } else {
                    items(state.channels) { ChannelCard(it) }
                }
                item { RawDataCard(state) }
            }

            item { SectionTitle("Automatische Lernanalyse") }
            if (state.autoAnalysisFindings.isEmpty()) {
                item { InfoCard("Noch keine Kandidaten", "Lange Fahrten werden automatisch verglichen. Für Bremse, Blinker und Licht helfen die Marker beim eindeutigen Zuordnen.") }
            } else {
                items(state.autoAnalysisFindings.take(20)) { finding ->
                    InfoCard(finding.marker, finding.description)
                }
            }

            item { SectionTitle("Datenprotokoll") }
            items(state.log) { line ->
                Text(line, style = MaterialTheme.typography.bodySmall)
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
}

@Composable
private fun StatusCard(state: ScooterState) {
    Card(shape = RoundedCornerShape(22.dp)) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(state.status, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("${state.deviceName} • Automatisches Langfahrt-Profil aktiv")
            Text(if (state.connected) "● Bluetooth verbunden" else "○ Bluetooth nicht verbunden")
            Text("Live: %.1f Pakete/s • ${state.channels.size} Kanäle • ${state.packetTotal} Pakete".format(state.packetsPerSecond))
            Text("Lernprofil: ${state.learningProfileCount} Kandidaten • Messungen: ${state.sessionHistoryCount}", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun SpeedDashboard(state: ScooterState) {
    val speed150d = remember(state.rawPackets) { decode150dSpeed(state.rawPackets["150D"]) }
    val difference = if (state.speedKmh != null && speed150d != null) abs(state.speedKmh - speed150d) else null
    Card(shape = RoundedCornerShape(22.dp)) {
        Column(
            Modifier.fillMaxWidth().padding(18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(state.speedKmh?.let { "%.1f".format(it) } ?: "—", style = MaterialTheme.typography.displayMedium, fontWeight = FontWeight.Black)
            Text("km/h • Hauptquelle 1505 Byte 6–7")
            Text("150D Vergleich: ${speed150d?.let { "%.1f km/h".format(it) } ?: "–"}")
            if (difference != null) {
                Text(
                    if (difference <= 1.0) "✓ Quellen stimmen überein" else "⚠ Abweichung: %.1f km/h".format(difference),
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }
    }
}

@Composable
private fun AccessoryCard(state: ScooterState) {
    val light = when (state.accessoryByte0) {
        0 -> "AUS"
        1 -> "AN"
        null -> "–"
        else -> "RAW ${state.accessoryByte0}"
    }
    val mode = when (state.accessoryByte3) {
        1 -> "ECO"
        2 -> "SPORT"
        null -> "–"
        else -> "UNBEKANNT"
    }
    Card(shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text("Licht & Fahrmodus", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("💡 Licht: $light  •  RAW 1508/0: ${state.accessoryByte0 ?: "–"}")
            Text("⚡ Fahrmodus: $mode  •  RAW 1508/3: ${state.accessoryByte3 ?: "–"}")
            Text("BT638: 1 = ECO, 2 = SPORT. Andere Modelle bleiben über RAW erkennbar.", style = MaterialTheme.typography.bodySmall)
            Text("Blinker und Bremse bleiben bis zum Markertest offen.", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun TemperatureAndTripCard(state: ScooterState) {
    Card(shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text("Weitere Decoderfelder", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("Motor-Temperatur: ${state.motorTemperatureC?.let { "%.1f °C".format(it) } ?: "nicht geliefert"}")
            Text("Akku-Temperatur: ${state.batteryTemperatureC?.let { "%.1f °C".format(it) } ?: "nicht geliefert"}")
            Text("Trip: ${state.tripDistanceKm?.let { "%.1f km".format(it) } ?: "noch offen"}")
            Text("Nicht verfügbare Werte werden nicht erfunden; FF/FFFF bleibt als nicht unterstützt sichtbar.", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun ConfirmedRawCard(state: ScooterState) {
    val p1505 = parseHex(state.rawPackets["1505"])
    val p1502 = parseHex(state.rawPackets["1502"])
    val p150a = parseHex(state.rawPackets["150A"])
    val p150d = parseHex(state.rawPackets["150D"])
    Card(shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Bestätigte & starke RAW-Felder", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("🟢 1505 Leistung A RAW: ${u16be(p1505, 0) ?: "–"}")
            Text("🟢 1505 Leistung B RAW: ${u16be(p1505, 2) ?: "–"}")
            Text("🟢 1505 Geschwindigkeit RAW: ${u16be(p1505, 6) ?: state.driveRaw ?: "–"}")
            Text("🟢 150D Geschwindigkeit RAW: ${u16be(p150d, 0) ?: "–"}")
            Text("🟡 150D zweites Statistikfeld: ${u16be(p150d, 2) ?: "–"}")
            Text("🟡 150A Motorstrom-Kandidat RAW: ${u16be(p150a, 0) ?: "–"}")
            Text("🟡 1502 statischer Wert A: ${u16be(p1502, 0) ?: "–"}")
            Text("🟡 1502 statischer Wert B: ${u16be(p1502, 6) ?: "–"}")
            Text("🟢 bestätigt • 🟡 starker Kandidat • Bedeutung bleibt bis zur Referenzmessung RAW", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun DeviceControllerCard(state: ScooterState) {
    val staticChannels = listOf("1501", "1502", "1503", "1504", "1507", "1514", "1516", "1517", "1518")
    Card(shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Gerät & Controller", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("Gerät: ${state.deviceName} • Adresse: ${state.address.ifBlank { "–" }}")
            Text("Erkannte Kanäle: ${state.channels.joinToString { it.channel }.ifBlank { "–" }}")
            staticChannels.forEach { channel ->
                val raw = state.rawPackets[channel]
                if (raw != null) Text("$channel: $raw", style = MaterialTheme.typography.bodySmall)
            }
            Text("Der sichere READ-Scan liest nur vorhandene lesbare Characteristics; es werden keine Scooter-Einstellungen geschrieben.", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun AutomaticRideCard(
    state: ScooterState,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onPause: () -> Unit,
    onExport: () -> Unit
) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(state.recordingActive, state.recordingStartedAt) {
        while (state.recordingActive) {
            now = System.currentTimeMillis()
            delay(100)
        }
    }
    val elapsed = if (state.recordingActive) (now - state.recordingStartedAt).coerceAtLeast(0L) else 0L

    Card(shape = RoundedCornerShape(22.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("● Automatische Langfahrt", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("Rohdaten, Telemetrie, automatische Analyse und Lernprofil werden zusammen gespeichert.")
            Text(formatElapsed(elapsed), style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Black)
            Text("Pakete: ${state.recordingPacketCount} • Marker: ${state.markerCount}")
            if (!state.recordingActive) {
                Button(onClick = onStart, enabled = state.connected, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                    Text(if (state.connected) "● LANGFAHRT STARTEN" else "ZUERST VERBINDEN", fontWeight = FontWeight.Black)
                }
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = onPause, modifier = Modifier.weight(1f)) {
                        Text(if (state.recordingPaused) "▶ FORTSETZEN" else "⏸ PAUSE")
                    }
                    Button(onClick = onStop, modifier = Modifier.weight(1f)) { Text("■ STOP & SPEICHERN") }
                }
            }
            OutlinedButton(onClick = onExport, enabled = state.packetTotal > 0 && !state.recordingActive, modifier = Modifier.fillMaxWidth()) {
                Text("Live-Daten zusätzlich als CSV")
            }
            if (state.lastExportMessage.isNotBlank()) Text(state.lastExportMessage, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun BrakePushTestCard(state: ScooterState, onMarker: (String) -> Unit) {
    Card(shape = RoundedCornerShape(22.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("🛴 Schiebe-/Brems-Test", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("Langfahrt starten, gleichmäßig ohne Gas schieben, zuerst „Schieben frei“ markieren und direkt vor dem Bremshebel „Bremse“ drücken.")
            Text("Spannung, Strom und Watt werden mitgespeichert, aber nicht automatisch als Bremsschalter bewertet. Gesucht wird ein zusätzliches stabiles Statusbyte.")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(
                    onClick = { onMarker("Schieben frei") },
                    enabled = state.recordingActive && !state.recordingPaused,
                    modifier = Modifier.weight(1f)
                ) { Text("Schieben frei") }
                Button(
                    onClick = { onMarker("Bremse beim Schieben") },
                    enabled = state.recordingActive && !state.recordingPaused,
                    modifier = Modifier.weight(1f)
                ) { Text("Bremse") }
            }
        }
    }
}

@Composable
private fun MarkerTestCard(state: ScooterState, onMarker: (String) -> Unit) {
    val markers = listOf("Stillstand", "Anfahren", "Langsam", "Vollgas", "Rollen", "Licht", "Links", "Rechts", "Fahrmodus")
    Card(shape = RoundedCornerShape(22.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("🏷 Weitere Marker", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            markers.chunked(2).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { marker ->
                        FilledTonalButton(
                            onClick = { onMarker(marker) },
                            enabled = state.recordingActive && !state.recordingPaused,
                            modifier = Modifier.weight(1f)
                        ) { Text(marker) }
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
            Text("Letzter Marker: ${state.lastMarker.ifBlank { "–" }}")
        }
    }
}

@Composable
private fun DecoderLabCard(
    state: ScooterState,
    selectedAction: String,
    onActionSelected: (String) -> Unit,
    onBaseline: () -> Unit,
    onActive: () -> Unit,
    onFinish: () -> Unit
) {
    val actions = listOf("Bremse", "Blinker links", "Blinker rechts", "Licht", "Fahrmodus", "Gas", "Rekuperation", "Unbekannt")
    var expanded by remember { mutableStateOf(false) }
    Card(shape = RoundedCornerShape(22.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("🔬 Kurzer Vergleichstest", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Box {
                OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) { Text(selectedAction) }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    actions.forEach { action ->
                        DropdownMenuItem(text = { Text(action) }, onClick = { onActionSelected(action); expanded = false })
                    }
                }
            }
            Text("Phase: ${state.labPhase}")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onBaseline, enabled = state.connected && !state.labRunning, modifier = Modifier.weight(1f)) { Text("1. Ruhe") }
                Button(onClick = onActive, enabled = state.labRunning && state.labPhase.startsWith("1/2"), modifier = Modifier.weight(1f)) { Text("2. Aktion") }
                Button(onClick = onFinish, enabled = state.labRunning && state.labPhase.startsWith("2/2"), modifier = Modifier.weight(1f)) { Text("3. Fertig") }
            }
            state.labCandidates.take(8).forEach {
                Text("${it.characteristic} • Byte ${it.byteIndex}: ${it.beforeValue} → ${it.activeValue} • ${it.score}%")
            }
        }
    }
}

@Composable
private fun ChannelCard(channel: BleChannelState) {
    val bytes = channel.hex.split("-").filter { it.isNotBlank() }
    val unsupported = bytes.isNotEmpty() && bytes.all { it.equals("FF", ignoreCase = true) }
    val status = when {
        unsupported -> "🔴 nicht unterstützt"
        channel.knowledge.contains("Bestätigt", ignoreCase = true) -> "🟢 bestätigt"
        channel.knowledge.contains("Kandidat", ignoreCase = true) -> "🟡 Kandidat"
        else -> "⚪ unbekannt"
    }
    Card(shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("${channel.channel} • ${channel.title}", fontWeight = FontWeight.Bold)
                Text(status, style = MaterialTheme.typography.labelMedium)
            }
            Text(if (unsupported) "Nur FF-Platzhalter – dieses Modell liefert hier keine Werte." else channel.meaning, style = MaterialTheme.typography.bodySmall)
            Text("Pakete: ${channel.packetCount} • geändert: ${channel.changedBytes}")
            Text(channel.hex, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun RawDataCard(state: ScooterState) {
    Card(shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text("Letztes BLE-Paket", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("Kanal: ${state.lastCharacteristic.ifBlank { "–" }} • Geändert: ${state.lastChangedBytes}")
            Text(state.lastRawHex.ifBlank { "Noch keine Daten empfangen" }, style = MaterialTheme.typography.bodySmall)
            Text("Diese Version liest Telemetrie und Diagnosewerte. Motor-Tuning bleibt aus der Oberfläche entfernt.", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun MetricRow(title1: String, value1: String, title2: String, value2: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        MetricCard(title1, value1, Modifier.weight(1f))
        MetricCard(title2, value2, Modifier.weight(1f))
    }
}

@Composable
private fun MetricCard(title: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier = modifier, shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.fillMaxWidth().padding(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(6.dp))
            Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun InfoCard(title: String, text: String) {
    Card(shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, fontWeight = FontWeight.Bold)
            Text(text, style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun decode150dSpeed(hex: String?): Double? {
    val bytes = parseHex(hex)
    val raw = u16be(bytes, 0) ?: return null
    if (raw == 0xFFFF) return null
    return raw / 10.0
}

private fun parseHex(hex: String?): List<Int> =
    hex.orEmpty().split('-', ' ', ':').mapNotNull { token -> token.trim().takeIf { it.length == 2 }?.toIntOrNull(16) }

private fun u16be(bytes: List<Int>, index: Int): Int? {
    if (index < 0 || index + 1 >= bytes.size) return null
    return (bytes[index] shl 8) or bytes[index + 1]
}

private fun formatElapsed(ms: Long): String {
    val hours = ms / 3_600_000
    val minutes = (ms / 60_000) % 60
    val seconds = (ms / 1_000) % 60
    val millis = ms % 1_000
    return "%02d:%02d:%02d.%03d".format(hours, minutes, seconds, millis)
}

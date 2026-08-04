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
import kotlinx.coroutines.launch
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
    val gattScanner = remember(manager) { GattReadScanner(manager) }
    val gattState by gattScanner.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var expertMode by remember { mutableStateOf(true) }
    var selectedAction by remember { mutableStateOf("Bremse") }

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

    fun directMarker(label: String) {
        scope.launch {
            if (!state.recordingActive) {
                manager.startMeasurement()
                delay(150)
            }
            manager.addMeasurementMarker(label)
        }
    }

    LaunchedEffect(state.connected) {
        if (state.connected) {
            delay(900)
            if (!manager.state.value.recordingActive) manager.startMeasurement()
            delay(1_800)
            gattScanner.scanAndRead()
        } else {
            gattScanner.reset()
        }
    }

    Scaffold(topBar = { TopAppBar(title = { Text("VMAX Dashboard • Version 7.1 Decoder AI") }) }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(vertical = 14.dp)
        ) {
            item { StatusCard(state, gattState) }
            item { SpeedCard(state) }

            item { SectionTitle("Bestätigte Fahrdaten") }
            item { MetricRow("Akku", state.batteryPercent?.let { "$it %" } ?: "–", "Kilometer", state.odometerKm?.let { "%.1f km".format(it) } ?: "–") }
            item { MetricRow("Spannung", state.voltageV?.let { "%.2f V".format(it) } ?: "–", "Strom", state.currentA?.let { "%.2f A".format(it) } ?: "–") }
            item { MetricRow("Leistung direkt", state.motorLoadRaw?.let { "$it W" } ?: "–", "Leistung V×A", state.currentPowerW?.let { "%.0f W".format(it) } ?: "–") }
            item { MetricRow("Max. Tempo", state.maxSpeedKmh?.let { "%.1f km/h".format(it) } ?: "–", "Max. Leistung", state.maxPowerW?.let { "%.0f W".format(it) } ?: "–") }
            item { LightModeCard(state) }

            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = ::connect, enabled = !state.scanning && !state.connected, modifier = Modifier.weight(1f)) {
                        Text(if (state.scanning) "Suche …" else "Verbinden")
                    }
                    OutlinedButton(onClick = manager::disconnect, enabled = state.connected || state.scanning, modifier = Modifier.weight(1f)) {
                        Text("Trennen")
                    }
                }
            }

            item { AutoRecordingCard(state, manager::stopMeasurementAndExport, manager::exportSessionCsv) }

            item { SectionTitle("Direkttests – einmal drücken") }
            item { DirectMarkerCard(state, ::directMarker) }

            item { GattSummaryCard(gattState, onScan = gattScanner::scanAndRead, enabled = state.connected) }

            item {
                Card(shape = RoundedCornerShape(18.dp)) {
                    Row(
                        Modifier.fillMaxWidth().padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Expertenmodus", fontWeight = FontWeight.Bold)
                            Text("GATT-Struktur, alle RAW-Kanäle und 3-Schritt-Test")
                        }
                        Switch(checked = expertMode, onCheckedChange = { expertMode = it })
                    }
                }
            }

            if (expertMode) {
                item { ConfirmedRawCard(state) }
                item { SectionTitle("Vollständiger GATT-Explorer") }
                if (gattState.entries.isEmpty()) {
                    item { InfoCard("Noch keine GATT-Daten", "Nach der Verbindung startet der sichere READ-Scan automatisch.") }
                } else {
                    items(gattState.entries) { GattEntryCard(it) }
                }

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

                item { SectionTitle("Alle empfangenen BLE-Kanäle") }
                if (state.channels.isEmpty()) {
                    item { InfoCard("Noch keine Live-Daten", "Alle Notify-/Indicate-Kanäle erscheinen nach der Verbindung automatisch.") }
                } else {
                    items(state.channels) { ChannelCard(it) }
                }
                item { RawDataCard(state) }
            }

            item { SectionTitle("Automatische Lernanalyse") }
            if (state.autoAnalysisFindings.isEmpty()) {
                item { InfoCard("Decoder AI läuft", "Stillstand, Fahrt und alle Rohbytes werden dauerhaft aufgezeichnet. Für Schalterdaten direkt vor der Aktion einmal den passenden Testknopf drücken.") }
            } else {
                items(state.autoAnalysisFindings.take(20)) { InfoCard(it.marker, it.description) }
            }

            item { SectionTitle("Protokoll") }
            items(state.log) {
                Text(it, style = MaterialTheme.typography.bodySmall)
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
private fun StatusCard(state: ScooterState, gatt: GattScanState) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(state.connected, state.lastPacketAt) {
        while (state.connected) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }
    val age = state.lastPacketAt.takeIf { it > 0 }?.let { ((now - it) / 1_000L).coerceAtLeast(0) }
    Card(shape = RoundedCornerShape(22.dp)) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(state.status, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(if (state.connected) "● Bluetooth verbunden" else "○ Bluetooth nicht verbunden")
            Text(if (state.recordingActive) "● Auto-KI-Aufnahme läuft" else "○ Aufnahme wartet")
            Text("%.1f Pakete/s • ${state.packetTotal} Pakete • ${state.channels.size} Live-Kanäle".format(state.packetsPerSecond))
            Text("GATT: ${gatt.serviceCount} Dienste • ${gatt.characteristicCount} Characteristics • ${gatt.readableCount} lesbar")
            if (state.connected && age != null && age > 10) Text("⚠ BLE-Datenstrom pausiert – letztes Paket vor ${age}s")
        }
    }
}

@Composable
private fun SpeedCard(state: ScooterState) {
    val speed150d = decode150dSpeed(state.rawPackets["150D"])
    val diff = if (state.speedKmh != null && speed150d != null) abs(state.speedKmh - speed150d) else null
    Card(shape = RoundedCornerShape(22.dp)) {
        Column(Modifier.fillMaxWidth().padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(state.speedKmh?.let { "%.1f".format(it) } ?: "—", style = MaterialTheme.typography.displayMedium, fontWeight = FontWeight.Black)
            Text("km/h • Hauptquelle 1505 Byte 6–7")
            Text("150D Vergleich: ${speed150d?.let { "%.1f km/h".format(it) } ?: "–"}")
            if (diff != null) Text(if (diff <= 1.0) "✓ Quellen stimmen überein" else "⚠ Abweichung %.1f km/h".format(diff))
        }
    }
}

@Composable
private fun LightModeCard(state: ScooterState) {
    val light = when (state.accessoryByte0) { 0 -> "AUS"; 1 -> "AN"; null -> "–"; else -> "RAW ${state.accessoryByte0}" }
    val mode = when (state.accessoryByte3) { 1 -> "ECO"; 2 -> "SPORT"; null -> "–"; else -> "RAW ${state.accessoryByte3}" }
    Card(shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text("Licht & Fahrmodus", fontWeight = FontWeight.Bold)
            Text("💡 Licht: $light • RAW 1508/0: ${state.accessoryByte0 ?: "–"}")
            Text("⚡ Fahrmodus: $mode • RAW 1508/3: ${state.accessoryByte3 ?: "–"}")
            Text("Für deinen BT638 bestätigt: 0/1 = AUS/AN und 1/2 = ECO/SPORT.", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun AutoRecordingCard(state: ScooterState, onStop: () -> Unit, onExport: () -> Unit) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(state.recordingActive) {
        while (state.recordingActive) { now = System.currentTimeMillis(); delay(250) }
    }
    val elapsed = if (state.recordingActive) (now - state.recordingStartedAt).coerceAtLeast(0) else 0
    Card(shape = RoundedCornerShape(22.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Automatische Daueraufnahme", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("Startet nach der Verbindung selbst. Alle Live-Daten und READ-Ergebnisse werden im selben Decoderprofil verglichen.")
            Text(formatElapsed(elapsed), style = MaterialTheme.typography.titleLarge)
            Text("Pakete: ${state.recordingPacketCount} • Marker: ${state.markerCount}")
            Button(onClick = onStop, enabled = state.recordingActive, modifier = Modifier.fillMaxWidth()) { Text("STOPPEN, ANALYSIEREN & SPEICHERN") }
            OutlinedButton(onClick = onExport, enabled = state.packetTotal > 0, modifier = Modifier.fillMaxWidth()) { Text("Rohdaten zusätzlich als CSV") }
            if (state.lastExportMessage.isNotBlank()) Text(state.lastExportMessage, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun DirectMarkerCard(state: ScooterState, onMarker: (String) -> Unit) {
    val markers = listOf(
        "Stillstand", "Bremse im Stand", "Schieben frei", "Bremse beim Schieben",
        "Licht AUS", "Licht AN", "ECO", "SPORT",
        "Blinker links", "Blinker rechts", "Anfahren", "Rollen", "Vollgas", "Laden"
    )
    Card(shape = RoundedCornerShape(22.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Ein-Knopf-Tests", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("Direkt vor der Aktion einmal drücken. Falls die Aufnahme aus ist, startet sie automatisch.")
            markers.chunked(2).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { marker ->
                        Button(onClick = { onMarker(marker) }, enabled = state.connected, modifier = Modifier.weight(1f)) { Text(marker) }
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
            Text("Letzter Marker: ${state.lastMarker.ifBlank { "–" }}")
        }
    }
}

@Composable
private fun GattSummaryCard(state: GattScanState, onScan: () -> Unit, enabled: Boolean) {
    Card(shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text("BLE-Explorer & sicherer READ-Scan", fontWeight = FontWeight.Bold)
            Text(state.status)
            Text("Dienste ${state.serviceCount} • Characteristics ${state.characteristicCount} • READ ${state.readableCount} • gestartet ${state.startedReads}")
            LinearProgressIndicator(
                progress = { if (state.readableCount > 0) state.startedReads.toFloat() / state.readableCount else 0f },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedButton(onClick = onScan, enabled = enabled && !state.running, modifier = Modifier.fillMaxWidth()) {
                Text(if (state.running) "READ-SCAN LÄUFT" else "ALLE SICHEREN READ-FELDER NOCHMALS LESEN")
            }
            Text("Es wird nichts geschrieben. WRITE-Fähigkeiten werden nur angezeigt.", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun GattEntryCard(entry: GattCharacteristicInfo) {
    val status = when {
        entry.lastReadStarted -> "✓ READ gestartet"
        entry.readable -> "READ verfügbar"
        entry.notifiable || entry.indicatable -> "Live-Kanal"
        else -> "statisch/sonstig"
    }
    Card(shape = RoundedCornerShape(14.dp)) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text("${entry.serviceUuid} → ${entry.characteristicUuid}", fontWeight = FontWeight.Bold)
            Text(entry.properties, style = MaterialTheme.typography.bodySmall)
            Text(status, style = MaterialTheme.typography.labelMedium)
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
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text("Bestätigte & starke RAW-Felder", fontWeight = FontWeight.Bold)
            Text("🟢 1505 Motor/Leistung A: ${u16be(p1505, 0) ?: "–"}")
            Text("🟢 1505 Motor/Leistung B: ${u16be(p1505, 2) ?: "–"}")
            Text("🟢 1505 Tempo RAW: ${u16be(p1505, 6) ?: "–"}")
            Text("🟢 150D Tempo RAW: ${u16be(p150d, 0) ?: "–"}")
            Text("🟡 150D Feld 2: ${u16be(p150d, 2) ?: "–"}")
            Text("🟡 150A Strom/Last: ${u16be(p150a, 0) ?: "–"}")
            Text("🟡 1502 statisch A/B: ${u16be(p1502, 0) ?: "–"} / ${u16be(p1502, 6) ?: "–"}")
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
    val actions = listOf("Bremse", "Blinker links", "Blinker rechts", "Licht", "Fahrmodus", "Gas", "Rekuperation")
    var expanded by remember { mutableStateOf(false) }
    Card(shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Optionaler 3-Schritt-Vergleichstest", fontWeight = FontWeight.Bold)
            Box {
                OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) { Text(selectedAction) }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    actions.forEach { action -> DropdownMenuItem(text = { Text(action) }, onClick = { onActionSelected(action); expanded = false }) }
                }
            }
            Text("Phase: ${state.labPhase}")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(onClick = onBaseline, enabled = state.connected && !state.labRunning, modifier = Modifier.weight(1f)) { Text("1 Ruhe") }
                Button(onClick = onActive, enabled = state.labRunning && state.labPhase.startsWith("1/2"), modifier = Modifier.weight(1f)) { Text("2 Aktion") }
                Button(onClick = onFinish, enabled = state.labRunning && state.labPhase.startsWith("2/2"), modifier = Modifier.weight(1f)) { Text("3 Fertig") }
            }
            state.labCandidates.take(8).forEach { Text("${it.characteristic} Byte ${it.byteIndex}: ${it.beforeValue}→${it.activeValue} • ${it.score}%", style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
private fun ChannelCard(channel: BleChannelState) {
    val bytes = channel.hex.split("-").filter { it.isNotBlank() }
    val unsupported = bytes.isNotEmpty() && bytes.all { it.equals("FF", true) }
    val level = when {
        unsupported -> "🔴 nicht unterstützt"
        channel.knowledge.contains("Bestätigt", true) -> "🟢 bestätigt"
        channel.knowledge.contains("Kandidat", true) -> "🟡 Kandidat"
        else -> "⚪ unbekannt"
    }
    Card(shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.fillMaxWidth().padding(13.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("${channel.channel} • ${channel.title}", fontWeight = FontWeight.Bold)
                Text(level, style = MaterialTheme.typography.labelSmall)
            }
            Text(if (unsupported) "Nur FF-Platzhalter" else channel.meaning, style = MaterialTheme.typography.bodySmall)
            Text("Pakete ${channel.packetCount} • geändert ${channel.changedBytes}")
            Text(channel.hex, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun RawDataCard(state: ScooterState) {
    InfoCard("Letztes BLE-Paket", "${state.lastCharacteristic.ifBlank { "–" }} • Δ ${state.lastChangedBytes}\n${state.lastRawHex.ifBlank { "Noch keine Daten" }}")
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
    val raw = u16be(parseHex(hex), 0) ?: return null
    return if (raw == 0xFFFF) null else raw / 10.0
}

private fun parseHex(hex: String?): List<Int> =
    hex.orEmpty().split('-', ' ', ':').mapNotNull { it.trim().takeIf { token -> token.length == 2 }?.toIntOrNull(16) }

private fun u16be(bytes: List<Int>, index: Int): Int? =
    if (index < 0 || index + 1 >= bytes.size) null else (bytes[index] shl 8) or bytes[index + 1]

private fun formatElapsed(ms: Long): String {
    val hours = ms / 3_600_000
    val minutes = (ms / 60_000) % 60
    val seconds = (ms / 1_000) % 60
    return "%02d:%02d:%02d".format(hours, minutes, seconds)
}

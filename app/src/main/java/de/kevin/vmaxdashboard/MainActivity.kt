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

    Scaffold(topBar = { TopAppBar(title = { Text("VMAX Dashboard • Version 7") }) }) { padding ->
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
                    "Signal", state.rssi?.let { "$it dBm" } ?: "–"
                )
            }
            item { AccessoryCard(state) }

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

            item { SectionTitle("Bekannte Kanäle & Live-Analyse") }
            if (state.channels.isEmpty()) {
                item { InfoCard("Noch keine BLE-Daten", "Nach der Verbindung erscheinen Rohbytes, Paketanzahl und geänderte Bytepositionen.") }
            } else {
                items(state.channels) { ChannelCard(it) }
            }

            item { RawDataCard(state) }
            item { SectionTitle("Datenprotokoll") }
            items(state.log) { line ->
                Text(line, style = MaterialTheme.typography.bodySmall)
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun StatusCard(state: ScooterState) {
    Card(shape = RoundedCornerShape(22.dp)) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(state.status, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("${state.deviceName} • Automatisches Langfahrt-Profil aktiv")
            Text(if (state.connected) "● Bluetooth verbunden" else "○ Bluetooth nicht verbunden")
            Text("Live: %.1f Pakete/s • ${state.channels.size} Kanäle".format(state.packetsPerSecond))
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
            Text("km/h • Hauptquelle 1505")
            Text("150D Vergleich: ${speed150d?.let { "%.1f km/h".format(it) } ?: "–"}")
            if (difference != null && difference > 1.0) {
                Text("⚠ Abweichung: %.1f km/h".format(difference), style = MaterialTheme.typography.labelLarge)
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
            Text("Zuordnung für BT638: 1 = ECO, 2 = SPORT. Andere Modelle bleiben über RAW erkennbar.", style = MaterialTheme.typography.bodySmall)
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
            Text("1. Langfahrt starten. 2. Scooter ohne Gas gleichmäßig schieben. 3. Marker „Schieben frei“. 4. Weiter schieben und direkt vor dem Bremshebel Marker „Bremse beim Schieben“ drücken.")
            Text("Spannung, Strom und Watt werden mitgespeichert, aber nicht automatisch als Bremssignal gewertet. Gesucht wird ein eigenes stabiles Statusbyte.")
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
    val unsupported = channel.hex.split("-").filter { it.isNotBlank() }.all { it.equals("FF", ignoreCase = true) }
    Card(shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text("${channel.channel} • ${channel.title}", fontWeight = FontWeight.Bold)
            Text(if (unsupported) "Nicht unterstützt: nur FF-Platzhalter" else channel.knowledge)
            Text(channel.meaning, style = MaterialTheme.typography.bodySmall)
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
            Text("Die App schreibt keine Motor-Tuning-Werte an den Scooter.")
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
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
}

@Composable
private fun InfoCard(title: String, text: String) {
    Card(shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(title, fontWeight = FontWeight.Bold)
            Text(text, style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun decode150dSpeed(hex: String?): Double? {
    val bytes = hex?.split("-")?.mapNotNull { it.toIntOrNull(16) } ?: return null
    if (bytes.size < 2) return null
    val raw = (bytes[0] shl 8) or bytes[1]
    if (raw == 0xFFFF) return null
    return raw / 10.0
}

private fun formatElapsed(ms: Long): String {
    val hours = ms / 3_600_000
    val minutes = (ms / 60_000) % 60
    val seconds = (ms / 1_000) % 60
    val millis = ms % 1_000
    return "%02d:%02d:%02d.%03d".format(hours, minutes, seconds, millis)
}

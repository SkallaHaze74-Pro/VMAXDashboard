@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package de.kevin.vmaxdashboard

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
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
    var selectedAction by remember { mutableStateOf("Blinker links") }
    var realtimeNow by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var realtimeResult by remember { mutableStateOf<RealtimeBleResult?>(null) }

    LaunchedEffect(state.connected) {
        if (!state.connected) {
            realtimeResult = null
            realtimeNow = System.currentTimeMillis()
        } else {
            while (true) {
                realtimeNow = System.currentTimeMillis()
                delay(100)
            }
        }
    }

    LaunchedEffect(state.status) {
        if (state.connected && state.status == "Live-Daten aktiv") {
            delay(250)
            realtimeResult = manager.enableRealtimeBleMode()
        }
    }

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

    Scaffold(topBar = { TopAppBar(title = { Text("VMAX Dashboard • Echtzeit-Test") }) }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(vertical = 14.dp)
        ) {
            item { StatusCard(state, realtimeResult) }
            item { IndicatorDashboard(state, realtimeNow) }

            item { SectionTitle("Fahrt & Technik") }
            item {
                MetricRow(
                    "Geschwindigkeit", state.speedKmh?.let { "%.1f km/h".format(it) } ?: "–",
                    "Akku", state.batteryPercent?.let { "$it %" } ?: "–"
                )
            }
            item {
                MetricRow(
                    "Akkuspannung", state.voltageV?.let { "%.1f V".format(it) } ?: "–",
                    "Akkustrom", state.currentA?.let { "%.2f A".format(it) } ?: "–"
                )
            }
            item {
                MetricRow(
                    "Leistung",
                    state.motorLoadRaw?.let { "$it W" }
                        ?: state.currentPowerW?.let { "%.0f W".format(it) }
                        ?: "–",
                    "Trip", state.tripDistanceKm?.let { "%.1f km".format(it) } ?: "wird geprüft"
                )
            }
            item {
                MetricRow(
                    "Geschw. RAW", state.driveRaw?.toString() ?: "–",
                    "Leistung RAW", state.motorLoadRaw?.toString() ?: "–"
                )
            }
            item {
                MetricRow(
                    "Kilometerstand", state.odometerKm?.let { "%.1f km".format(it) } ?: "–",
                    "Signal", state.rssi?.let { "$it dBm" } ?: "–"
                )
            }
            item {
                MetricRow(
                    "Motor-Temp.", state.motorTemperatureC?.let { "%.1f °C".format(it) } ?: "–",
                    "Akku-Temp.", state.batteryTemperatureC?.let { "%.1f °C".format(it) } ?: "–"
                )
            }
            item {
                MetricRow(
                    "Pakete/s", "%.1f".format(state.packetsPerSecond),
                    "Pakete gesamt", state.packetTotal.toString()
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
                        onClick = { manager.disconnect() },
                        enabled = state.connected || state.scanning,
                        modifier = Modifier.weight(1f)
                    ) { Text("Trennen") }
                }
            }

            item { MotorTuningCard(state, manager) }

            item {
                MeasurementCard(
                    state = state,
                    onStart = manager::startMeasurement,
                    onStop = manager::stopMeasurementAndExport,
                    onPause = manager::toggleMeasurementPause,
                    onMarker = manager::addMeasurementMarker,
                    onExport = manager::exportSessionCsv
                )
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

            item { SectionTitle("Bekannte Kanäle & Live-Analyse") }
            if (state.channels.isEmpty()) {
                item { InfoCard("Noch keine BLE-Daten", "Nach der Verbindung erscheinen hier Rohbytes, Paketanzahl, Paketalter und geänderte Bytepositionen.") }
            } else {
                items(state.channels) { ChannelCard(it, realtimeNow) }
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
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
}

@Composable
private fun StatusCard(state: ScooterState, realtimeResult: RealtimeBleResult?) {
    Card(shape = RoundedCornerShape(22.dp)) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(state.status, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("${state.deviceName} • Modellprofil wird automatisch gelernt")
            Text(if (state.connected) "● Bluetooth verbunden" else "○ Bluetooth nicht verbunden")
            Text("Analyse: ${state.analysisPhase} • ${state.channels.size} Kanäle")
            Text(
                "Live: %.1f Pakete/s • Lernkandidaten: ${state.learningProfileCount} • Messungen: ${state.sessionHistoryCount}"
                    .format(state.packetsPerSecond),
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                realtimeResult?.message ?: if (state.connected) "Echtzeitmodus wird nach Live-Start angefordert" else "Echtzeitmodus wartet auf Verbindung",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun IndicatorDashboard(state: ScooterState, now: Long) {
    val speedLastSeen = state.channels.firstOrNull { it.channel == "1505" }?.lastSeenMs ?: 0L
    val speedAge = if (speedLastSeen > 0L) (now - speedLastSeen).coerceAtLeast(0L) else null
    Card(shape = RoundedCornerShape(22.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 18.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(if (state.leftIndicator) "◀" else "◁", style = MaterialTheme.typography.displaySmall)
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    state.speedKmh?.let { "%.1f".format(it) } ?: "—",
                    style = MaterialTheme.typography.displayMedium,
                    fontWeight = FontWeight.Black
                )
                Text("km/h")
                Text(speedAge?.let { "1505 zuletzt vor ${formatPacketAge(it)}" } ?: "Warte auf Geschwindigkeitskanal")
            }
            Text(if (state.rightIndicator) "▶" else "▷", style = MaterialTheme.typography.displaySmall)
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
private fun AccessoryCard(state: ScooterState) {
    val lightText = when (state.accessoryByte0) {
        0 -> "aus"
        1 -> "an"
        else -> "unbekannt"
    }
    Card(shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Zubehör & Status", fontWeight = FontWeight.Bold)
            Text("Licht: $lightText • Fahrstufe: ${state.accessoryByte3 ?: "–"}")
            Text("Blinker links/rechts: noch nicht identifiziert")
            Text("Bremse: noch nicht identifiziert")
            Text("Schloss: ${state.lockActive?.let { if (it) "aktiv" else "offen" } ?: "wird erkannt"}")
            Text("Laden: ${state.charging?.let { if (it) "ja" else "nein" } ?: "wird erkannt"}")
        }
    }
}

@Composable
private fun MeasurementCard(
    state: ScooterState,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onPause: () -> Unit,
    onMarker: (String) -> Unit,
    onExport: () -> Unit
) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var lastPressedMarker by remember { mutableStateOf("") }
    LaunchedEffect(state.recordingActive, state.recordingStartedAt) {
        while (state.recordingActive) {
            now = System.currentTimeMillis()
            delay(100)
        }
    }
    val elapsed = if (state.recordingActive) (now - state.recordingStartedAt).coerceAtLeast(0L) else 0L
    val markers = listOf("Stillstand", "Anfahren", "Langsam", "Vollgas", "Rollen", "Bremse", "Licht", "Links", "Rechts")

    Card(shape = RoundedCornerShape(22.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("● Messaufnahme", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(formatElapsed(elapsed), style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Black)
            Text("Pakete: ${state.recordingPacketCount} • Marker: ${state.markerCount} • Letzter: ${state.lastMarker.ifBlank { "–" }}")
            if (!state.recordingActive) {
                Button(onClick = onStart, enabled = state.connected, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                    Text(if (state.connected) "● AUFNAHME STARTEN" else "ZUERST VERBINDEN", fontWeight = FontWeight.Black)
                }
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = onPause, modifier = Modifier.weight(1f)) {
                        Text(if (state.recordingPaused) "▶ FORTSETZEN" else "⏸ PAUSE")
                    }
                    Button(onClick = onStop, modifier = Modifier.weight(1f)) { Text("■ STOP & SPEICHERN") }
                }
                markers.chunked(3).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        row.forEach { marker ->
                            val selected = lastPressedMarker == marker || state.lastMarker == marker
                            FilledTonalButton(
                                onClick = { lastPressedMarker = marker; onMarker(marker) },
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(horizontal = 4.dp)
                            ) { Text(if (selected) "✓ $marker" else marker, style = MaterialTheme.typography.labelSmall) }
                        }
                        repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
            OutlinedButton(onClick = onExport, enabled = state.packetTotal > 0 && !state.recordingActive, modifier = Modifier.fillMaxWidth()) {
                Text("Aktuelle Live-Daten zusätzlich als CSV")
            }
            if (state.lastExportMessage.isNotBlank()) Text(state.lastExportMessage, style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun formatElapsed(ms: Long): String {
    val hours = ms / 3_600_000
    val minutes = (ms / 60_000) % 60
    val seconds = (ms / 1_000) % 60
    val millis = ms % 1_000
    return "%02d:%02d:%02d.%03d".format(hours, minutes, seconds, millis)
}

private fun formatPacketAge(ms: Long): String = when {
    ms < 1_000L -> "$ms ms"
    ms < 10_000L -> "%.1f s".format(ms / 1_000.0)
    else -> "${ms / 1_000L} s"
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
    val actions = listOf("Blinker links", "Blinker rechts", "Licht", "Bremse", "Fahrmodus", "Gas", "Rekuperation", "Laden", "Schloss", "Unbekannt")
    var expanded by remember { mutableStateOf(false) }
    Card(shape = RoundedCornerShape(22.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("🔬 Decoder Lab", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
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
                Text("${it.characteristic} • Byte ${it.byteIndex}: ${it.beforeValue} → ${it.activeValue} • ${it.score}%", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun ChannelCard(channel: BleChannelState, now: Long) {
    val packetAge = if (channel.lastSeenMs > 0L) (now - channel.lastSeenMs).coerceAtLeast(0L) else null
    Card(shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("${channel.channel} • ${channel.title}", fontWeight = FontWeight.Bold)
                Text(channel.knowledge, style = MaterialTheme.typography.labelMedium)
            }
            Text(channel.meaning, style = MaterialTheme.typography.bodySmall)
            Text("Pakete: ${channel.packetCount} • zuletzt vor ${packetAge?.let(::formatPacketAge) ?: "–"} • geändert: ${channel.changedBytes}")
            Text(channel.hex, style = MaterialTheme.typography.bodySmall)
        }
    }
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

@Composable
private fun RawDataCard(state: ScooterState) {
    Card(shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text("Letztes BLE-Paket", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("Kanal: ${state.lastCharacteristic.ifBlank { "–" }} • Geändert: ${state.lastChangedBytes}")
            Text(state.lastRawHex.ifBlank { "Noch keine Daten empfangen" }, style = MaterialTheme.typography.bodySmall)
            Text("Echtzeit-Test verändert keine Scooter-Einstellungen. Motor-Tuning sendet nur nach separater Sicherheitsbestätigung.")
        }
    }
}

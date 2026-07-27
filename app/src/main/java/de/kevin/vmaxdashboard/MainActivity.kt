@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package de.kevin.vmaxdashboard

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
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

    Scaffold(topBar = { TopAppBar(title = { Text("VMAX Dashboard • Live AI 6.3") }) }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(vertical = 14.dp)
        ) {
            item { StatusCard(state) }
            item {
                InfoCard(
                    "Decoder nach zwei Prüfstandstests",
                    "Akku ist bestätigt. Fahrwert, Motorlast und Zubehör werden zusätzlich als RAW angezeigt. Spannung/Strom bleiben aus, bis die Skalierung sicher bestätigt ist."
                )
            }
            item { IndicatorDashboard(state) }
            item { SectionTitle("Fahrt") }
            item {
                MetricRow(
                    "Tempo Kandidat", state.speedKmh?.let { "%.1f km/h".format(it) } ?: "–",
                    "Fahrwert RAW", state.driveRaw?.toString() ?: "–"
                )
            }
            item {
                MetricRow(
                    "Kilometerstand", state.odometerKm?.let { "%.2f km".format(it) } ?: "wird erkannt",
                    "Signal", state.rssi?.let { "$it dBm" } ?: "–"
                )
            }

            item { SectionTitle("Akku & Technik") }
            item {
                MetricRow(
                    "Akku", state.batteryPercent?.let { "$it %" } ?: "–",
                    "Akku/Last RAW", state.batteryStateRaw?.toString() ?: "–"
                )
            }
            item {
                MetricRow(
                    "Motorlast RAW", state.motorLoadRaw?.toString() ?: "–",
                    "Motor-Temp.", state.motorTemperatureC?.let { "%.1f °C".format(it) } ?: "noch unbekannt"
                )
            }
            item {
                MetricRow(
                    "Akku-Temp.", state.batteryTemperatureC?.let { "%.1f °C".format(it) } ?: "wird erkannt",
                    "Pakete/s", "%.1f".format(state.packetsPerSecond)
                )
            }
            item {
                MetricRow(
                    "Zubehör RAW 0", state.accessoryByte0?.toString() ?: "–",
                    "Zubehör RAW 3", state.accessoryByte3?.toString() ?: "–"
                )
            }

            item { SectionTitle("Zubehör & Status") }
            item { AccessoryCard(state) }

            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = { connect() }, enabled = !state.scanning && !state.connected, modifier = Modifier.weight(1f)) {
                        Text(if (state.scanning) "Suche …" else "Verbinden")
                    }
                    OutlinedButton(onClick = { manager.disconnect() }, enabled = state.connected || state.scanning, modifier = Modifier.weight(1f)) {
                        Text("Trennen")
                    }
                }
            }

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
                    onActive = { manager.startLabAction() },
                    onFinish = { manager.finishLab() }
                )
            }

            item { SectionTitle("Bekannte Kanäle & Live-Analyse") }
            if (state.channels.isEmpty()) {
                item { InfoCard("Noch keine BLE-Daten", "Nach der Verbindung erscheinen hier alle Kanäle mit bekannter Bedeutung, Rohbytes, Paketanzahl und geänderten Bytepositionen.") }
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

@Composable private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
}

@Composable private fun StatusCard(state: ScooterState) {
    Card(shape = RoundedCornerShape(22.dp)) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(state.status, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("${state.deviceName} • Modellprofil wird automatisch gelernt")
            Text(if (state.connected) "● Bluetooth verbunden" else "○ Bluetooth nicht verbunden")
            Text("Analyse: ${state.analysisPhase} • ${state.channels.size} Kanäle")
            Text("Live: %.1f Pakete/s • Lernkandidaten: ${state.learningProfileCount} • Messfahrten: ${state.sessionHistoryCount}".format(state.packetsPerSecond), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable private fun IndicatorDashboard(state: ScooterState) {
    Card(shape = RoundedCornerShape(22.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 18.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(if (state.leftIndicator) "◀" else "◁", style = MaterialTheme.typography.displaySmall)
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(state.speedKmh?.let { "%.1f".format(it) } ?: "—", style = MaterialTheme.typography.displayMedium, fontWeight = FontWeight.Black)
                Text("km/h")
                Text("Live-Telemetrie")
            }
            Text(if (state.rightIndicator) "▶" else "▷", style = MaterialTheme.typography.displaySmall)
        }
    }
}

@Composable private fun MetricRow(title1: String, value1: String, title2: String, value2: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        MetricCard(title1, value1, Modifier.weight(1f))
        MetricCard(title2, value2, Modifier.weight(1f))
    }
}

@Composable private fun MetricCard(title: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier = modifier, shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.fillMaxWidth().padding(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(6.dp))
            Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable private fun AccessoryCard(state: ScooterState) {
    Card(shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Erkannte Zustände", fontWeight = FontWeight.Bold)
            Text("Blinker links: ${yesNoUnknown(state.leftIndicator, false)}")
            Text("Blinker rechts: ${yesNoUnknown(state.rightIndicator, false)}")
            Text("Licht: ${yesNoUnknown(state.lightOn, false)}")
            Text("Bremse: ${yesNoUnknown(state.brakeActive, false)}")
            Text("Schloss: ${state.lockActive?.let { if (it) "aktiv" else "offen" } ?: "wird erkannt"}")
            Text("Laden: ${state.charging?.let { if (it) "ja" else "nein" } ?: "wird erkannt"}")
            Text("Zubehörkanäle 1514–1518 werden vollständig protokolliert.", style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun yesNoUnknown(value: Boolean, confirmed: Boolean): String =
    if (!confirmed) "wird erkannt" else if (value) "aktiv" else "aus"

@Composable private fun MeasurementCard(
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
    val timer = formatElapsed(elapsed)
    val markers = listOf("Stillstand", "Anfahren", "Langsam", "Vollgas", "Rollen", "Bremse", "Licht", "Links", "Rechts", "Fahrmodus", "Laden", "Sonstiges")

    Card(shape = RoundedCornerShape(22.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("● Messfahrt-Aufnahme", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("Für normale Tests nur diesen Bereich verwenden. Der Decoder Lab darunter ist optional und muss nicht gleichzeitig laufen.")
            Text("Speicherort: Downloads/VMAXDashboard/Messfahrt_Datum_Uhrzeit", style = MaterialTheme.typography.bodySmall)

            val statusText = when {
                state.recordingPaused -> "⏸ AUFNAHME PAUSIERT"
                state.recordingActive -> "● AUFNAHME LÄUFT"
                state.connected -> "✓ BEREIT ZUR AUFNAHME"
                else -> "○ ZUERST SCOOTER VERBINDEN"
            }
            val statusContainer = when {
                state.recordingPaused -> MaterialTheme.colorScheme.tertiaryContainer
                state.recordingActive -> MaterialTheme.colorScheme.errorContainer
                state.connected -> MaterialTheme.colorScheme.primaryContainer
                else -> MaterialTheme.colorScheme.surfaceVariant
            }
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                color = statusContainer
            ) {
                Text(
                    statusText,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                    fontWeight = FontWeight.Black
                )
            }

            Text(timer, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Black)
            Text("Pakete: ${state.recordingPacketCount} • Marker: ${state.markerCount} • Letzter Marker: ${state.lastMarker.ifBlank { "–" }}")

            if (!state.recordingActive) {
                Button(
                    onClick = onStart,
                    enabled = state.connected,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 58.dp)
                ) {
                    Text(if (state.connected) "● MESSFAHRT STARTEN" else "ZUERST VERBINDEN", fontWeight = FontWeight.Black)
                }
                if (!state.connected) {
                    Text("Der Button wird nach erfolgreicher Bluetooth-Verbindung aktiv.", style = MaterialTheme.typography.bodySmall)
                }
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = onPause, modifier = Modifier.weight(1f).heightIn(min = 52.dp)) {
                        Text(if (state.recordingPaused) "▶ FORTSETZEN" else "⏸ PAUSE", fontWeight = FontWeight.Bold)
                    }
                    Button(onClick = onStop, modifier = Modifier.weight(1f).heightIn(min = 52.dp)) {
                        Text("■ STOP & SPEICHERN", fontWeight = FontWeight.Bold)
                    }
                }
                Text("MARKER – gedrückter Button bleibt sichtbar markiert", fontWeight = FontWeight.Bold)
                markers.chunked(3).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        row.forEach { marker ->
                            val selected = lastPressedMarker == marker || state.lastMarker == marker
                            if (selected) {
                                Button(
                                    onClick = { lastPressedMarker = marker; onMarker(marker) },
                                    modifier = Modifier.weight(1f).heightIn(min = 46.dp),
                                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp)
                                ) {
                                    Text("✓ $marker", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Black)
                                }
                            } else {
                                FilledTonalButton(
                                    onClick = { lastPressedMarker = marker; onMarker(marker) },
                                    modifier = Modifier.weight(1f).heightIn(min = 46.dp),
                                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp)
                                ) {
                                    Text(marker, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                        repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }

            OutlinedButton(onClick = onExport, enabled = state.packetTotal > 0 && !state.recordingActive, modifier = Modifier.fillMaxWidth()) {
                Text("Aktuelle Live-Daten zusätzlich als CSV")
            }
            if (state.lastExportMessage.isNotBlank()) Text(state.lastExportMessage, style = MaterialTheme.typography.bodySmall)
            if (state.lastSessionFolder.isNotBlank()) Text("Letzte Messfahrt: ${state.lastSessionFolder}", style = MaterialTheme.typography.bodySmall)
            if (state.autoAnalysisFindings.isNotEmpty()) {
                HorizontalDivider()
                Text("Automatische Analyse – beste Treffer", fontWeight = FontWeight.Bold)
                state.autoAnalysisFindings.take(8).forEach { finding ->
                    Text(finding.description, style = MaterialTheme.typography.bodySmall)
                }
            }
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

@Composable private fun ChannelCard(channel: BleChannelState) {
    Card(shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("${channel.channel} • ${channel.title}", fontWeight = FontWeight.Bold)
                Text(channel.knowledge, style = MaterialTheme.typography.labelMedium)
            }
            Text(channel.meaning, style = MaterialTheme.typography.bodySmall)
            Text("Pakete: ${channel.packetCount} • Geänderte Bytes: ${channel.changedBytes}")
            Text(channel.hex, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable private fun InfoCard(title: String, text: String) {
    Card(shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(title, fontWeight = FontWeight.Bold)
            Text(text, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable private fun DecoderLabCard(
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
            Text("Optionaler Einzeltest. Nicht gleichzeitig mit der Messfahrt nötig. Vergleicht stabile Bytes vor und während genau einer Aktion.")
            ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = !expanded }) {
                OutlinedTextField(
                    value = selectedAction, onValueChange = {}, readOnly = true,
                    label = { Text("Test") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                    modifier = Modifier.menuAnchor().fillMaxWidth()
                )
                ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
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
            AnimatedVisibility(state.labCandidates.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text("Beste Treffer", fontWeight = FontWeight.Bold)
                    state.labCandidates.take(12).forEach {
                        Text("${it.characteristic} • Byte ${it.byteIndex}: ${it.beforeValue} → ${it.activeValue} • ${it.score}%")
                    }
                }
            }
        }
    }
}

@Composable private fun RawDataCard(state: ScooterState) {
    Card(shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text("Letztes BLE-Paket", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("Kanal: ${state.lastCharacteristic.ifBlank { "–" }} • Geändert: ${state.lastChangedBytes}")
            Text(state.lastRawHex.ifBlank { "Noch keine Daten empfangen" }, style = MaterialTheme.typography.bodySmall)
            Text("Nur Diagnose: Die App sendet keine Steuer- oder Tuningbefehle.")
        }
    }
}

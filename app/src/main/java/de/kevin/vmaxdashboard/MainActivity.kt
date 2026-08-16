@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package de.kevin.vmaxdashboard

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
    val context = LocalContext.current
    val githubSync = remember(context) { GitHubTelemetrySync.get(context.applicationContext) }
    var githubSnapshot by remember { mutableStateOf(githubSync.snapshot()) }
    var aiProfile by remember { mutableStateOf(AdaptiveDecoderRuntime.snapshot()) }
    var expertMode by remember { mutableStateOf(true) }
    var selectedAction by remember { mutableStateOf("Bremse") }
    var chargeMode by remember { mutableStateOf(false) }
    var chargeStartedAt by remember { mutableLongStateOf(0L) }
    var lastRealBattery by remember { mutableStateOf<Int?>(null) }
    var lastRealVoltage by remember { mutableStateOf<Double?>(null) }
    var lastRealValueAt by remember { mutableLongStateOf(0L) }
    var previousConnected by remember { mutableStateOf(false) }

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

    fun marker(label: String, requireConnection: Boolean = true) {
        scope.launch {
            if (!state.recordingActive && state.connected) {
                manager.startMeasurement()
                delay(150)
            }
            if (manager.state.value.recordingActive && (!requireConnection || manager.state.value.connected)) {
                manager.addMeasurementMarker(label)
            }
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            githubSnapshot = githubSync.snapshot()
            aiProfile = AdaptiveDecoderRuntime.snapshot()
            delay(1_000)
        }
    }

    LaunchedEffect(state.batteryPercent, state.voltageV, state.lastPacketAt) {
        if (state.connected && state.lastPacketAt > 0L) {
            state.batteryPercent?.let { lastRealBattery = it }
            state.voltageV?.let { lastRealVoltage = it }
            lastRealValueAt = state.lastPacketAt
        }
    }

    LaunchedEffect(state.connected) {
        if (state.connected) {
            delay(700)
            if (!manager.state.value.recordingActive) manager.startMeasurement()
            delay(1_400)
            for (attempt in 0 until 8) {
                if (gattScanner.scanAndRead()) break
                if (attempt < 7) delay(750)
            }
            if (chargeMode && !previousConnected) marker("BLE beim Laden wieder verbunden", false)
        } else {
            gattScanner.reset()
            if (chargeMode && previousConnected && manager.state.value.recordingActive) {
                manager.addMeasurementMarker("BLE beim Laden getrennt")
            }
        }
        previousConnected = state.connected
    }

    LaunchedEffect(chargeMode, state.connected) {
        while (chargeMode && !manager.state.value.connected) {
            if (!manager.state.value.scanning && manager.hasRequiredPermissions()) manager.startScan()
            delay(15_000)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("VMAX Dashboard • ${BuildConfig.VERSION_NAME}")
                        Text(
                            "Original SDK Live + Adaptive Decoder AI • Build ${BuildConfig.VERSION_NAME}",
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(vertical = 14.dp)
        ) {
            item { StatusCard(state, gattState, chargeMode, aiProfile) }
            item { SpeedCard(state) }
            item { SectionTitle("Bestätigte Fahrdaten") }
            item { MetricRow("Akku", state.batteryPercent?.let { "$it %" } ?: "–", "Kilometer", state.odometerKm?.let { "%.1f km".format(it) } ?: "–") }
            item { MetricRow("Spannung", state.voltageV?.let { "%.2f V".format(it) } ?: "–", "Strom", state.currentA?.let { "%.2f A".format(it) } ?: "–") }
            item { MetricRow("Leistung direkt", state.sdkDirectPowerW?.let { "%.0f W".format(it) } ?: state.motorLoadRaw?.let { "$it W" } ?: "–", "Leistung V×A", state.currentPowerW?.let { "%.0f W".format(it) } ?: "–") }
            item {
                MetricRow(
                    "Restreichweite (SDK)",
                    state.sdkRemainingRangeKm?.let { "%.0f km".format(it) } ?: "–",
                    "Startmodus",
                    VmaxStartMode.fromRaw(state.startModeRaw)?.label ?: "–"
                )
            }
            item { LightModeCard(state) }
            item { StartModeCard(state, manager::setStartMode) }
            item { OriginalSdkRealtimeCard(state) }
            item { AdaptiveConfidenceCard(state, aiProfile) }

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

            item {
                ChargeDiagnosticCard(
                    state = state,
                    active = chargeMode,
                    startedAt = chargeStartedAt,
                    lastBattery = lastRealBattery,
                    lastVoltage = lastRealVoltage,
                    lastValueAt = lastRealValueAt,
                    onPlugIn = {
                        chargeMode = true
                        chargeStartedAt = System.currentTimeMillis()
                        marker("Ladegerät einstecken", false)
                    },
                    onPower = { marker("Power beim Laden", false) },
                    onUnplug = {
                        marker("Ladegerät abziehen", false)
                        chargeMode = false
                        if (!state.connected && !state.scanning) connect()
                    }
                )
            }

            item { AutoRecordingCard(state, manager::stopMeasurementAndExport, manager::exportSessionCsv) }
            item {
                GitHubSyncCard(
                    snapshot = githubSnapshot,
                    aiProfile = aiProfile,
                    onOpen = {
                        context.startActivity(Intent(context, GitHubSyncActivity::class.java))
                    }
                )
            }
            if (aiProfile.confirmedRuleCount > 0) {
                item { AdaptiveLiveCard(state, aiProfile) }
            }
            item { SectionTitle("Direkttests – einmal drücken") }
            item { DirectMarkerCard(state) { marker(it) } }
            item { GattSummaryCard(gattState, { gattScanner.scanAndRead() }, state.connected) }

            item {
                Card(shape = RoundedCornerShape(18.dp)) {
                    Row(
                        Modifier.fillMaxWidth().padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Expertenmodus", fontWeight = FontWeight.Bold)
                            Text("GATT-Struktur, RAW-Kanäle und Vergleichstest")
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
                        state,
                        selectedAction,
                        { selectedAction = it },
                        { manager.startLabBaseline(selectedAction) },
                        manager::startLabAction,
                        manager::finishLab
                    )
                }
                item { SectionTitle("Alle empfangenen BLE-Kanäle") }
                if (state.channels.isEmpty()) {
                    item { InfoCard("Noch keine Live-Daten", "Notify-/Indicate-Kanäle erscheinen nach der Verbindung.") }
                } else {
                    items(state.channels) { ChannelCard(it) }
                }
                item { RawDataCard(state) }
            }

            item { SectionTitle("Automatische Lernanalyse") }
            if (state.autoAnalysisFindings.isEmpty()) {
                item { InfoCard("Decoder AI läuft", "Fahrt, Stillstand, Ladeabbrüche und Marker werden dauerhaft aufgezeichnet.") }
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
private fun AdaptiveConfidenceCard(state: ScooterState, aiProfile: AdaptiveProfileSnapshot) {
    val adaptive = remember(state.rawPackets, aiProfile.revision, aiProfile.generatedAtMs) {
        AdaptiveDecoderRuntime.decodePackets(state.rawPackets)
    }
    val signalRows = listOf(
        "speedKmh" to "Geschwindigkeit",
        "batteryPercent" to "Akku",
        "voltageV" to "Spannung",
        "currentA" to "Strom",
        "powerW" to "Leistung",
        "motorTemperatureC" to "Motor-Temperatur",
        "batteryTemperatureC" to "Akku-Temperatur",
        "tripDistanceKm" to "Strecke",
        "odometerKm" to "Kilometerstand",
        "charging" to "Laden",
        "lightOn" to "Licht",
        "brakeActive" to "Bremse",
        "leftIndicator" to "Blinker links",
        "rightIndicator" to "Blinker rechts",
        "lockActive" to "Sperre"
    ).mapNotNull { (signal, label) ->
        val confidence = adaptive.signalConfidence[signal] ?: return@mapNotNull null
        val source = adaptive.signalSources[signal] ?: "Unbekannt"
        val channel = adaptive.signalChannels[signal] ?: "–"
        Triple(label, confidence, "$source • $channel")
    }

    Card(shape = RoundedCornerShape(22.dp)) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                "KI-Vertrauen & Herkunft",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Text(
                "${aiProfile.source} • ${aiProfile.confidenceSummary}",
                style = MaterialTheme.typography.bodySmall
            )
            if (signalRows.isEmpty()) {
                Text("Noch keine vertrauensbewerteten KI-Signale sichtbar.")
            } else {
                signalRows.forEach { (label, confidence, origin) ->
                    ConfidenceRow(label = label, confidence = confidence, origin = origin)
                }
            }
        }
    }
}

@Composable
private fun ConfidenceRow(label: String, confidence: String, origin: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("$label • ${confidence.uppercase()}", fontWeight = FontWeight.SemiBold)
        Text(origin, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun ChargeDiagnosticCard(
    state: ScooterState,
    active: Boolean,
    startedAt: Long,
    lastBattery: Int?,
    lastVoltage: Double?,
    lastValueAt: Long,
    onPlugIn: () -> Unit,
    onPower: () -> Unit,
    onUnplug: () -> Unit
) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(active, state.connected, lastValueAt) {
        while (active) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }
    val age = lastValueAt.takeIf { it > 0 }?.let { ((now - it) / 1_000L).coerceAtLeast(0L) }
    val duration = if (active && startedAt > 0L) now - startedAt else 0L
    val status = when {
        !active -> "Bereit"
        state.connected && state.packetsPerSecond > 0.1 -> "BLE verbunden – Live-Daten vorhanden"
        state.connected -> "BLE verbunden – Telemetrie pausiert"
        state.scanning -> "Controller offline – automatische Suche läuft"
        else -> "Controller offline – nächster Scan folgt automatisch"
    }
    Card(shape = RoundedCornerShape(22.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("🔌 Lade-Diagnose", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(status)
            if (active) Text("Ladetest: ${formatElapsed(duration)}")
            Text("Letzter echter Akkuwert: ${lastBattery?.let { "$it %" } ?: "–"}")
            Text("Letzte echte Spannung: ${lastVoltage?.let { "%.2f V".format(it) } ?: "–"}")
            if (age != null) Text("Letzte echte Messung vor ${age}s")
            if (!active) {
                Button(onClick = onPlugIn, enabled = state.connected, modifier = Modifier.fillMaxWidth()) { Text("1. LADEGERÄT EINSTECKEN") }
            } else {
                Button(onClick = onPower, modifier = Modifier.fillMaxWidth()) { Text("2. POWER BEIM LADEN") }
                OutlinedButton(onClick = onUnplug, modifier = Modifier.fillMaxWidth()) { Text("3. LADEGERÄT ABZIEHEN") }
            }
            Text("Offline-Werte werden nicht geschätzt. Die Aufnahme und Marker bleiben auch bei BLE-Abschaltung aktiv.", style = MaterialTheme.typography.bodySmall)
        }
    }
}

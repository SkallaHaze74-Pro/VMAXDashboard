@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package de.kevin.vmaxdashboard

import android.Manifest
import android.content.Intent
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
            gattScanner.scanAndRead()
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
                        Text("VMAX Dashboard • Version 7.6")
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
            item { LightModeCard(state) }
            item { OriginalSdkRealtimeCard(state) }

            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = ::connect, enabled = !state.scanning && !state.connected, modifier = Modifier.weight(1f)) {
                        Text(if (state.scanning) "Suche …" else "Verbinden")
                    }
                    OutlinedButton(onClick = manager::disconnect, enabled = state.connected || state.scanning, modifier = Modifier.weight(1f)) { Text("Trennen") }
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
            item { GattSummaryCard(gattState, gattScanner::scanAndRead, state.connected) }

            item {
                Card(shape = RoundedCornerShape(18.dp)) {
                    Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
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
                if (gattState.entries.isEmpty()) item { InfoCard("Noch keine GATT-Daten", "Nach der Verbindung startet der sichere READ-Scan automatisch.") }
                else items(gattState.entries) { GattEntryCard(it) }

                item {
                    DecoderLabCard(
                        state, selectedAction, { selectedAction = it },
                        { manager.startLabBaseline(selectedAction) }, manager::startLabAction, manager::finishLab
                    )
                }
                item { SectionTitle("Alle empfangenen BLE-Kanäle") }
                if (state.channels.isEmpty()) item { InfoCard("Noch keine Live-Daten", "Notify-/Indicate-Kanäle erscheinen nach der Verbindung.") }
                else items(state.channels) { ChannelCard(it) }
                item { RawDataCard(state) }
            }

            item { SectionTitle("Automatische Lernanalyse") }
            if (state.autoAnalysisFindings.isEmpty()) item { InfoCard("Decoder AI läuft", "Fahrt, Stillstand, Ladeabbrüche und Marker werden dauerhaft aufgezeichnet.") }
            else items(state.autoAnalysisFindings.take(20)) { InfoCard(it.marker, it.description) }

            item { SectionTitle("Protokoll") }
            items(state.log) { Text(it, style = MaterialTheme.typography.bodySmall); HorizontalDivider() }
        }
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
        while (active) { now = System.currentTimeMillis(); delay(1_000) }
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

@Composable private fun SectionTitle(text: String) = Text(text, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)

@Composable
private fun StatusCard(state: ScooterState, gatt: GattScanState, chargeMode: Boolean, aiProfile: AdaptiveProfileSnapshot) {
    Card(shape = RoundedCornerShape(22.dp)) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(state.status, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("Version 7.6 • Build ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodySmall)
            Text(if (state.connected) "● Bluetooth verbunden" else "○ Bluetooth nicht verbunden")
            Text(if (state.recordingActive) "● Auto-KI-Aufnahme läuft" else "○ Aufnahme wartet")
            Text("Original-SDK live: ${state.sdkLiveFieldCount} Felder • Decoder AI: ${aiProfile.confirmedRuleCount}/${aiProfile.ruleCount} bestätigt", style = MaterialTheme.typography.bodySmall)
            if (chargeMode) Text("🔌 Lademodus aktiv – Auto-Reconnect alle 15 Sekunden")
            Text("%.1f Pakete/s • ${state.packetTotal} Pakete • ${state.channels.size} Live-Kanäle".format(state.packetsPerSecond))
            Text("GATT: ${gatt.serviceCount} Dienste • ${gatt.characteristicCount} Characteristics • ${gatt.readableCount} lesbar")
        }
    }
}

@Composable
private fun SpeedCard(state: ScooterState) {
    val max150d = decode150dStatistic(state.rawPackets["150D"], 0)
    val average150d = decode150dStatistic(state.rawPackets["150D"], 2)
    Card(shape = RoundedCornerShape(22.dp)) {
        Column(Modifier.fillMaxWidth().padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(state.speedKmh?.let { "%.1f".format(it) } ?: "—", style = MaterialTheme.typography.displayMedium, fontWeight = FontWeight.Black)
            Text("km/h • Original-libble + BT638")
            Text(
                "Fahrstatistik 150D: Max ${max150d?.let { "%.1f".format(it) } ?: "–"} • " +
                    "Ø ${average150d?.let { "%.1f km/h".format(it) } ?: "–"}"
            )
            Text("150D bleibt im Stillstand gespeichert und ist kein Live-Tempo.", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun LightModeCard(state: ScooterState) {
    val light = when (state.accessoryByte0) { 0 -> "AUS"; 1 -> "AN"; null -> "–"; else -> "RAW ${state.accessoryByte0}" }
    val mode = when (state.accessoryByte3) { 1 -> "ECO"; 2 -> "SPORT"; null -> "–"; else -> "RAW ${state.accessoryByte3}" }
    InfoCard("Licht & Fahrmodus", "💡 Licht: $light • RAW 1508/0: ${state.accessoryByte0 ?: "–"}\n⚡ Fahrmodus: $mode • RAW 1508/3: ${state.accessoryByte3 ?: "–"}\nBT638 bestätigt: 0/1 = AUS/AN und 1/2 = ECO/SPORT.")
}

@Composable
private fun OriginalSdkRealtimeCard(state: ScooterState) {
    fun d(value: Double?, unit: String, digits: Int = 2): String =
        value?.let { "% .${digits}f".format(it).trim() + " " + unit } ?: "–"
    fun i(value: Int?, unit: String = ""): String = value?.let { "$it${if (unit.isBlank()) "" else " $unit"}" } ?: "–"

    Card(shape = RoundedCornerShape(22.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("⚡ Original-SDK Echtzeit", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("libble Ground Truth • ${state.sdkLiveFieldCount} aktuell dekodierbare Felder", style = MaterialTheme.typography.bodySmall)
            Text("1505 Leistung A/B: ${d(state.sdkPerformancePowerAW, "W", 1)} • ${d(state.sdkPerformancePowerBW, "W", 1)}")
            Text("1505 Drehmoment: ${d(state.sdkPerformanceTorqueNm, "Nm", 2)} • RPM: ${i(state.sdkPerformanceRpm)}")
            Text("1505 Weg RAW: ${i(state.sdkPerformanceDistanceRaw)} • 1506 Zähler RAW: ${state.sdkOperatingCounterRaw ?: "–"}")
            Text("1509 Akku-Temp: ${d(state.resolvedBatteryTemperatureC, "°C", 1)} • 2. Strom: ${d(state.sdkSecondaryBatteryCurrentA, "A", 3)}")
            Text("1509 direkte Leistung: ${d(state.sdkDirectPowerW, "W", 0)}")
            Text("150A Motorstrom: ${d(state.sdkMotorCurrentA, "A", 3)} • Motorspannung: ${d(state.sdkMotorVoltageV, "V", 3)}")
            Text("150A Motor-RPM: ${i(state.sdkMotorRpm)} • Drehmoment: ${d(state.sdkMotorTorqueNm, "Nm", 2)}")
            Text("150A Motortemperatur: ${d(state.resolvedMotorTemperatureC, "°C", 1)}")
            Text("Assistenz/Fahrstufe RAW: ${i(state.sdkAssistanceLevelRaw)}")
            Text(
                "Leistung A/B bleiben absichtlich neutral benannt, bis der Original-App↔BT638-Vergleich eindeutig Motor- und Tretleistung zuordnet. 0xFFFF-Felder werden nicht erfunden.",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun AutoRecordingCard(state: ScooterState, onStop: () -> Unit, onExport: () -> Unit) {
    Card(shape = RoundedCornerShape(22.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Automatische Daueraufnahme", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("Pakete: ${state.recordingPacketCount} • Marker: ${state.markerCount}")
            Button(onClick = onStop, enabled = state.recordingActive, modifier = Modifier.fillMaxWidth()) { Text("STOPPEN, ANALYSIEREN & SPEICHERN") }
            OutlinedButton(onClick = onExport, enabled = state.packetTotal > 0, modifier = Modifier.fillMaxWidth()) { Text("Rohdaten zusätzlich als CSV") }
            if (state.lastExportMessage.isNotBlank()) Text(state.lastExportMessage, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun GitHubSyncCard(snapshot: GitHubSyncSnapshot, aiProfile: AdaptiveProfileSnapshot, onOpen: () -> Unit) {
    val status = when {
        !snapshot.tokenConfigured -> "Noch nicht eingerichtet"
        !snapshot.enabled -> "Eingerichtet • Auto-Upload ist aus"
        snapshot.pendingBundles > 0 -> "Auto-Upload aktiv • ${snapshot.pendingBundles} Fahrt(en) warten"
        else -> "✓ Auto-Upload aktiv • GitHub aktuell"
    }
    Card(shape = RoundedCornerShape(22.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("☁ GitHub Fahrdaten & Decoder AI", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(status)
            Text("KI: ${aiProfile.confirmedRuleCount}/${aiProfile.ruleCount} Regeln bestätigt • ${aiProfile.source}")
            if (snapshot.lastStatus.isNotBlank()) {
                Text(snapshot.lastStatus, style = MaterialTheme.typography.bodySmall)
            }
            Button(onClick = onOpen, modifier = Modifier.fillMaxWidth()) {
                Text(if (snapshot.tokenConfigured) "GITHUB SYNC & DECODER AI" else "GITHUB SYNC EINRICHTEN")
            }
            Text("Kein Scooter und keine Bluetooth-Verbindung für die Einstellungen nötig. Bestätigte KI-Regeln werden read-only in die Live-Auswertung übernommen.", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun AdaptiveLiveCard(state: ScooterState, aiProfile: AdaptiveProfileSnapshot) {
    fun binary(value: Boolean): String = if (!state.connected) "–" else if (value) "AN" else "AUS"
    Card(shape = RoundedCornerShape(22.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("🧠 KI-gelernte Live-Signale", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            if ("brakeActive" in aiProfile.signals) Text("🛑 Bremse: ${binary(state.brakeActive)}")
            if ("leftIndicator" in aiProfile.signals) Text("⬅ Blinker links: ${binary(state.leftIndicator)}")
            if ("rightIndicator" in aiProfile.signals) Text("➡ Blinker rechts: ${binary(state.rightIndicator)}")
            if ("lightOn" in aiProfile.signals) Text("💡 Licht KI: ${binary(state.lightOn)}")
            if ("charging" in aiProfile.signals) Text("🔌 Laden: ${state.charging?.let { binary(it) } ?: "–"}")
            if ("lockActive" in aiProfile.signals) Text("🔒 Sperre: ${state.lockActive?.let { binary(it) } ?: "–"}")
            val numeric = aiProfile.signals.filter { it !in setOf("brakeActive", "leftIndicator", "rightIndicator", "lightOn", "charging", "lockActive") }
            if (numeric.isNotEmpty()) Text("Zusätzliche Messwerte: ${numeric.sorted().joinToString(", ")}", style = MaterialTheme.typography.bodySmall)
            Text("Nur bestätigte Regeln werden angezeigt; unsichere Treffer bleiben Lernkandidaten.", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun DirectMarkerCard(state: ScooterState, onMarker: (String) -> Unit) {
    val markers = listOf("Stillstand", "Bremse im Stand", "Schieben frei", "Bremse beim Schieben", "Licht AUS", "Licht AN", "ECO", "SPORT", "Blinker links", "Blinker rechts", "Anfahren", "Rollen", "Vollgas")
    Card(shape = RoundedCornerShape(22.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            markers.chunked(2).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { m -> Button(onClick = { onMarker(m) }, enabled = state.connected, modifier = Modifier.weight(1f)) { Text(m) } }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
            Text("Letzter Marker: ${state.lastMarker.ifBlank { "–" }}")
        }
    }
}

@Composable
private fun GattSummaryCard(state: GattScanState, onScan: () -> Unit, enabled: Boolean) {
    InfoCard("BLE-Explorer & sicherer READ-Scan", "${state.status}\nDienste ${state.serviceCount} • Characteristics ${state.characteristicCount} • READ ${state.readableCount} • gestartet ${state.startedReads}")
    OutlinedButton(onClick = onScan, enabled = enabled && !state.running, modifier = Modifier.fillMaxWidth()) { Text("ALLE SICHEREN READ-FELDER LESEN") }
}

@Composable private fun GattEntryCard(e: GattCharacteristicInfo) = InfoCard("${e.serviceUuid} → ${e.characteristicUuid}", "${e.properties}\n${if (e.lastReadStarted) "✓ READ gestartet" else if (e.readable) "READ verfügbar" else "Live/statisch"}")

@Composable
private fun ConfirmedRawCard(state: ScooterState) {
    val a = parseHex(state.rawPackets["1505"]); val b = parseHex(state.rawPackets["1502"]); val c = parseHex(state.rawPackets["150A"]); val d = parseHex(state.rawPackets["150D"])
    InfoCard("Bestätigte & starke RAW-Felder", "1505 A/B: ${u16be(a,0) ?: "–"} / ${u16be(a,2) ?: "–"}\n1505 Tempo: ${u16be(a,6) ?: "–"}\n150D Max/Ø RAW: ${u16be(d,0) ?: "–"} / ${u16be(d,2) ?: "–"}\n150A Last: ${u16be(c,0) ?: "–"}\n1502 A/B: ${u16be(b,0) ?: "–"} / ${u16be(b,6) ?: "–"}")
}

@Composable
private fun DecoderLabCard(state: ScooterState, selected: String, select: (String)->Unit, baseline:()->Unit, active:()->Unit, finish:()->Unit) {
    var expanded by remember { mutableStateOf(false) }
    val actions = listOf("Bremse", "Blinker links", "Blinker rechts", "Licht", "Fahrmodus", "Gas", "Rekuperation")
    Card(shape = RoundedCornerShape(18.dp)) { Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Optionaler 3-Schritt-Test", fontWeight = FontWeight.Bold)
        Box { OutlinedButton(onClick={expanded=true}, modifier=Modifier.fillMaxWidth()){Text(selected)}; DropdownMenu(expanded, {expanded=false}) { actions.forEach { a -> DropdownMenuItem({Text(a)}, {select(a);expanded=false}) } } }
        Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.spacedBy(6.dp)) {
            Button(baseline, enabled=state.connected&&!state.labRunning, modifier=Modifier.weight(1f)){Text("1 Ruhe")}
            Button(active, enabled=state.labRunning&&state.labPhase.startsWith("1/2"), modifier=Modifier.weight(1f)){Text("2 Aktion")}
            Button(finish, enabled=state.labRunning&&state.labPhase.startsWith("2/2"), modifier=Modifier.weight(1f)){Text("3 Fertig")}
        }
    } }
}

@Composable
private fun ChannelCard(c: BleChannelState) = InfoCard("${c.channel} • ${c.title}", "${c.knowledge} • Pakete ${c.packetCount} • Δ ${c.changedBytes}\n${c.hex}")

@Composable private fun RawDataCard(state: ScooterState) = InfoCard("Letztes BLE-Paket", "${state.lastCharacteristic.ifBlank { "–" }} • Δ ${state.lastChangedBytes}\n${state.lastRawHex.ifBlank { "Noch keine Daten" }}")

@Composable
private fun MetricRow(t1:String,v1:String,t2:String,v2:String) { Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.spacedBy(10.dp)) { MetricCard(t1,v1,Modifier.weight(1f)); MetricCard(t2,v2,Modifier.weight(1f)) } }
@Composable private fun MetricCard(t:String,v:String,m:Modifier=Modifier) { Card(m, shape=RoundedCornerShape(18.dp)) { Column(Modifier.fillMaxWidth().padding(14.dp), horizontalAlignment=Alignment.CenterHorizontally) { Text(t); Text(v, fontWeight=FontWeight.Bold) } } }
@Composable private fun InfoCard(t:String,x:String) { Card(shape=RoundedCornerShape(18.dp)) { Column(Modifier.fillMaxWidth().padding(16.dp)) { Text(t,fontWeight=FontWeight.Bold); Text(x,style=MaterialTheme.typography.bodySmall) } } }
private fun decode150dStatistic(hex:String?, offset:Int):Double? { val r=u16be(parseHex(hex),offset)?:return null; return if(r==0xFFFF||r==0x8000)null else r/10.0 }
private fun parseHex(hex:String?):List<Int> = hex.orEmpty().split('-',' ',':').mapNotNull { it.trim().takeIf { x->x.length==2 }?.toIntOrNull(16) }
private fun u16be(b:List<Int>,i:Int):Int? = if(i<0||i+1>=b.size)null else (b[i] shl 8) or b[i+1]
private fun formatElapsed(ms:Long):String = "%02d:%02d:%02d".format(ms/3_600_000,(ms/60_000)%60,(ms/1_000)%60)

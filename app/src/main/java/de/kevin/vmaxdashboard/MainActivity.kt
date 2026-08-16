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
                Button(onClick = onPlugIn, enabled = state.connected, modifier = Modifier.fillMaxWidth()) {
                    Text("1. LADEGERÄT EINSTECKEN")
                }
            } else {
                Button(onClick = onPower, modifier = Modifier.fillMaxWidth()) {
                    Text("2. POWER BEIM LADEN")
                }
                OutlinedButton(onClick = onUnplug, modifier = Modifier.fillMaxWidth()) {
                    Text("3. LADEGERÄT ABZIEHEN")
                }
            }
            Text(
                "Offline-Werte werden nicht geschätzt. Die Aufnahme und Marker bleiben auch bei BLE-Abschaltung aktiv.",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun SectionTitle(text: String) =
    Text(text, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)

@Composable
private fun StatusCard(
    state: ScooterState,
    gatt: GattScanState,
    chargeMode: Boolean,
    aiProfile: AdaptiveProfileSnapshot
) {
    Card(shape = RoundedCornerShape(22.dp)) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(state.status, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("Build ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodySmall)
            Text(
                when {
                    state.telemetryReady -> "● Bluetooth verbunden • Telemetrie aktiv"
                    state.connected -> "◐ Bluetooth-Link verbunden • warte auf Telemetrie"
                    else -> "○ Bluetooth nicht verbunden"
                }
            )
            Text(if (state.recordingActive) "● Auto-KI-Aufnahme läuft" else "○ Aufnahme wartet")
            Text(
                "Original-SDK live: ${state.sdkLiveFieldCount} Felder • Decoder AI: " +
                    "${aiProfile.confirmedRuleCount}/${aiProfile.ruleCount} bestätigt",
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                "Profile: Cloud ${aiProfile.cloudRuleCount} • Lokal ${aiProfile.localRuleCount} • " +
                    aiProfile.confidenceSummary,
                style = MaterialTheme.typography.bodySmall
            )
            if (chargeMode) Text("🔌 Lademodus aktiv – Auto-Reconnect alle 15 Sekunden")
            Text(
                "%.1f Pakete/s • ${state.packetTotal} Pakete • ${state.channels.size} Live-Kanäle"
                    .format(state.packetsPerSecond)
            )
            Text(
                "GATT: ${gatt.serviceCount} Dienste • ${gatt.characteristicCount} Characteristics • " +
                    "${gatt.readableCount} lesbar"
            )
        }
    }
}

@Composable
private fun SpeedCard(state: ScooterState) {
    val max150d = decode150dStatistic(state.rawPackets["150D"], 0)
    val average150d = decode150dStatistic(state.rawPackets["150D"], 2)
    Card(shape = RoundedCornerShape(22.dp)) {
        Column(
            Modifier.fillMaxWidth().padding(18.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                state.speedKmh?.let { "%.1f".format(it) } ?: "—",
                style = MaterialTheme.typography.displayMedium,
                fontWeight = FontWeight.Black
            )
            Text("km/h • Original-libble + BT638")
            Text(
                "Fahrstatistik 150D: Max ${max150d?.let { "%.1f".format(it) } ?: "–"} • " +
                    "Ø ${average150d?.let { "%.1f km/h".format(it) } ?: "–"}"
            )
            Text(
                "150D bleibt im Stillstand gespeichert und ist kein Live-Tempo.",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun LightModeCard(state: ScooterState) {
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
        else -> "RAW ${state.accessoryByte3}"
    }
    InfoCard(
        "Licht & Fahrmodus",
        "💡 Licht: $light • RAW 1508/0: ${state.accessoryByte0 ?: "–"}\n" +
            "⚡ Fahrmodus: $mode • RAW 1508/3: ${state.accessoryByte3 ?: "–"}\n" +
            "BT638 bestätigt: 0/1 = AUS/AN und 1/2 = ECO/SPORT."
    )
}

@Composable
private fun StartModeCard(state: ScooterState, onSetMode: (VmaxStartMode) -> Unit) {
    val current = VmaxStartMode.fromRaw(state.startModeRaw)
    var safetyNowElapsedMs by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(state.connected, state.connectionEpoch, state.lastSpeedSampleElapsedRealtimeMs) {
        safetyNowElapsedMs = SystemClock.elapsedRealtime()
        if (state.connected && state.lastSpeedSampleElapsedRealtimeMs > 0L) {
            val ageMs = safetyNowElapsedMs - state.lastSpeedSampleElapsedRealtimeMs
            val untilStaleMs = StartModeWriteSafetyPolicy.MAX_SPEED_SAMPLE_AGE_MS - ageMs
            if (untilStaleMs >= 0L) {
                delay(untilStaleMs + 1L)
                safetyNowElapsedMs = SystemClock.elapsedRealtime()
            }
        }
    }
    val safetyBlock = StartModeWriteSafetyPolicy.blockReason(
        StartModeWriteSafetyInput(
            connected = state.connected,
            telemetryReady = state.telemetryReady,
            recordingActive = state.recordingActive,
            startModeBusy = state.startModeBusy,
            pendingStartModeWrite = false,
            legacyRouteConfirmed = state.startModeWriteAvailable,
            gattBusy = state.gattOperationBusy,
            speedKmh = state.speedKmh,
            speedSampleAtElapsedMs = state.lastSpeedSampleElapsedRealtimeMs,
            speedSampleConnectionEpoch = state.speedSampleConnectionEpoch,
            connectionEpoch = state.connectionEpoch,
            nowElapsedMs = safetyNowElapsedMs
        )
    )
    val safeToWrite = safetyBlock == null
    Card(shape = RoundedCornerShape(22.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("🛴 Anfahrmodus", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("Aktiv: ${current?.label ?: "noch nicht gelesen"} • ${state.startModeStatus}")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = { onSetMode(VmaxStartMode.ZERO_START) },
                    enabled = safeToWrite && current != VmaxStartMode.ZERO_START,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Zero-Start")
                }
                Button(
                    onClick = { onSetMode(VmaxStartMode.KICK_START) },
                    enabled = safeToWrite && current != VmaxStartMode.KICK_START,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Kick-Start")
                }
            }
            Text(
                when {
                    state.recordingActive -> "Zum Ändern zuerst die Messfahrt stoppen."
                    !state.startModeWriteAvailable ->
                        "Der Modus wird sicher aus 1508/11 angezeigt; Schreiben bleibt bei unbekannter Protokollroute gesperrt."
                    safetyBlock == StartModeWriteBlockReason.SPEED_NOT_AVAILABLE ||
                        safetyBlock == StartModeWriteBlockReason.SPEED_FROM_PREVIOUS_CONNECTION ||
                        safetyBlock == StartModeWriteBlockReason.SPEED_SAMPLE_STALE ->
                        "Ändern erst nach einer frischen 1505-Stillstandsmessung der aktuellen Verbindung."
                    safetyBlock == StartModeWriteBlockReason.GATT_BUSY ->
                        "Ein anderer BLE-Vorgang läuft; danach wird die Änderung wieder freigegeben."
                    else ->
                        "Änderung nur im Stillstand; danach bestätigt die App den Wert über 1508/11."
                },
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                "Kick-Start verlangt erst Anschieben/Antreten, bevor der Gashebel anfährt.",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun OriginalSdkRealtimeCard(state: ScooterState) {
    fun d(value: Double?, unit: String, digits: Int = 2): String =
        value?.let { "% .${digits}f".format(it).trim() + " " + unit } ?: "–"

    fun i(value: Int?, unit: String = ""): String =
        value?.let { "$it${if (unit.isBlank()) "" else " $unit"}" } ?: "–"

    Card(shape = RoundedCornerShape(22.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("⚡ Original-SDK Echtzeit", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(
                "libble Ground Truth • ${state.sdkLiveFieldCount} aktuell dekodierbare Felder",
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                "1505 Leistung A/B: ${d(state.sdkPerformancePowerAW, "W", 1)} • " +
                    d(state.sdkPerformancePowerBW, "W", 1)
            )
            Text(
                "1505 Drehmoment: ${d(state.sdkPerformanceTorqueNm, "Nm", 2)} • " +
                    "RPM: ${i(state.sdkPerformanceRpm)}"
            )
            Text(
                "1505 Restreichweite: ${d(state.sdkRemainingRangeKm, "km", 0)} • " +
                    "1506 Zähler RAW: ${state.sdkOperatingCounterRaw ?: "–"}"
            )
            Text(
                "1509 Akku-Temp: ${d(state.resolvedBatteryTemperatureC, "°C", 1)} • " +
                    "2. Strom: ${d(state.sdkSecondaryBatteryCurrentA, "A", 3)}"
            )
            Text("1509 direkte Leistung: ${d(state.sdkDirectPowerW, "W", 0)}")
            Text(
                "150A Motorstrom: ${d(state.sdkMotorCurrentA, "A", 3)} • " +
                    "Motorspannung: ${d(state.sdkMotorVoltageV, "V", 3)}"
            )
            Text(
                "150A Motor-RPM: ${i(state.sdkMotorRpm)} • " +
                    "Drehmoment: ${d(state.sdkMotorTorqueNm, "Nm", 2)}"
            )
            Text("150A Motortemperatur: ${d(state.resolvedMotorTemperatureC, "°C", 1)}")
            Text("Assistenz/Fahrstufe RAW: ${i(state.sdkAssistanceLevelRaw)}")
            Text(
                when {
                    state.sdkRemainingRangeKm != null ->
                        "Restreichweite ist der direkte Controllerwert aus 1505/10–11 in km – keine App-Schätzung."
                    state.connected && state.telemetryReady ->
                        "1505/10–11 liefert aktuell 0xFFFF; deshalb zeigt die App korrekt „–“ statt einer erfundenen Schätzung."
                    else ->
                        "Der direkte Restreichweitenwert aus 1505/10–11 erscheint, sobald der Controller einen gültigen Wert liefert."
                },
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
            Button(
                onClick = onStop,
                enabled = state.recordingActive,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("STOPPEN, ANALYSIEREN & SPEICHERN")
            }
            OutlinedButton(
                onClick = onExport,
                enabled = state.packetTotal > 0,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Rohdaten zusätzlich als CSV")
            }
            if (state.lastExportMessage.isNotBlank()) {
                Text(state.lastExportMessage, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun GitHubSyncCard(
    snapshot: GitHubSyncSnapshot,
    aiProfile: AdaptiveProfileSnapshot,
    onOpen: () -> Unit
) {
    val status = when {
        !snapshot.tokenConfigured -> "Noch nicht eingerichtet"
        !snapshot.enabled -> "Eingerichtet • Auto-Upload ist aus"
        snapshot.pendingBundles > 0 -> "Auto-Upload aktiv • ${snapshot.pendingBundles} Fahrt(en) warten"
        else -> "✓ Auto-Upload aktiv • GitHub aktuell"
    }
    Card(shape = RoundedCornerShape(22.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "☁ GitHub Fahrdaten & Decoder AI",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Text(status)
            Text(
                "KI: ${aiProfile.confirmedRuleCount}/${aiProfile.ruleCount} Regeln bestätigt • ${aiProfile.source}"
            )
            if (snapshot.lastStatus.isNotBlank()) {
                Text(snapshot.lastStatus, style = MaterialTheme.typography.bodySmall)
            }
            Button(onClick = onOpen, modifier = Modifier.fillMaxWidth()) {
                Text(if (snapshot.tokenConfigured) "GITHUB SYNC & DECODER AI" else "GITHUB SYNC EINRICHTEN")
            }
            Text(
                "Kein Scooter und keine Bluetooth-Verbindung für die Einstellungen nötig. " +
                    "Bestätigte KI-Regeln werden read-only in die Live-Auswertung übernommen.",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun AdaptiveLiveCard(state: ScooterState, aiProfile: AdaptiveProfileSnapshot) {
    fun binary(value: Boolean): String =
        if (!state.connected) "–" else if (value) "AN" else "AUS"

    Card(shape = RoundedCornerShape(22.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                "🧠 KI-gelernte Live-Signale",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            if ("brakeActive" in aiProfile.signals) Text("🛑 Bremse: ${binary(state.brakeActive)}")
            if ("leftIndicator" in aiProfile.signals) Text("⬅ Blinker links: ${binary(state.leftIndicator)}")
            if ("rightIndicator" in aiProfile.signals) Text("➡ Blinker rechts: ${binary(state.rightIndicator)}")
            if ("lightOn" in aiProfile.signals) Text("💡 Licht KI: ${binary(state.lightOn)}")
            if ("charging" in aiProfile.signals) {
                Text("🔌 Laden: ${state.charging?.let { binary(it) } ?: "–"}")
            }
            if ("lockActive" in aiProfile.signals) {
                Text("🔒 Sperre: ${state.lockActive?.let { binary(it) } ?: "–"}")
            }
            val numeric = aiProfile.signals.filter {
                it !in setOf(
                    "brakeActive",
                    "leftIndicator",
                    "rightIndicator",
                    "lightOn",
                    "charging",
                    "lockActive"
                )
            }
            if (numeric.isNotEmpty()) {
                Text(
                    "Zusätzliche Messwerte: ${numeric.sorted().joinToString(", ")}",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Text(
                "Nur bestätigte Regeln werden angezeigt; unsichere Treffer bleiben Lernkandidaten.",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun DirectMarkerCard(state: ScooterState, onMarker: (String) -> Unit) {
    val markers = listOf(
        "Stillstand",
        "Bremse im Stand",
        "Schieben frei",
        "Bremse beim Schieben",
        "Licht AUS",
        "Licht AN",
        "ECO",
        "SPORT",
        "Blinker links",
        "Blinker rechts",
        "Anfahren",
        "Rollen",
        "Vollgas"
    )
    Card(shape = RoundedCornerShape(22.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            markers.chunked(2).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { marker ->
                        Button(
                            onClick = { onMarker(marker) },
                            enabled = state.connected,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(marker)
                        }
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
    Card(shape = RoundedCornerShape(22.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "BLE-Explorer & sicherer READ-Scan",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Text(state.status)
            Text(
                "Bestätigt ${state.confirmedCount} • Beobachtet ${state.observedCount} • " +
                    "SDK ${state.sdkKnownCount} • Unbekannt ${state.unknownCount}",
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                "Dienste ${state.serviceCount} • Characteristics ${state.characteristicCount} • " +
                    "READ ${state.readableCount} • gestartet ${state.startedReads}",
                style = MaterialTheme.typography.bodySmall
            )
            OutlinedButton(
                onClick = onScan,
                enabled = enabled && !state.running,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (state.running) "READ-SCAN LÄUFT …" else "ALLE SICHEREN READ-FELDER LESEN")
            }
        }
    }
}

@Composable
private fun GattEntryCard(entry: GattCharacteristicInfo) {
    val readState = when {
        entry.lastReadStarted -> "✓ READ gestartet"
        entry.readable -> "READ verfügbar"
        else -> "Live/statisch"
    }
    Card(shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                "${entry.serviceUuid} → ${entry.characteristicUuid}",
                fontWeight = FontWeight.Bold
            )
            Text("${entry.evidence} • ${entry.family} • $readState")
            if (entry.meaning.isNotBlank()) {
                Text(entry.meaning, style = MaterialTheme.typography.bodySmall)
            }
            if (entry.sources.isNotEmpty()) {
                Text(
                    "Quellen: ${entry.sources.joinToString(" • ")}",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            if (entry.confirmedDetails.isNotEmpty()) {
                Text(
                    "Bestätigt: ${entry.confirmedDetails.joinToString("; ")}",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            if (entry.unknownDetails.isNotEmpty()) {
                Text(
                    "Noch offen: ${entry.unknownDetails.joinToString("; ")}",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            if (entry.safetyNote.isNotBlank()) {
                Text("Sicherheit: ${entry.safetyNote}", style = MaterialTheme.typography.bodySmall)
            }
            if (entry.uiHint.isNotBlank()) {
                Text("Hinweis: ${entry.uiHint}", style = MaterialTheme.typography.bodySmall)
            }
            Text("Eigenschaften: ${entry.properties}", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun ConfirmedRawCard(state: ScooterState) {
    val a = parseHex(state.rawPackets["1505"])
    val b = parseHex(state.rawPackets["1502"])
    val c = parseHex(state.rawPackets["150A"])
    val d = parseHex(state.rawPackets["150D"])
    InfoCard(
        "Bestätigte & starke RAW-Felder",
        "1505 A/B: ${u16be(a, 0) ?: "–"} / ${u16be(a, 2) ?: "–"}\n" +
            "1505 Tempo: ${u16be(a, 6) ?: "–"}\n" +
            "150D Max/Ø RAW: ${u16be(d, 0) ?: "–"} / ${u16be(d, 2) ?: "–"}\n" +
            "150A Last: ${u16be(c, 0) ?: "–"}\n" +
            "1502 A/B: ${u16be(b, 0) ?: "–"} / ${u16be(b, 6) ?: "–"}"
    )
}

@Composable
private fun DecoderLabCard(
    state: ScooterState,
    selected: String,
    select: (String) -> Unit,
    baseline: () -> Unit,
    active: () -> Unit,
    finish: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val actions = listOf("Bremse", "Blinker links", "Blinker rechts", "Licht", "Fahrmodus", "Gas", "Rekuperation")
    Card(shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Optionaler 3-Schritt-Test", fontWeight = FontWeight.Bold)
            Box {
                OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(selected)
                }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    actions.forEach { action ->
                        DropdownMenuItem(
                            text = { Text(action) },
                            onClick = {
                                select(action)
                                expanded = false
                            }
                        )
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(
                    onClick = baseline,
                    enabled = state.connected && !state.labRunning,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("1 Ruhe")
                }
                Button(
                    onClick = active,
                    enabled = state.labRunning && state.labPhase.startsWith("1/2"),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("2 Aktion")
                }
                Button(
                    onClick = finish,
                    enabled = state.labRunning && state.labPhase.startsWith("2/2"),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("3 Fertig")
                }
            }
        }
    }
}

@Composable
private fun ChannelCard(channel: BleChannelState) {
    InfoCard(
        "${channel.channel} • ${channel.title}",
        "${channel.knowledge} • Pakete ${channel.packetCount} • Δ ${channel.changedBytes}\n${channel.hex}"
    )
}

@Composable
private fun RawDataCard(state: ScooterState) {
    InfoCard(
        "Letztes BLE-Paket",
        "${state.lastCharacteristic.ifBlank { "–" }} • Δ ${state.lastChangedBytes}\n" +
            state.lastRawHex.ifBlank { "Noch keine Daten" }
    )
}

@Composable
private fun MetricRow(
    firstTitle: String,
    firstValue: String,
    secondTitle: String,
    secondValue: String
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        MetricCard(firstTitle, firstValue, Modifier.weight(1f))
        MetricCard(secondTitle, secondValue, Modifier.weight(1f))
    }
}

@Composable
private fun MetricCard(title: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier, shape = RoundedCornerShape(18.dp)) {
        Column(
            Modifier.fillMaxWidth().padding(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(title)
            Text(value, fontWeight = FontWeight.Bold)
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

internal fun decode150dStatistic(hex: String?, offset: Int): Double? {
    val packet = parseHex(hex)
    if (isUnavailable150dPayload(packet.map(Int::toByte).toByteArray())) return null
    val raw = u16be(packet, offset) ?: return null
    return if (raw == 0xFFFF || raw == 0x8000) null else raw / 10.0
}

private fun parseHex(hex: String?): List<Int> =
    hex.orEmpty()
        .split('-', ' ', ':')
        .mapNotNull { part ->
            part.trim()
                .takeIf { it.length == 2 }
                ?.toIntOrNull(16)
        }

private fun u16be(bytes: List<Int>, offset: Int): Int? =
    if (offset < 0 || offset + 1 >= bytes.size) null
    else (bytes[offset] shl 8) or bytes[offset + 1]

private fun formatElapsed(ms: Long): String =
    "%02d:%02d:%02d".format(
        ms / 3_600_000,
        (ms / 60_000) % 60,
        (ms / 1_000) % 60
    )

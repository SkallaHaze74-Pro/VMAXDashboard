@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package de.kevin.vmaxdashboard

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.lifecycle.compose.collectAsStateWithLifecycle

private val VmaxRed = Color(0xFFFF3157)
private val ElectricBlue = Color(0xFF43C8FF)
private val Night = Color(0xFF070A10)
private val Panel = Color(0xFF111722)
private val Panel2 = Color(0xFF171F2C)
private val SoftText = Color(0xFF9BA9BB)

class MainActivity : ComponentActivity() {
    private lateinit var bleManager: BleScooterManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        bleManager = BleScooterManager(applicationContext)
        setContent { VmaxTheme { VmaxApp(bleManager) } }
    }

    override fun onDestroy() {
        bleManager.disconnect()
        super.onDestroy()
    }
}

@Composable
private fun VmaxTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = VmaxRed,
            secondary = ElectricBlue,
            background = Night,
            surface = Panel,
            surfaceVariant = Panel2,
            onBackground = Color.White,
            onSurface = Color.White
        ),
        content = content
    )
}

private enum class Screen(val label: String, val symbol: String) {
    Dashboard("Dashboard", "◉"), Ride("Fahrt", "↗"), Diagnose("Diagnose", "⌁"), Settings("Setup", "⚙")
}

@Composable
private fun VmaxApp(manager: BleScooterManager) {
    val state by manager.state.collectAsStateWithLifecycle()
    var screen by remember { mutableStateOf(Screen.Dashboard) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results -> if (results.values.all { it }) manager.startScan() }

    fun connect() {
        if (manager.hasRequiredPermissions()) manager.startScan()
        else permissionLauncher.launch(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) arrayOf(
                Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT
            ) else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        )
    }

    Scaffold(
        containerColor = Night,
        bottomBar = {
            NavigationBar(containerColor = Color(0xFF0B1018)) {
                Screen.entries.forEach { item ->
                    NavigationBarItem(
                        selected = screen == item,
                        onClick = { screen = item },
                        icon = { Text(item.symbol, fontSize = 20.sp) },
                        label = { Text(item.label) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = VmaxRed,
                            selectedTextColor = Color.White,
                            indicatorColor = VmaxRed.copy(alpha = .16f),
                            unselectedIconColor = SoftText,
                            unselectedTextColor = SoftText
                        )
                    )
                }
            }
        }
    ) { padding ->
        when (screen) {
            Screen.Dashboard -> DashboardScreen(state, ::connect, manager::disconnect, padding)
            Screen.Ride -> RideScreen(state, padding)
            Screen.Diagnose -> DiagnoseScreen(state, manager, padding)
            Screen.Settings -> SettingsScreen(state, ::connect, manager::disconnect, padding)
        }
    }
}

@Composable
private fun Header(state: ScooterState) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("VMAX", fontWeight = FontWeight.Black, fontSize = 28.sp, letterSpacing = 2.sp)
            Text("LIVE CONTROL", color = VmaxRed, fontWeight = FontWeight.Bold, fontSize = 12.sp)
        }
        Surface(shape = RoundedCornerShape(50), color = if (state.connected) Color(0xFF153827) else Panel2) {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).background(if (state.connected) Color(0xFF48E58B) else VmaxRed, CircleShape))
                Spacer(Modifier.width(7.dp))
                Text(if (state.connected) "LIVE" else "OFFLINE", fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun DashboardScreen(state: ScooterState, connect: () -> Unit, disconnect: () -> Unit, padding: PaddingValues) {
    LazyColumn(
        Modifier.fillMaxSize().padding(padding).padding(horizontal = 18.dp),
        contentPadding = PaddingValues(top = 18.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item { Header(state) }
        item { Speedometer(state.speedKmh ?: 0.0, state.batteryPercent) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ProMetric("🔋", "AKKU", state.batteryPercent?.let { "$it %" } ?: "–", Modifier.weight(1f))
                ProMetric("⚡", "SPANNUNG", state.voltageV?.let { "%.1f V".format(it) } ?: "Suche", Modifier.weight(1f))
                ProMetric("🌡", "TEMP", state.temperatureC?.let { "%.1f°".format(it) } ?: "Suche", Modifier.weight(1f))
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(24.dp)) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(state.deviceName, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Text(state.status, color = if (state.connected) Color(0xFF48E58B) else SoftText, fontSize = 13.sp)
                    }
                    Text(state.address.ifBlank { "Bereit für Bluetooth-Verbindung" }, color = SoftText)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(onClick = connect, enabled = !state.connected && !state.scanning, modifier = Modifier.weight(1f), shape = RoundedCornerShape(16.dp)) {
                            Text(if (state.scanning) "SUCHE …" else "VERBINDEN", fontWeight = FontWeight.Bold)
                        }
                        OutlinedButton(onClick = disconnect, enabled = state.connected || state.scanning, modifier = Modifier.weight(1f), shape = RoundedCornerShape(16.dp)) {
                            Text("TRENNEN")
                        }
                    }
                }
            }
        }
        item { TelemetryStrip(state) }
        item { MiniGraph(state.speedHistory) }
    }
}

@Composable
private fun Speedometer(speed: Double, battery: Int?) {
    val progress by animateFloatAsState((speed / 25.0).coerceIn(0.0, 1.0).toFloat(), label = "speed")
    Box(Modifier.fillMaxWidth().height(310.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(280.dp)) {
            val stroke = 22.dp.toPx()
            val inset = stroke / 2
            drawArc(Color(0xFF202938), 140f, 260f, false, Offset(inset, inset), Size(size.width - stroke, size.height - stroke), style = Stroke(stroke, cap = StrokeCap.Round))
            drawArc(Brush.sweepGradient(listOf(ElectricBlue, VmaxRed, VmaxRed)), 140f, 260f * progress, false, Offset(inset, inset), Size(size.width - stroke, size.height - stroke), style = Stroke(stroke, cap = StrokeCap.Round))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("GESCHWINDIGKEIT", color = SoftText, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Text("%.1f".format(speed), fontSize = 72.sp, fontWeight = FontWeight.Black, letterSpacing = (-3).sp)
            Text("km/h", color = ElectricBlue, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(15.dp))
            Surface(color = Panel2, shape = RoundedCornerShape(50)) {
                Text("SPORT  •  ${battery?.let { "$it %" } ?: "AKKU –"}", Modifier.padding(horizontal = 16.dp, vertical = 8.dp), fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun ProMetric(symbol: String, label: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier, shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = Panel)) {
        Column(Modifier.fillMaxWidth().padding(vertical = 15.dp, horizontal = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(symbol, fontSize = 20.sp)
            Spacer(Modifier.height(5.dp))
            Text(label, color = SoftText, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            Text(value, fontWeight = FontWeight.Black, fontSize = 17.sp, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun TelemetryStrip(state: ScooterState) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        CompactMetric("MAX", "%.1f km/h".format(state.maxSpeedKmh), Modifier.weight(1f))
        CompactMetric("PAKETE", state.packetTotal.toString(), Modifier.weight(1f))
        CompactMetric("KANÄLE", state.channels.size.toString(), Modifier.weight(1f))
    }
}

@Composable
private fun CompactMetric(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(modifier, color = Panel2, shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(13.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, color = SoftText, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            Text(value, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        }
    }
}

@Composable
private fun MiniGraph(history: List<Double>) {
    Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.padding(18.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("ECHTE LIVE-TELEMETRIE", fontWeight = FontWeight.Bold)
                Text("${history.size}/60 WERTE", color = ElectricBlue, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(14.dp))
            Canvas(Modifier.fillMaxWidth().height(90.dp)) {
                val points = if (history.size >= 2) history else listOf(0.0, 0.0)
                val maxValue = maxOf(25.0, points.maxOrNull() ?: 25.0)
                val step = size.width / (points.size - 1).coerceAtLeast(1)
                for (i in 0 until points.lastIndex) {
                    val y1 = size.height * (1f - (points[i] / maxValue).toFloat().coerceIn(0f, 1f))
                    val y2 = size.height * (1f - (points[i + 1] / maxValue).toFloat().coerceIn(0f, 1f))
                    drawLine(ElectricBlue, Offset(i * step, y1), Offset((i + 1) * step, y2), 4.dp.toPx(), StrokeCap.Round)
                }
            }
        }
    }
}

@Composable
private fun RideScreen(state: ScooterState, padding: PaddingValues) {
    LazyColumn(Modifier.fillMaxSize().padding(padding).padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Header(state) }
        item { Text("FAHRT", fontSize = 28.sp, fontWeight = FontWeight.Black) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                BigStat("AKTUELL", "%.1f".format(state.speedKmh ?: 0.0), "km/h", Modifier.weight(1f))
                BigStat("MAXIMUM", "%.1f".format(state.maxSpeedKmh), "km/h", Modifier.weight(1f))
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                BigStat("Ø SPEED", "%.1f".format(state.averageSpeedKmh), "km/h", Modifier.weight(1f))
                BigStat("FAHRZEIT", formatRideTime(state.rideSeconds), "hh:mm:ss", Modifier.weight(1f))
            }
        }
        item { MiniGraph(state.speedHistory) }
        item { InfoPanel("Fahrtenaufzeichnung 3.1", "Geschwindigkeit, Maximum, Durchschnitt und Fahrzeit werden jetzt aus echten BLE-Messwerten berechnet. Strecke und GPS folgen nach Bestätigung des Kilometer-Bytes.") }
    }
}

@Composable
private fun BigStat(label: String, value: String, unit: String, modifier: Modifier) {
    Card(modifier, colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.padding(20.dp)) {
            Text(label, color = SoftText, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Text(value, fontSize = 36.sp, fontWeight = FontWeight.Black)
            Text(unit, color = ElectricBlue, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun DiagnoseScreen(state: ScooterState, manager: BleScooterManager, padding: PaddingValues) {
    val clipboard = LocalClipboardManager.current
    var showOnlyCandidates by remember { mutableStateOf(false) }
    val visibleChannels = if (showOnlyCandidates) {
        state.channels.filter { channel -> channel.byteStats.any { it.changeCount >= 2 || it.range >= 2 } }
    } else state.channels

    LazyColumn(
        Modifier.fillMaxSize().padding(padding).padding(horizontal = 18.dp),
        contentPadding = PaddingValues(vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { Header(state) }
        item {
            Text("BLE LABOR 3.1", fontSize = 28.sp, fontWeight = FontWeight.Black)
            Text("Markiere jede Aktion. Die App vergleicht alle Bytes automatisch mit dem Startwert.", color = SoftText)
        }
        item {
            Surface(color = Panel, shape = RoundedCornerShape(22.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("AKTUELLE TESTPHASE", color = SoftText, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Text("#${state.analysisPhaseNumber}  ${state.analysisPhase}", fontSize = 22.sp, fontWeight = FontWeight.Black, color = ElectricBlue)
                    Text("Vor jeder neuen Bewegung einmal den passenden Knopf drücken.", color = SoftText, fontSize = 12.sp)
                }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PhaseButton("STAND", Modifier.weight(1f)) { manager.setAnalysisPhase("Stillstand") }
                    PhaseButton("RAD", Modifier.weight(1f)) { manager.setAnalysisPhase("Rad langsam drehen") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PhaseButton("FAHRT", Modifier.weight(1f)) { manager.setAnalysisPhase("Fahren / Gas") }
                    PhaseButton("BREMSE", Modifier.weight(1f)) { manager.setAnalysisPhase("Bremsen") }
                }
            }
        }
        item { TelemetryStrip(state) }
        item { ChannelExplorerSummary(state.channels) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { showOnlyCandidates = !showOnlyCandidates },
                    modifier = Modifier.weight(1f)
                ) { Text(if (showOnlyCandidates) "ALLE KANÄLE" else "NUR KANDIDATEN") }
                Button(
                    onClick = { clipboard.setText(AnnotatedString(buildAnalysisReport(state))) },
                    enabled = state.channels.isNotEmpty(),
                    modifier = Modifier.weight(1f)
                ) { Text("REPORT KOPIEREN") }
            }
        }
        item {
            OutlinedButton(onClick = manager::resetAnalyzer, modifier = Modifier.fillMaxWidth()) {
                Text("ANALYSE ZURÜCKSETZEN")
            }
        }
        if (state.decoderCandidates.isNotEmpty()) item {
            DecoderAssistantCard(state.decoderCandidates)
        }
        if (state.channels.isEmpty()) item {
            InfoPanel("Noch keine Pakete", "Verbinde BT638. Drücke zuerst STAND, warte fünf Sekunden und markiere danach jede Bewegung mit RAD, FAHRT oder BREMSE.")
        }
        items(visibleChannels, key = { it.channel }) { channel -> ChannelCard(channel) }
        item { Text("LIVE-LOG", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp)) }
        items(state.log.take(20)) { line ->
            Text(line, color = SoftText, fontSize = 11.sp)
            HorizontalDivider(color = Color.White.copy(alpha = .07f))
        }
    }
}


@Composable
private fun ChannelExplorerSummary(channels: List<BleChannelState>) {
    val active = channels.count { it.active }
    val changing = channels.count { channel -> channel.byteStats.any { it.changeCount > 0 } }
    val services = channels.map { it.service }.filter { it.isNotBlank() }.distinct().size
    Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(22.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("BLE-KANAL-EXPLORER", fontWeight = FontWeight.Black, fontSize = 18.sp)
            Text("Alle gefundenen Notify- und Indicate-Kanäle – nicht nur Dienst 1500.", color = SoftText, fontSize = 12.sp)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                ExplorerMetric("GEFUNDEN", channels.size.toString())
                ExplorerMetric("AKTIV", active.toString())
                ExplorerMetric("ÄNDERN SICH", changing.toString())
                ExplorerMetric("DIENSTE", services.toString())
            }
        }
    }
}

@Composable
private fun ExplorerMetric(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = ElectricBlue, fontWeight = FontWeight.Black, fontSize = 20.sp)
        Text(label, color = SoftText, fontSize = 9.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun DecoderAssistantCard(candidates: List<DecoderCandidate>) {
    Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(22.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("AUTO-DECODER 3.1", fontWeight = FontWeight.Black, fontSize = 18.sp)
            Text("Die stärksten unbekannten Byte-Kandidaten", color = SoftText, fontSize = 12.sp)
            candidates.take(6).forEach { candidate ->
                Surface(color = Panel2, shape = RoundedCornerShape(14.dp)) {
                    Column(Modifier.fillMaxWidth().padding(12.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("${candidate.channel} · B${candidate.byteIndex}", color = ElectricBlue, fontWeight = FontWeight.Bold)
                            Text("Score ${candidate.score}", color = VmaxRed, fontWeight = FontWeight.Bold)
                        }
                        Text(candidate.hint, fontSize = 12.sp)
                        Text("Wert ${candidate.current} · Bereich ${candidate.range} · ${candidate.changeCount} Änderungen", color = SoftText, fontSize = 11.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun PhaseButton(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Button(onClick = onClick, modifier = modifier, shape = RoundedCornerShape(14.dp)) {
        Text(label, fontWeight = FontWeight.Black)
    }
}

private fun formatRideTime(seconds: Long): String {
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    val secs = seconds % 60
    return "%02d:%02d:%02d".format(hours, minutes, secs)
}

private fun buildAnalysisReport(state: ScooterState): String = buildString {
    appendLine("VMAX BLE ANALYSE v3.1")
    appendLine("Gerät: ${state.deviceName} ${state.address}")
    appendLine("Status: ${state.status}")
    appendLine("Testphase #${state.analysisPhaseNumber}: ${state.analysisPhase}")
    appendLine("Pakete: ${state.packetTotal}; Kanäle: ${state.channels.size}")
    appendLine("Geschwindigkeit: ${state.speedKmh ?: 0.0} km/h; Akku: ${state.batteryPercent ?: -1}%")
    appendLine()
    state.channels.forEach { channel ->
        appendLine("DIENST ${channel.service} | KANAL ${channel.channel} | ${channel.properties} | Pakete ${channel.packetCount} | ${"%.2f".format(channel.packetsPerSecond)} Pakete/s | Länge ${channel.packetLength}")
        appendLine(channel.hex)
        appendLine("Letzte Änderungen: ${channel.changedBytes}")
        channel.byteStats
            .filter { it.changeCount > 0 || it.range > 0 || (it.deltaFromBaseline ?: 0) != 0 }
            .sortedWith(compareByDescending<BleByteStat> { it.changeCount }.thenByDescending { it.range })
            .take(12)
            .forEach { stat ->
                appendLine("  B${stat.index}: aktuell=${stat.current}, min=${stat.min}, max=${stat.max}, Bereich=${stat.range}, Änderungen=${stat.changeCount}, ΔBasis=${stat.deltaFromBaseline ?: 0}")
            }
        appendLine()
    }
}

@Composable
private fun ChannelCard(channel: BleChannelState) {
    val candidates = channel.byteStats
        .filter { it.changeCount > 0 || it.range > 0 || (it.deltaFromBaseline ?: 0) != 0 }
        .sortedWith(compareByDescending<BleByteStat> { it.changeCount }.thenByDescending { it.range })
        .take(8)

    Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text("KANAL ${channel.channel}", color = ElectricBlue, fontWeight = FontWeight.Black)
                    Text("Dienst ${channel.service.ifBlank { "?" }} · ${channel.properties}", color = SoftText, fontSize = 10.sp)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(if (channel.active) "● AKTIV" else "○ WARTET", color = if (channel.active) ElectricBlue else SoftText, fontWeight = FontWeight.Bold, fontSize = 10.sp)
                    Text("#${channel.packetCount} · ${"%.1f".format(channel.packetsPerSecond)} Hz · ${channel.packetLength} Byte", color = SoftText, fontSize = 10.sp)
                }
            }
            Text(channel.hex, fontSize = 11.sp, lineHeight = 16.sp, color = if (channel.active) Color.White else SoftText)
            if (channel.active) Text("Letzte Änderung: ${channel.changedBytes}", color = VmaxRed, fontSize = 11.sp)
            else Text("Kanal wurde gefunden, hat aber noch kein Paket gesendet.", color = SoftText, fontSize = 11.sp)
            if (candidates.isNotEmpty()) {
                HorizontalDivider(color = Color.White.copy(alpha = .08f))
                Text("BYTE-KANDIDATEN", color = SoftText, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                candidates.forEach { stat ->
                    val delta = stat.deltaFromBaseline?.let { if (it >= 0) "+$it" else "$it" } ?: "–"
                    Text(
                        "B${stat.index}: ${stat.current}   min ${stat.min} · max ${stat.max} · Δ $delta · ${stat.changeCount}× geändert",
                        fontSize = 11.sp,
                        color = if ((stat.deltaFromBaseline ?: 0) != 0) ElectricBlue else Color.White
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsScreen(state: ScooterState, connect: () -> Unit, disconnect: () -> Unit, padding: PaddingValues) {
    LazyColumn(Modifier.fillMaxSize().padding(padding).padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Header(state) }
        item { Text("SETUP", fontSize = 28.sp, fontWeight = FontWeight.Black) }
        item { SettingRow("Automatisch verbinden", "Vorbereitet für BT638", true) }
        item { SettingRow("Tacho glätten", "Ruhigere Live-Anzeige", true) }
        item { SettingRow("Dark Performance Theme", "VMAX Night-Ride Design", true) }
        item { SettingRow("Nur Lesemodus", "Keine Steuerbefehle an den Scooter", true) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(connect, enabled = !state.connected, modifier = Modifier.weight(1f)) { Text("VERBINDEN") }
                OutlinedButton(disconnect, enabled = state.connected, modifier = Modifier.weight(1f)) { Text("TRENNEN") }
            }
        }
        item { InfoPanel("VMAX Dashboard 3.1", "BLE-Kanal-Explorer über alle Dienste, Live-Aktivität, Paketrate, Byte-Analyse, echter Graph und Auto-Decoder.") }
    }
}

@Composable
private fun SettingRow(title: String, subtitle: String, checked: Boolean) {
    Surface(color = Panel, shape = RoundedCornerShape(18.dp)) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Bold)
                Text(subtitle, color = SoftText, fontSize = 12.sp)
            }
            Switch(checked = checked, onCheckedChange = null)
        }
    }
}

@Composable
private fun InfoPanel(title: String, text: String) {
    Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(22.dp)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text(title, fontWeight = FontWeight.Black, fontSize = 18.sp)
            Text(text, color = SoftText, lineHeight = 20.sp)
        }
    }
}

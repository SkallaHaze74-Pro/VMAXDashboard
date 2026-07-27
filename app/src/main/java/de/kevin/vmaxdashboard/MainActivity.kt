@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package de.kevin.vmaxdashboard

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.content.Intent
import androidx.compose.ui.platform.LocalContext
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.graphics.drawscope.rotate
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
private val NeonPurple = Color(0xFF9D4EDD)
private val NeonPink = Color(0xFFFF3CAC)
private val NeonOrange = Color(0xFFFF8A00)
private val NeonYellow = Color(0xFFFFE600)
private val NeonGreen = Color(0xFF39FF88)
private val NeonCyan = Color(0xFF00E5FF)
private val RainbowColors = listOf(NeonPink, NeonPurple, NeonCyan, NeonGreen, NeonYellow, NeonOrange, NeonPink)

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
    Dashboard("Cockpit", "◉"), Tester("Tester Lab", "✦"), Diagnose("Explorer", "⌁"), Settings("Setup", "⚙")
}

private enum class ReportSendState {
    Idle,
    Sending,
    Sent,
    Failed
}


@Composable
private fun VmaxApp(manager: BleScooterManager) {
    val state by manager.state.collectAsStateWithLifecycle()
    var screen by remember { mutableStateOf(Screen.Dashboard) }
    var pendingUniversalScan by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.all { it }) {
            if (pendingUniversalScan) manager.startUniversalScan() else manager.smartConnect()
        }
    }

    LaunchedEffect(Unit) {
        if (manager.hasRequiredPermissions()) manager.smartConnect()
    }

    fun connect() {
        pendingUniversalScan = false
        if (manager.hasRequiredPermissions()) manager.smartConnect()
        else permissionLauncher.launch(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) arrayOf(
                Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT
            ) else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        )
    }

    fun universalScan() {
        pendingUniversalScan = true
        if (manager.hasRequiredPermissions()) manager.startUniversalScan()
        else permissionLauncher.launch(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) arrayOf(
                Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT
            ) else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        )
    }

    Box(
        Modifier.fillMaxSize().background(
            Brush.radialGradient(
                colors = listOf(Color(0xFF17102A), Night, Color.Black),
                radius = 1400f
            )
        )
    ) {
    Scaffold(
        containerColor = Color.Transparent,
        bottomBar = {
            NavigationBar(containerColor = Color(0xEE090B12)) {
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
            Screen.Tester -> TesterLabScreen(state, manager, ::universalScan, padding)
            Screen.Diagnose -> DiagnoseScreen(state, manager, padding)
            Screen.Settings -> SettingsScreen(state, manager, ::connect, manager::disconnect, padding)
        }
    }
    }
}

@Composable
private fun Header(state: ScooterState) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("VX", fontWeight = FontWeight.Black, fontSize = 28.sp, letterSpacing = 2.sp)
            Text("SCOOTER TELEMETRY", color = NeonCyan, fontWeight = FontWeight.Bold, fontSize = 12.sp)
        }
        Surface(
            modifier = Modifier.border(1.dp, Brush.horizontalGradient(RainbowColors), RoundedCornerShape(50)),
            shape = RoundedCornerShape(50),
            color = if (state.connected) Color(0xCC10291F) else Color(0xCC151824)
        ) {
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
    val progress by animateFloatAsState(
        (speed / 25.0).coerceIn(0.0, 1.0).toFloat(),
        animationSpec = tween(650),
        label = "speed"
    )
    val infinite = rememberInfiniteTransition(label = "neonRainbow")
    val rotation by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(8500), RepeatMode.Restart),
        label = "rainbowRotation"
    )
    val pulse by infinite.animateFloat(
        initialValue = .72f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1200), RepeatMode.Reverse),
        label = "neonPulse"
    )

    Box(Modifier.fillMaxWidth().height(326.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(300.dp)) {
            val stroke = 22.dp.toPx()
            val inset = stroke / 2
            val arcSize = Size(size.width - stroke, size.height - stroke)
            drawArc(Color(0xFF202331), 140f, 260f, false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
            rotate(rotation, pivot = center) {
                drawArc(
                    Brush.sweepGradient(RainbowColors, center), 140f, 260f * progress, false,
                    Offset(inset, inset), arcSize,
                    style = Stroke(36.dp.toPx(), cap = StrokeCap.Round), alpha = .12f * pulse
                )
                drawArc(
                    Brush.sweepGradient(RainbowColors, center), 140f, 260f * progress, false,
                    Offset(inset, inset), arcSize,
                    style = Stroke(stroke, cap = StrokeCap.Round)
                )
            }
            drawCircle(Brush.radialGradient(listOf(NeonPurple.copy(alpha = .12f), Color.Transparent)), radius = size.minDimension * .38f)
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("NEON SPEED", color = SoftText, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
            Text("%.1f".format(speed), fontSize = 76.sp, fontWeight = FontWeight.Black, letterSpacing = (-4).sp)
            Text("km/h", color = NeonCyan, fontSize = 18.sp, fontWeight = FontWeight.Black)
            Spacer(Modifier.height(15.dp))
            Surface(
                modifier = Modifier.border(1.dp, Brush.horizontalGradient(RainbowColors), RoundedCornerShape(50)),
                color = Color(0xCC11131D), shape = RoundedCornerShape(50)
            ) {
                Text("SPORT  •  ${battery?.let { "$it %" } ?: "AKKU –"}", Modifier.padding(horizontal = 18.dp, vertical = 9.dp), fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun ProMetric(symbol: String, label: String, value: String, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(20.dp)
    Card(
        modifier.border(1.dp, Brush.linearGradient(RainbowColors.map { it.copy(alpha = .55f) }), shape),
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = Color(0xDD11131D))
    ) {
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
    val shape = RoundedCornerShape(24.dp)
    Card(
        modifier = Modifier.border(1.dp, Brush.horizontalGradient(RainbowColors.map { it.copy(alpha = .45f) }), shape),
        colors = CardDefaults.cardColors(containerColor = Color(0xDD11131D)), shape = shape
    ) {
        Column(Modifier.padding(18.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("NEON LIVE WAVE", fontWeight = FontWeight.Bold)
                Text("${history.size}/60 WERTE", color = NeonCyan, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(14.dp))
            Canvas(Modifier.fillMaxWidth().height(90.dp)) {
                val points = if (history.size >= 2) history else listOf(0.0, 0.0)
                val maxValue = maxOf(25.0, points.maxOrNull() ?: 25.0)
                val step = size.width / (points.size - 1).coerceAtLeast(1)
                for (i in 0 until points.lastIndex) {
                    val y1 = size.height * (1f - (points[i] / maxValue).toFloat().coerceIn(0f, 1f))
                    val y2 = size.height * (1f - (points[i + 1] / maxValue).toFloat().coerceIn(0f, 1f))
                    val fraction = i.toFloat() / points.lastIndex.coerceAtLeast(1)
                    val colorIndex = (fraction * (RainbowColors.size - 1)).toInt().coerceIn(0, RainbowColors.lastIndex)
                    val lineColor = RainbowColors[colorIndex]
                    drawLine(lineColor.copy(alpha = .18f), Offset(i * step, y1), Offset((i + 1) * step, y2), 11.dp.toPx(), StrokeCap.Round)
                    drawLine(lineColor, Offset(i * step, y1), Offset((i + 1) * step, y2), 4.dp.toPx(), StrokeCap.Round)
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
private fun SettingsScreen(state: ScooterState, manager: BleScooterManager, connect: () -> Unit, disconnect: () -> Unit, padding: PaddingValues) {
    LazyColumn(Modifier.fillMaxSize().padding(padding).padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Header(state) }
        item { Text("SETUP", fontSize = 28.sp, fontWeight = FontWeight.Black) }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(22.dp)) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("SMART CONNECT", fontWeight = FontWeight.Black, color = NeonCyan)
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Automatisch verbinden", fontWeight = FontWeight.Bold)
                            Text("Beim Start und nach einem Verbindungsabbruch.", color = SoftText, fontSize = 11.sp)
                        }
                        Switch(checked = state.autoConnectEnabled, onCheckedChange = manager::setAutoConnectEnabled)
                    }
                    Text(
                        if (state.rememberedDeviceAddress.isBlank()) "Noch kein Scooter gespeichert"
                        else "Gespeichert: ${state.rememberedDeviceName} · ${state.rememberedDeviceAddress}",
                        color = if (state.rememberedDeviceAddress.isBlank()) SoftText else NeonGreen,
                        fontSize = 12.sp
                    )
                    if (state.reconnectAttempt > 0 && !state.connected) {
                        Text("Wiederverbindungsversuch #${state.reconnectAttempt}", color = NeonYellow, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = connect, enabled = !state.connected, modifier = Modifier.weight(1f)) { Text("JETZT VERBINDEN", fontSize = 11.sp) }
                        OutlinedButton(onClick = manager::forgetRememberedScooter, enabled = state.rememberedDeviceAddress.isNotBlank(), modifier = Modifier.weight(1f)) { Text("VERGESSEN", fontSize = 11.sp) }
                    }
                }
            }
        }
        item { SettingRow("Tacho glätten", "Ruhigere Live-Anzeige", true) }
        item { SettingRow("Dark Performance Theme", "VMAX Night-Ride Design", true) }
        item { SettingRow("Nur Lesemodus", "Keine Steuerbefehle an den Scooter", true) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(connect, enabled = !state.connected, modifier = Modifier.weight(1f)) { Text("VERBINDEN") }
                OutlinedButton(disconnect, enabled = state.connected, modifier = Modifier.weight(1f)) { Text("TRENNEN") }
            }
        }
        item { InfoPanel("Scooter Telemetry VX ${BuildConfig.VERSION_NAME}", "Scooter-Finder, Tester Lab, universeller BLE-Scanner, standardisierte Decoder-Tests, Vergleichsbericht, Neon-Cockpit und sicherer Nur-Lesemodus.") }
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

@Composable
private fun TesterLabScreen(
    state: ScooterState,
    manager: BleScooterManager,
    universalScan: () -> Unit,
    padding: PaddingValues
) {
    val context = LocalContext.current
    val telemetryReporter = remember(context.applicationContext) {
        TelemetryReporter(context.applicationContext)
    }

    var scooterModel by remember { mutableStateOf("") }
    var testerNote by remember { mutableStateOf("") }
    var consent by remember { mutableStateOf(false) }
    var telemetryUploadEnabled by remember {
        mutableStateOf(telemetryReporter.uploadEnabled)
    }
    var telemetryStatus by remember { mutableStateOf("") }
    var autoScanStarted by remember { mutableStateOf(false) }
    var showAllDevices by remember { mutableStateOf(false) }
    var reportType by remember { mutableStateOf("Fehlerbericht") }
    var reportSendState by remember {
        mutableStateOf(ReportSendState.Idle)
    }
    var firebaseReady by remember { mutableStateOf(false) }
    var ownReports by remember {
        mutableStateOf<List<TesterReportItem>>(emptyList())
    }
    var ownReportsStatus by remember {
        mutableStateOf("Firebase-Verbindung wird aufgebaut …")
    }


    LaunchedEffect(Unit) {
        if (!autoScanStarted && !state.connected && state.discoveredScooters.isEmpty()) {
            autoScanStarted = true
            universalScan()
        }
    }

    LaunchedEffect(Unit) {
        telemetryReporter.ensureLogin(
            onSuccess = {
                firebaseReady = true
                ownReportsStatus = "Eigene Berichte werden geladen …"

                telemetryReporter.observeOwnReports(
                    onUpdate = { reports ->
                        ownReports = reports
                        ownReportsStatus =
                            if (reports.isEmpty()) {
                                "Noch keine Berichte vorhanden."
                            } else {
                                ""
                            }
                    },
                    onFailure = { error ->
                        ownReportsStatus =
                            "Berichte konnten nicht geladen werden: " +
                                (error.localizedMessage
                                    ?: "unbekannter Fehler")
                    }
                )
            },
            onFailure = { error ->
                firebaseReady = false
                ownReportsStatus =
                    "Firebase-Anmeldung fehlgeschlagen: " +
                        (error.localizedMessage
                            ?: "unbekannter Fehler")
            }
        )
    }



    val phases = listOf(
        "01 Stillstand 20 s",
        "02 Rad frei drehen / langsam",
        "03 Konstant langsam",
        "04 Konstant schnell",
        "05 Gas geben",
        "06 Rollen lassen",
        "07 Bremsen",
        "08 Licht EIN",
        "09 Licht AUS",
        "10 Laden / Abschluss"
    )

    fun shareReport() {
        val report = buildTesterReport(state, scooterModel, testerNote)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Scooter Telemetry VX Testbericht")
            putExtra(Intent.EXTRA_TEXT, report)
        }
        context.startActivity(Intent.createChooser(intent, "Testbericht teilen"))
    }

    LazyColumn(
        Modifier.fillMaxSize().padding(padding).padding(horizontal = 18.dp),
        contentPadding = PaddingValues(top = 18.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item { Header(state) }
        item {
            InfoPanel(
                "VX TESTER LAB",
                "Geführte Aufnahmen machen Daten verschiedener Scooter vergleichbar. Testdaten werden nur übertragen, wenn der Firebase-Schalter ausdrücklich aktiviert wurde. Das manuelle Teilen bleibt zusätzlich verfügbar."
            )
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(22.dp)) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("1 · TESTER & SCOOTER", fontWeight = FontWeight.Black, color = NeonCyan)
                    OutlinedTextField(
                        value = scooterModel,
                        onValueChange = { scooterModel = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Scooter-Modell, z. B. VX2 Pro") },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = testerNote,
                        onValueChange = { testerNote = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Firmware, Akkustand oder Besonderheiten") },
                        minLines = 2
                    )
                }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(22.dp)) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column {
                            Text("2 · SCOOTER-FINDER", fontWeight = FontWeight.Black, color = NeonPurple)
                            Text(state.status, color = SoftText, fontSize = 11.sp)
                        }
                        Surface(
                            color = if (state.scanning) NeonPurple.copy(alpha = 0.18f) else Panel2,
                            shape = RoundedCornerShape(20.dp)
                        ) {
                            Text(
                                if (state.scanning) "● LIVE" else "${state.discoveredScooters.size} GEFUNDEN",
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                color = if (state.scanning) NeonCyan else SoftText,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Black
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = universalScan,
                            enabled = !state.scanning && !state.connected,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(14.dp)
                        ) { Text(if (state.scanning) "SUCHE …" else "NEU SCANNEN") }
                        if (state.scanning) {
                            OutlinedButton(
                                onClick = { manager.stopScan() },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(14.dp)
                            ) { Text("STOPP") }
                        }
                    }

                    val likelyDevices = state.discoveredScooters.filter { it.likelyScooter }
                    val displayedDevices = if (showAllDevices || likelyDevices.isEmpty()) {
                        state.discoveredScooters
                    } else likelyDevices

                    if (state.scanning && state.discoveredScooters.isEmpty()) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text("Bluetooth-Geräte in der Nähe werden automatisch angezeigt …", color = SoftText, fontSize = 12.sp)
                    }

                    if (likelyDevices.isNotEmpty()) {
                        Text(
                            "${likelyDevices.size} möglicher Scooter erkannt",
                            color = NeonGreen,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp
                        )
                    }

                    displayedDevices.take(if (showAllDevices) 30 else 12).forEach { device ->
                        val quality = when {
                            device.rssi >= -55 -> "SEHR GUT"
                            device.rssi >= -67 -> "GUT"
                            device.rssi >= -78 -> "MITTEL"
                            else -> "SCHWACH"
                        }
                        val bars = when {
                            device.rssi >= -55 -> "▂▄▆█"
                            device.rssi >= -67 -> "▂▄▆·"
                            device.rssi >= -78 -> "▂▄··"
                            else -> "▂···"
                        }
                        Surface(
                            color = if (device.likelyScooter) NeonPurple.copy(alpha = 0.13f) else Panel2,
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .then(
                                    if (device.likelyScooter) Modifier.border(1.dp, NeonPurple.copy(alpha = 0.55f), RoundedCornerShape(16.dp))
                                    else Modifier
                                )
                        ) {
                            Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                                            Text(device.name, fontWeight = FontWeight.Black, fontSize = 15.sp)
                                            if (device.likelyScooter) {
                                                Text("SCOOTER?", color = NeonGreen, fontSize = 9.sp, fontWeight = FontWeight.Black)
                                            }
                                        }
                                        Text(device.address, color = SoftText, fontSize = 10.sp)
                                    }
                                    Column(horizontalAlignment = Alignment.End) {
                                        Text(bars, color = if (device.rssi >= -67) NeonGreen else NeonYellow, fontWeight = FontWeight.Black)
                                        Text("$quality · ${device.rssi} dBm", color = SoftText, fontSize = 9.sp)
                                    }
                                }
                                Button(
                                    onClick = { manager.connectTo(device.address) },
                                    enabled = !state.connected,
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(12.dp)
                                ) { Text("MIT ${device.name.uppercase()} VERBINDEN", fontSize = 11.sp) }
                            }
                        }
                    }

                    if (state.discoveredScooters.size > likelyDevices.size && likelyDevices.isNotEmpty()) {
                        TextButton(onClick = { showAllDevices = !showAllDevices }, modifier = Modifier.fillMaxWidth()) {
                            Text(if (showAllDevices) "NUR MÖGLICHE SCOOTER ZEIGEN" else "ALLE BLE-GERÄTE ANZEIGEN")
                        }
                    }

                    if (state.connected) {
                        Surface(color = NeonGreen.copy(alpha = 0.12f), shape = RoundedCornerShape(14.dp)) {
                            Column(Modifier.fillMaxWidth().padding(12.dp)) {
                                Text("● LIVE VERBUNDEN", color = NeonGreen, fontWeight = FontWeight.Black)
                                Text("${state.deviceName} · ${state.address}", color = SoftText, fontSize = 11.sp)
                            }
                        }
                    }
                }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(22.dp)) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("3 · GEFÜHRTER DECODER-TEST", fontWeight = FontWeight.Black, color = NeonPink)
                    Text("Vor jeder Aktion die passende Phase antippen und ungefähr 10–20 Sekunden halten.", color = SoftText, fontSize = 12.sp)
                    phases.chunked(2).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            row.forEach { phase ->
                                OutlinedButton(
                                    onClick = { manager.setAnalysisPhase(phase) },
                                    enabled = state.connected,
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(12.dp),
                                    contentPadding = PaddingValues(8.dp)
                                ) { Text(phase, fontSize = 10.sp, textAlign = TextAlign.Center) }
                            }
                            if (row.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                    Text("Aktiv: #${state.analysisPhaseNumber} ${state.analysisPhase}", color = NeonYellow, fontWeight = FontWeight.Bold)
                    Text("${state.packetTotal} Pakete · ${state.channels.count { it.active }} aktive Kanäle", color = SoftText)
                }
            }
        }
        item {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = Panel
                ),
                shape = RoundedCornerShape(22.dp)
            ) {
                Column(
                    Modifier.padding(18.dp),
                    verticalArrangement =
                        Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        "4 · BETA CENTER",
                        fontWeight = FontWeight.Black,
                        color = NeonCyan
                    )

                    Surface(
                        color = Panel2,
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment =
                                Alignment.CenterVertically
                        ) {
                            Box(
                                Modifier
                                    .size(10.dp)
                                    .background(
                                        if (firebaseReady) {
                                            NeonGreen
                                        } else {
                                            NeonYellow
                                        },
                                        CircleShape
                                    )
                            )

                            Spacer(Modifier.width(9.dp))

                            Column(Modifier.weight(1f)) {
                                Text(
                                    if (firebaseReady) {
                                        "Firebase verbunden"
                                    } else {
                                        "Firebase wird verbunden"
                                    },
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp
                                )

                                Text(
                                    "Tester-ID " +
                                        telemetryReporter.testerId
                                            .take(8)
                                            .uppercase() +
                                        "…",
                                    color = SoftText,
                                    fontSize = 10.sp
                                )
                            }
                        }
                    }

                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment =
                            Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "Anonyme Testdaten senden",
                                fontWeight = FontWeight.Bold
                            )

                            Text(
                                if (telemetryUploadEnabled) {
                                    "Aktiv – technische Testdaten " +
                                        "dürfen übertragen werden."
                                } else {
                                    "Aus – es werden keine " +
                                        "Testdaten hochgeladen."
                                },
                                color =
                                    if (telemetryUploadEnabled) {
                                        NeonGreen
                                    } else {
                                        SoftText
                                    },
                                fontSize = 11.sp
                            )
                        }

                        Switch(
                            checked = telemetryUploadEnabled,
                            onCheckedChange = { enabled ->
                                telemetryUploadEnabled = enabled
                                telemetryReporter.uploadEnabled =
                                    enabled
                                reportSendState =
                                    ReportSendState.Idle

                                telemetryStatus =
                                    if (enabled) {
                                        "Upload ist aktiviert."
                                    } else {
                                        "Upload ist ausgeschaltet."
                                    }
                            }
                        )
                    }

                    HorizontalDivider(
                        color = Color.White.copy(alpha = 0.08f)
                    )

                    Text(
                        "BERICHTSTYP",
                        color = SoftText,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )

                    Row(
                        horizontalArrangement =
                            Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected =
                                reportType == "Fehlerbericht",
                            onClick = {
                                reportType = "Fehlerbericht"
                            },
                            label = {
                                Text("🐞 FEHLER")
                            },
                            modifier = Modifier.weight(1f)
                        )

                        FilterChip(
                            selected =
                                reportType == "Funktionswunsch",
                            onClick = {
                                reportType = "Funktionswunsch"
                            },
                            label = {
                                Text("💡 WUNSCH")
                            },
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Text(
                        if (reportType == "Fehlerbericht") {
                            "Beschreibe oben bei Besonderheiten " +
                                "möglichst genau, was nicht funktioniert."
                        } else {
                            "Beschreibe oben, welche Funktion du " +
                                "dir für die App wünschst."
                        },
                        color = SoftText,
                        fontSize = 11.sp
                    )

                    Button(
                        onClick = {
                            reportSendState =
                                ReportSendState.Sending
                            telemetryStatus =
                                "Bericht wird gesendet …"

                            val title =
                                when (reportType) {
                                    "Funktionswunsch" -> {
                                        if (scooterModel.isBlank()) {
                                            "Funktionswunsch"
                                        } else {
                                            "Funktionswunsch: " +
                                                scooterModel
                                        }
                                    }

                                    else -> {
                                        if (scooterModel.isBlank()) {
                                            "Fehlerbericht"
                                        } else {
                                            "Fehlerbericht: " +
                                                scooterModel
                                        }
                                    }
                                }

                            telemetryReporter.reportProblem(
                                title = title,
                                details = buildTesterReport(
                                    state,
                                    scooterModel,
                                    testerNote
                                ),
                                scooterState = state,
                                reportType = reportType,
                                scooterModel = scooterModel,
                                onSuccess = {
                                    reportSendState =
                                        ReportSendState.Sent
                                    telemetryStatus =
                                        "Bericht erfolgreich gesendet."
                                },
                                onFailure = { error ->
                                    reportSendState =
                                        ReportSendState.Failed
                                    telemetryStatus =
                                        error.localizedMessage
                                            ?: "Bericht konnte nicht " +
                                                "gesendet werden."
                                }
                            )
                        },
                        enabled =
                            telemetryUploadEnabled &&
                                testerNote.isNotBlank() &&
                                reportSendState !=
                                    ReportSendState.Sending,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Text(
                            when {
                                !telemetryUploadEnabled ->
                                    "UPLOAD ZUERST AKTIVIEREN"

                                testerNote.isBlank() ->
                                    "BESCHREIBUNG OBEN EINTRAGEN"

                                reportSendState ==
                                    ReportSendState.Sending ->
                                    "WIRD GESENDET …"

                                reportType ==
                                    "Funktionswunsch" ->
                                    "FUNKTIONSWUNSCH SENDEN"

                                else ->
                                    "FEHLERBERICHT SENDEN"
                            },
                            fontWeight = FontWeight.Black
                        )
                    }

                    ReportUploadStatus(
                        state = reportSendState,
                        message = telemetryStatus
                    )

                    HorizontalDivider(
                        color = Color.White.copy(alpha = 0.08f)
                    )

                    Text(
                        "MEINE BERICHTE",
                        fontWeight = FontWeight.Black,
                        color = NeonPurple
                    )

                    if (ownReportsStatus.isNotBlank()) {
                        Text(
                            ownReportsStatus,
                            color =
                                if (
                                    ownReportsStatus.contains(
                                        "fehl",
                                        ignoreCase = true
                                    ) ||
                                    ownReportsStatus.contains(
                                        "nicht geladen",
                                        ignoreCase = true
                                    )
                                ) {
                                    VmaxRed
                                } else {
                                    SoftText
                                },
                            fontSize = 11.sp
                        )
                    }

                    ownReports.take(10).forEach { report ->
                        TesterReportRow(report)
                    }

                    Text(
                        "Nur Berichte deiner anonymen Tester-ID " +
                            "werden angezeigt.",
                        color = SoftText,
                        fontSize = 10.sp
                    )
                }
            }
        }

        item {
            Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(22.dp)) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("5 · BERICHT TEILEN", fontWeight = FontWeight.Black, color = NeonGreen)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = consent, onCheckedChange = { consent = it })
                        Text("Ich habe geprüft, dass ich diesen technischen Bericht teilen möchte.", fontSize = 12.sp)
                    }
                    Button(
                        onClick = ::shareReport,
                        enabled = consent && state.packetTotal > 0,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("STANDARDISIERTEN TESTBERICHT TEILEN") }
                    OutlinedButton(onClick = manager::resetAnalyzer, modifier = Modifier.fillMaxWidth()) { Text("NEUE TESTSESSION") }
                    Text("Enthält Geräte-/Android-Version, Modellangabe, UUID-Kanäle und Byte-Statistiken. Kein GPS. Firebase-Übertragung nur nach ausdrücklicher Aktivierung.", color = SoftText, fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable
private fun ReportUploadStatus(
    state: ReportSendState,
    message: String
) {
    val color = when (state) {
        ReportSendState.Idle -> SoftText
        ReportSendState.Sending -> NeonYellow
        ReportSendState.Sent -> NeonGreen
        ReportSendState.Failed -> VmaxRed
    }

    val fallback = when (state) {
        ReportSendState.Idle ->
            "Bereit zum Senden"

        ReportSendState.Sending ->
            "Bericht wird gesendet …"

        ReportSendState.Sent ->
            "Bericht wurde erfolgreich gesendet."

        ReportSendState.Failed ->
            "Bericht konnte nicht gesendet werden."
    }

    Surface(
        color = color.copy(alpha = 0.10f),
        shape = RoundedCornerShape(14.dp)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(10.dp)
                    .background(color, CircleShape)
            )

            Spacer(Modifier.width(9.dp))

            Text(
                message.ifBlank { fallback },
                color = color,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun TesterReportRow(
    report: TesterReportItem
) {
    val statusColor = when (
        report.status.trim().lowercase()
    ) {
        "behoben" ->
            NeonGreen

        "in prüfung",
        "in pruefung" ->
            ElectricBlue

        "gesehen" ->
            NeonPurple

        else ->
            NeonYellow
    }

    Surface(
        color = Panel2,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            Modifier.padding(12.dp),
            verticalArrangement =
                Arrangement.spacedBy(5.dp)
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment =
                    Alignment.CenterVertically
            ) {
                Box(
                    Modifier
                        .size(9.dp)
                        .background(
                            statusColor,
                            CircleShape
                        )
                )

                Spacer(Modifier.width(8.dp))

                Text(
                    report.title,
                    modifier = Modifier.weight(1f),
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp
                )

                Text(
                    report.status.uppercase(),
                    color = statusColor,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Black
                )
            }

            Text(
                report.reportType,
                color = SoftText,
                fontSize = 10.sp
            )
        }
    }
}

private fun buildTesterReport(state: ScooterState, scooterModel: String, testerNote: String): String = buildString {
    appendLine("SCOOTER TELEMETRY VX – TESTER REPORT ${BuildConfig.VERSION_NAME}")
    appendLine("Report-Schema: STVX-1")
    appendLine("App: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
    appendLine("Android: ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}")
    appendLine("Telefon: ${Build.MANUFACTURER} ${Build.MODEL}")
    appendLine("Scooter-Modell: ${scooterModel.ifBlank { "nicht angegeben" }}")
    appendLine("BLE-Gerät: ${state.deviceName} ${state.address}")
    appendLine("Tester-Notiz: ${testerNote.ifBlank { "–" }}")
    appendLine("Phase #${state.analysisPhaseNumber}: ${state.analysisPhase}")
    appendLine("Pakete gesamt: ${state.packetTotal}")
    appendLine("Kanäle gefunden: ${state.channels.size}; aktiv: ${state.channels.count { it.active }}")
    appendLine("Messwerte: speed=${state.speedKmh ?: "?"}; battery=${state.batteryPercent ?: "?"}; voltage=${state.voltageV ?: "?"}; temp=${state.temperatureC ?: "?"}")
    appendLine()
    state.channels.forEach { channel ->
        appendLine("SERVICE=${channel.service};CHANNEL=${channel.channel};PROPS=${channel.properties};PACKETS=${channel.packetCount};HZ=${"%.3f".format(channel.packetsPerSecond)};LEN=${channel.packetLength}")
        appendLine("HEX=${channel.hex}")
        channel.byteStats.forEach { stat ->
            appendLine("BYTE=${stat.index};NOW=${stat.current};MIN=${stat.min};MAX=${stat.max};RANGE=${stat.range};CHANGES=${stat.changeCount};DELTA=${stat.deltaFromBaseline ?: 0}")
        }
        appendLine("END_CHANNEL")
    }
}

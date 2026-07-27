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
            val permissions =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
                } else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
            permissionLauncher.launch(permissions)
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("VMAX Dashboard • VX2 Gear") }) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(vertical = 14.dp)
        ) {
            item { StatusCard(state) }
            item { IndicatorDashboard(state) }

            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    MetricCard("Akku", state.batteryPercent?.let { "$it %" } ?: "–", Modifier.weight(1f))
                    MetricCard("Signal", state.rssi?.let { "$it dBm" } ?: "–", Modifier.weight(1f))
                }
            }

            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    MetricCard("Tempo", state.speedKmh?.let { "%.1f km/h".format(it) } ?: "lernt noch", Modifier.weight(1f))
                    MetricCard("Temperatur", state.temperatureC?.let { "%.1f °C".format(it) } ?: "lernt noch", Modifier.weight(1f))
                }
            }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
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

            item { RawDataCard(state) }

            item {
                Text("Datenprotokoll", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
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
        Column(
            Modifier.fillMaxWidth().padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(state.status, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("${state.deviceName} • VX2 Gear / VX2 4G")
            Text(if (state.connected) "● Bluetooth verbunden" else "○ Bluetooth nicht verbunden")
            Text("Verschlüsselte Berichte auf dem Handy: ${state.encryptedReports}")
        }
    }
}

@Composable
private fun IndicatorDashboard(state: ScooterState) {
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
                Text(
                    listOfNotNull(
                        "💡".takeIf { state.lightOn },
                        "BRAKE".takeIf { state.brakeActive }
                    ).joinToString("  ").ifBlank { "Live-Telemetrie" }
                )
            }
            Text(if (state.rightIndicator) "▶" else "▷", style = MaterialTheme.typography.displaySmall)
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
    val actions = listOf(
        "Blinker links", "Blinker rechts", "Licht", "Bremse",
        "Fahrmodus", "Gas", "Rekuperation", "Unbekannt"
    )
    var expanded by remember { mutableStateOf(false) }

    Card(shape = RoundedCornerShape(22.dp)) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("🔬 Decoder Lab", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("Die App vergleicht BLE-Bytes vor und während einer Aktion.")

            ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = !expanded }) {
                OutlinedTextField(
                    value = selectedAction,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Test") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                    modifier = Modifier.menuAnchor().fillMaxWidth()
                )
                ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    actions.forEach { action ->
                        DropdownMenuItem(
                            text = { Text(action) },
                            onClick = {
                                onActionSelected(action)
                                expanded = false
                            }
                        )
                    }
                }
            }

            Text("Phase: ${state.labPhase}")

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onBaseline,
                    enabled = state.connected && !state.labRunning,
                    modifier = Modifier.weight(1f)
                ) { Text("1. Ruhe") }
                Button(
                    onClick = onActive,
                    enabled = state.labRunning && state.labPhase.startsWith("1/2"),
                    modifier = Modifier.weight(1f)
                ) { Text("2. Aktion") }
                Button(
                    onClick = onFinish,
                    enabled = state.labRunning && state.labPhase.startsWith("2/2"),
                    modifier = Modifier.weight(1f)
                ) { Text("3. Fertig") }
            }

            AnimatedVisibility(state.labCandidates.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text("Beste Treffer", fontWeight = FontWeight.Bold)
                    state.labCandidates.take(10).forEach {
                        Text(
                            "${it.characteristic} • Byte ${it.byteIndex}: " +
                                "${it.beforeValue} → ${it.activeValue} • ${it.score}%"
                        )
                    }
                }
            }

            Text(
                "GPS und Bluetooth-Adresse werden nicht in den verschlüsselten Bericht aufgenommen.",
                style = MaterialTheme.typography.labelMedium
            )
        }
    }
}

@Composable
private fun MetricCard(title: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier = modifier, shape = RoundedCornerShape(18.dp)) {
        Column(
            Modifier.fillMaxWidth().padding(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(title, style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(6.dp))
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun RawDataCard(state: ScooterState) {
    Card(shape = RoundedCornerShape(18.dp)) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            Text("Letztes BLE-Paket", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("Kanal: ${state.lastCharacteristic.ifBlank { "–" }}")
            Text(state.lastRawHex.ifBlank { "Noch keine Daten empfangen" }, style = MaterialTheme.typography.bodySmall)
            Text("${state.rawPackets.size} Kanäle zuletzt gesehen")
            Text("Nur Diagnose: Es werden keine Steuerbefehle an den Scooter gesendet.")
        }
    }
}

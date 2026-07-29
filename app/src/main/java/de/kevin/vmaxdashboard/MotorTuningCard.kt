@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package de.kevin.vmaxdashboard

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

@Composable
fun MotorTuningCard(state: ScooterState, manager: BleScooterManager) {
    var selectedProfileIndex by rememberSaveable { mutableIntStateOf(1) }
    var profileMenuOpen by remember { mutableStateOf(false) }
    var safetyConfirmed by rememberSaveable { mutableStateOf(false) }
    var resetDialogOpen by remember { mutableStateOf(false) }
    val draftValues = remember { mutableStateMapOf<MotorTuningParameter, Int>() }

    val profile = state.motorTuningProfiles.firstOrNull { it.index == selectedProfileIndex }
        ?: state.motorTuningProfiles.firstOrNull()
    val original = state.motorTuningOriginalProfiles.firstOrNull { it.index == profile?.index }

    LaunchedEffect(profile?.index, profile?.values, state.motorTuningLastReadRaw) {
        profile?.let {
            selectedProfileIndex = it.index
            draftValues.clear()
            draftValues.putAll(it.values)
        }
    }

    val previewPacket = remember(profile, draftValues.toMap(), state.motorTuningProtocol) {
        profile?.let {
            runCatching {
                MotorTuningProtocol.packetHex(
                    MotorTuningProtocol.buildWritePacket(it, draftValues.toMap(), state.motorTuningProtocol)
                )
            }.getOrNull()
        }.orEmpty()
    }

    Card {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("⚙ Motor‑Tuning Lab", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black)
            Text(
                "Originalwerte werden zuerst über 160C gelesen und im Arbeitsspeicher gesichert. " +
                    "Geschrieben wird auf 160D; danach liest die App zurück und vergleicht jedes Feld.",
                style = MaterialTheme.typography.bodySmall
            )

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                color = when (state.motorTuningLastVerified) {
                    true -> MaterialTheme.colorScheme.primaryContainer
                    false -> MaterialTheme.colorScheme.errorContainer
                    null -> MaterialTheme.colorScheme.surfaceVariant
                }
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(state.motorTuningStatus, fontWeight = FontWeight.Bold)
                    Text(
                        "160C lesen: ${yesNo(state.motorTuningReadAvailable)} • " +
                            "160D schreiben: ${yesNo(state.motorTuningWriteAvailable)}",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        "Protokoll: ${state.motorTuningProtocol.label} • Profile: ${state.motorTuningProfiles.size}",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { manager.readMotorTuningValues() },
                    enabled = state.connected && state.motorTuningReadAvailable && !state.motorTuningBusy,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Original neu lesen")
                }
                if (state.motorTuningBusy) {
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(28.dp))
                    }
                } else {
                    Text(
                        if (state.motorTuningOriginalProfiles.isNotEmpty()) "✓ Original gesichert" else "Noch kein Original",
                        modifier = Modifier.weight(1f).align(Alignment.CenterVertically),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            if (state.motorTuningProfiles.isNotEmpty()) {
                Box {
                    OutlinedButton(onClick = { profileMenuOpen = true }, modifier = Modifier.fillMaxWidth()) {
                        Text("Profil ${profile?.index ?: 1}")
                    }
                    DropdownMenu(expanded = profileMenuOpen, onDismissRequest = { profileMenuOpen = false }) {
                        state.motorTuningProfiles.forEach { item ->
                            DropdownMenuItem(
                                text = { Text("Profil ${item.index}") },
                                onClick = {
                                    selectedProfileIndex = item.index
                                    profileMenuOpen = false
                                }
                            )
                        }
                    }
                }

                profile?.wireOrder
                    ?.filter { profile.values.containsKey(it) }
                    ?.forEach { parameter ->
                        val current = draftValues[parameter] ?: profile.values[parameter] ?: 0
                        val originalValue = original?.values?.get(parameter) ?: profile.values[parameter] ?: current
                        val minimum = max(0, originalValue - 5)
                        val maximum = if (parameter == MotorTuningParameter.MaxSpeed) {
                            originalValue
                        } else {
                            min(parameter.sdkMaximum, originalValue + 5)
                        }.coerceAtLeast(minimum)

                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(parameter.label, fontWeight = FontWeight.Bold)
                                Text("$current  (Original $originalValue)")
                            }
                            if (maximum > minimum) {
                                Slider(
                                    value = current.coerceIn(minimum, maximum).toFloat(),
                                    onValueChange = { draftValues[parameter] = it.roundToInt() },
                                    valueRange = minimum.toFloat()..maximum.toFloat(),
                                    steps = (maximum - minimum - 1).coerceAtLeast(0),
                                    enabled = !state.motorTuningBusy
                                )
                            } else {
                                Text("Fester Testwert: $current", style = MaterialTheme.typography.bodySmall)
                            }
                            Text(
                                if (parameter == MotorTuningParameter.MaxSpeed)
                                    "Erster Test: nur senken oder Original wiederherstellen."
                                else "Erster Test: maximal ±5 vom gesicherten Original.",
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }

                HorizontalDivider()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = safetyConfirmed,
                        onCheckedChange = { safetyConfirmed = it },
                        enabled = !state.motorTuningBusy
                    )
                    Text(
                        "Scooter steht sicher, Rad ist frei bzw. Fahrzeug ist aufgebockt, und ich ändere nur einen kleinen Wert.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                Text("Paketvorschau 160D: ${previewPacket.ifBlank { "–" }}", style = MaterialTheme.typography.bodySmall)

                Button(
                    onClick = {
                        profile?.let { manager.writeMotorTuning(it.index, draftValues.toMap()) }
                    },
                    enabled = safetyConfirmed && profile != null && state.motorTuningSupported && !state.motorTuningBusy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
                ) {
                    Text("TESTWEISE ÜBERTRAGEN & PRÜFEN", fontWeight = FontWeight.Black)
                }

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { profile?.let { manager.restoreOriginalMotorTuning(it.index) } },
                        enabled = safetyConfirmed && original != null && !state.motorTuningBusy,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Original wiederherstellen")
                    }
                    OutlinedButton(
                        onClick = { resetDialogOpen = true },
                        enabled = safetyConfirmed && profile != null && !state.motorTuningBusy,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Werkprofil")
                    }
                }
            } else {
                Text(
                    if (state.connected && state.motorTuningReadAvailable)
                        "Verbunden, aber noch keine vollständige FD…FE‑Antwort von 160C empfangen."
                    else "Nach dem Verbinden prüft die App automatisch Dienst 1600 und die Kanäle 160C/160D.",
                    style = MaterialTheme.typography.bodySmall
                )
            }

            if (state.motorTuningLastPacket.isNotBlank()) {
                Text("Letztes TX: ${state.motorTuningLastPacket}", style = MaterialTheme.typography.labelSmall)
            }
            if (state.motorTuningLastReadRaw.isNotBlank()) {
                Text("Letztes RX: ${state.motorTuningLastReadRaw}", style = MaterialTheme.typography.labelSmall)
            }
        }
    }

    if (resetDialogOpen) {
        AlertDialog(
            onDismissRequest = { resetDialogOpen = false },
            title = { Text("Werkprofil wirklich anfordern?") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        "Dabei sendet die App den in der Originalbibliothek gefundenen Reset‑Rahmen " +
                            "Profil‑Index + FF‑FF‑FF‑FF. Danach wird 160C zurückgelesen."
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    resetDialogOpen = false
                    profile?.let { manager.resetMotorTuning(it.index) }
                }) { Text("Werkprofil senden") }
            },
            dismissButton = {
                OutlinedButton(onClick = { resetDialogOpen = false }) { Text("Abbrechen") }
            }
        )
    }
}

private fun yesNo(value: Boolean): String = if (value) "ja" else "nein"

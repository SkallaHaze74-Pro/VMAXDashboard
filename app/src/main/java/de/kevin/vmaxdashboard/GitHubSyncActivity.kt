package de.kevin.vmaxdashboard

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

class GitHubSyncActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val sync = GitHubTelemetrySync.get(applicationContext).also { it.start() }
        setContent {
            MaterialTheme {
                GitHubSyncScreen(sync = sync, onClose = ::finish)
            }
        }
    }
}

@Composable
private fun GitHubSyncScreen(sync: GitHubTelemetrySync, onClose: () -> Unit) {
    var token by remember { mutableStateOf("") }
    var snapshot by remember { mutableStateOf(sync.snapshot()) }
    var localMessage by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        while (true) {
            snapshot = sync.snapshot()
            delay(750)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("VMAX Dashboard • GitHub Sync V7.5", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("Messfahrten und Decoder-AI-Ergebnisse automatisch sichern")

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text("Ziel", fontWeight = FontWeight.Bold)
                Text("Repository: SkallaHaze74-Pro/VMAXDashboard")
                Text("Daten-Branch: telemetry-data")
                Text("Ordner: fahrdaten/JJJJ-MM-TT/Messfahrt_…")
                Text("Der Daten-Branch löst keinen normalen main-Build aus.", style = MaterialTheme.typography.bodySmall)
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Automatischer Upload", fontWeight = FontWeight.Bold)
                        Text(if (snapshot.enabled) "Aktiv" else "Aus", style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(
                        checked = snapshot.enabled,
                        onCheckedChange = {
                            sync.setEnabled(it)
                            snapshot = sync.snapshot()
                        }
                    )
                }

                HorizontalDivider()
                Text("GitHub Fine-grained Token", fontWeight = FontWeight.Bold)
                Text(
                    "Einmalig einen Token für dieses Repository mit 'Contents: Read and write' eintragen. " +
                        "Der Token wird mit Android Keystore verschlüsselt und nicht in die APK oder Fahrdaten geschrieben.",
                    style = MaterialTheme.typography.bodySmall
                )
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(if (snapshot.tokenConfigured) "Neuen Token eintragen" else "Token eintragen") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true
                )
                Button(
                    onClick = {
                        runCatching { sync.saveToken(token) }
                            .onSuccess {
                                token = ""
                                localMessage = "✓ Token verschlüsselt gespeichert"
                            }
                            .onFailure { localMessage = it.message ?: "Token konnte nicht gespeichert werden" }
                        snapshot = sync.snapshot()
                    },
                    enabled = token.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (snapshot.tokenConfigured) "TOKEN ERSETZEN & SYNC STARTEN" else "TOKEN SPEICHERN & SYNC STARTEN")
                }

                if (snapshot.tokenConfigured) {
                    OutlinedButton(
                        onClick = {
                            sync.clearToken()
                            snapshot = sync.snapshot()
                            localMessage = "Token entfernt"
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("TOKEN ENTFERNEN") }
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text("Sync-Status", fontWeight = FontWeight.Bold)
                Text(snapshot.lastStatus.ifBlank { "Bereit" })
                Text("Offene Messfahrten: ${snapshot.pendingBundles}")
                Text("Erfolgreich hochgeladen: ${snapshot.uploadedBundles}")
                Text("Token: ${if (snapshot.tokenConfigured) "✓ eingerichtet" else "○ fehlt"}")
                OutlinedButton(
                    onClick = {
                        sync.retryNow()
                        localMessage = "Sync erneut angestoßen"
                    },
                    enabled = snapshot.enabled && snapshot.tokenConfigured,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("JETZT SYNCHRONISIEREN") }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Was automatisch hochgeladen wird", fontWeight = FontWeight.Bold)
                Text("• BLE_Rohdaten.csv")
                Text("• Live_Telemetrie.csv")
                Text("• Ereignisse.csv")
                Text("• Zusammenfassung.txt")
                Text("• Automatische_Analyse.txt")
                Text("• Lernprofil.json")
                Text("• manifest.json mit Max-Speed, Max-Leistung, Paket- und Markerzahl")
                Spacer(Modifier.height(2.dp))
                Text(
                    "Ohne Internet bleibt die komplette Fahrt in der internen Warteschlange. " +
                        "Sobald Android wieder ein Netz meldet, versucht die App den Upload automatisch erneut.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        if (localMessage.isNotBlank()) Text(localMessage, fontWeight = FontWeight.SemiBold)
        OutlinedButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text("SCHLIESSEN") }
        Spacer(Modifier.height(12.dp))
    }
}

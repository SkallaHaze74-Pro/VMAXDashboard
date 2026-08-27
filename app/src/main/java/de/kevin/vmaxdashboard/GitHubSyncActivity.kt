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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

class GitHubSyncActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val sync = GitHubTelemetrySync.get(applicationContext)
        val aiSync = DecoderAiCloudSync.get(applicationContext)
        setContent {
            VmaxDashboardTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    GitHubSyncScreen(sync = sync, aiSync = aiSync, onClose = ::finish)
                }
            }
        }
    }
}

@Composable
private fun GitHubSyncScreen(sync: GitHubTelemetrySync, aiSync: DecoderAiCloudSync, onClose: () -> Unit) {
    val appContext = LocalContext.current.applicationContext
    val adaptiveStore = remember(appContext) { AdaptiveDecoderProfileStore.get(appContext) }
    val externalAiSecrets = remember(appContext) { ExternalAiSecretsStore(appContext) }
    val autoReviewCoordinator = remember(appContext) { ExternalAiAutoReviewCoordinator.get(appContext) }
    var token by remember { mutableStateOf("") }
    var snapshot by remember { mutableStateOf(sync.snapshot()) }
    var aiProfile by remember { mutableStateOf(adaptiveStore.snapshot()) }
    var aiStatus by remember { mutableStateOf(aiSync.status()) }
    var localMessage by remember { mutableStateOf("") }

    var geminiKey by remember { mutableStateOf("") }
    var glmKey by remember { mutableStateOf("") }
    var externalAiStatus by remember { mutableStateOf(externalAiSecrets.status()) }
    var autoReview by remember { mutableStateOf(autoReviewCoordinator.snapshot()) }

    LaunchedEffect(Unit) {
        if (snapshot.enabled) {
            sync.start()
            aiSync.start()
        } else {
            autoReviewCoordinator.start()
        }
        while (true) {
            snapshot = sync.snapshot()
            aiProfile = adaptiveStore.snapshot()
            aiStatus = aiSync.status()
            externalAiStatus = externalAiSecrets.status()
            autoReview = autoReviewCoordinator.snapshot()
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
        Text(
            "VMAX Dashboard • GitHub & Decoder AI • ${BuildConfig.VERSION_NAME}",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        Text("Messfahrten sichern, automatisch vergleichen und bestätigte Decoder ohne neue APK übernehmen")

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text("Ziel", fontWeight = FontWeight.Bold)
                Text("Repository: SkallaHaze74-Pro/VMAXDashboard")
                Text("Daten-Branch: telemetry-data")
                Text("Fahrdaten: fahrdaten/JJJJ-MM-TT/Messfahrt_…")
                Text("KI-Profil: decoder-ai/decoder_profile.json")
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Adaptive Decoder AI", fontWeight = FontWeight.Bold)
                Text(aiStatus.status)
                Text("Profilquelle: ${aiProfile.source}")
                Text("Regeln: ${aiProfile.confirmedRuleCount} bestätigt / ${aiProfile.ruleCount} gesamt")
                if (aiProfile.revision.isNotBlank()) Text("Revision: ${aiProfile.revision}", style = MaterialTheme.typography.bodySmall)
                if (aiProfile.signals.isNotEmpty()) {
                    Text("Live lernbar: ${aiProfile.signals.sorted().joinToString(", ")}", style = MaterialTheme.typography.bodySmall)
                }
                Text(
                    "Die KI darf ausschließlich lesen/dekodieren. Sie erzeugt keine Motor- oder BLE-Schreibbefehle.",
                    style = MaterialTheme.typography.bodySmall
                )
                OutlinedButton(
                    onClick = {
                        aiSync.refreshNow()
                        localMessage = "Decoder-AI-Profil wird neu geprüft"
                    },
                    enabled = snapshot.enabled && snapshot.tokenConfigured,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("DECODER AI JETZT ABGLEICHEN") }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("READ-ONLY-KI ohne Mehrfachläufe", fontWeight = FontWeight.Bold)
                Text(
                    "Die App nutzt pro neuem Decoder-Evidenzstand genau einen Provider; GLM ist im Auto-Modus der Ausweichweg, falls Gemini ausfällt. Reine Sync-/Reconnect-Statusänderungen starten keine neue KI-Runde.",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    "Nach dem Upload prüfen Gemini und GLM den vollständigen RAW-/Power-/Deep-READ-Bericht je einmal unabhängig. GPT-5.6 Luna darf in GitHub optional nur diese zwei Entwürfe kurz ordnen – keine dritte Vollanalyse und kein drittes Evidenzvotum.",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    "Fallbacks: Gemini 3.7 → 3.6 → 3.5; GLM 5.3 → kostenlose 4.7/4.5. Nur vollständige, fingerprintgebundene Antworten werden gespeichert.",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    "OpenAI ist kostenpflichtig und läuft nur über das optionale GitHub-Secret OPENAI_API_KEY; ein bezahlter OpenAI-Key wird nicht in der APK gespeichert.",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    "Gemini: ${if (externalAiStatus.geminiConfigured) "✓ Key gespeichert" else "○ Key fehlt"} • " +
                        "GLM: ${if (externalAiStatus.glmConfigured) "✓ Key gespeichert" else "○ Key fehlt"}",
                    fontWeight = FontWeight.SemiBold
                )

                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Automatische KI-Zweitprüfung", fontWeight = FontWeight.Bold)
                        Text(if (autoReview.enabled) "Aktiv" else "Aus", style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(
                        checked = autoReview.enabled,
                        onCheckedChange = {
                            autoReviewCoordinator.setEnabled(it)
                            autoReview = autoReviewCoordinator.snapshot()
                        }
                    )
                }

                Text(autoReview.status)
                if (autoReview.lastProvider.isNotBlank()) {
                    Text(
                        "Letzte vollständige KI: ${autoReview.lastProvider} • ${autoReview.lastModel} • " +
                            if (autoReview.lastResultMatchesCurrentEvidence) "aktueller App-Evidenzstand" else "historischer Evidenzstand",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                if (autoReview.lastAttemptError.isNotBlank()) {
                    Text(
                        "Letzter Aktualisierungsfehler: ${autoReview.lastAttemptError}",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                if (autoReview.lastResult.isNotBlank()) {
                    HorizontalDivider()
                    Text("Letzte automatische Analyse", fontWeight = FontWeight.Bold)
                    Text(autoReview.lastResult)
                }

                HorizontalDivider()
                Text("Gemini API-Key", fontWeight = FontWeight.Bold)
                OutlinedTextField(
                    value = geminiKey,
                    onValueChange = { geminiKey = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(if (externalAiStatus.geminiConfigured) "Neuen Gemini-Key eintragen" else "Gemini-Key eintragen") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            runCatching { externalAiSecrets.saveGeminiKey(geminiKey) }
                                .onSuccess {
                                    geminiKey = ""
                                    externalAiStatus = externalAiSecrets.status()
                                    autoReviewCoordinator.requestIfEvidenceChanged("Gemini-Key gespeichert")
                                    localMessage = "✓ Gemini-Key gespeichert • Prüfung nur bei ungeprüfter Evidenz"
                                }
                                .onFailure { localMessage = it.message ?: "Gemini-Key konnte nicht gespeichert werden" }
                        },
                        enabled = geminiKey.isNotBlank(),
                        modifier = Modifier.weight(1f)
                    ) { Text("GEMINI SPEICHERN") }
                    OutlinedButton(
                        onClick = {
                            externalAiSecrets.clearGeminiKey()
                            externalAiStatus = externalAiSecrets.status()
                            localMessage = "Gemini-Key entfernt"
                        },
                        enabled = externalAiStatus.geminiConfigured,
                        modifier = Modifier.weight(1f)
                    ) { Text("ENTFERNEN") }
                }

                Text("GLM-5.3 API-Key", fontWeight = FontWeight.Bold)
                OutlinedTextField(
                    value = glmKey,
                    onValueChange = { glmKey = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(if (externalAiStatus.glmConfigured) "Neuen GLM-Key eintragen" else "GLM-Key eintragen") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            runCatching { externalAiSecrets.saveGlmKey(glmKey) }
                                .onSuccess {
                                    glmKey = ""
                                    externalAiStatus = externalAiSecrets.status()
                                    autoReviewCoordinator.requestIfEvidenceChanged("GLM-Key gespeichert")
                                    localMessage = "✓ GLM-Key gespeichert • Prüfung nur bei ungeprüfter Evidenz"
                                }
                                .onFailure { localMessage = it.message ?: "GLM-Key konnte nicht gespeichert werden" }
                        },
                        enabled = glmKey.isNotBlank(),
                        modifier = Modifier.weight(1f)
                    ) { Text("GLM SPEICHERN") }
                    OutlinedButton(
                        onClick = {
                            externalAiSecrets.clearGlmKey()
                            externalAiStatus = externalAiSecrets.status()
                            localMessage = "GLM-Key entfernt"
                        },
                        enabled = externalAiStatus.glmConfigured,
                        modifier = Modifier.weight(1f)
                    ) { Text("ENTFERNEN") }
                }

                Text(
                    "Die Keys stehen nicht im Quellcode und werden nicht zu GitHub hochgeladen. Für eine öffentlich verteilte APK bleibt ein eigener Backend-Proxy die stärkere Sicherheitsstufe.",
                    style = MaterialTheme.typography.bodySmall
                )

                OutlinedButton(
                    onClick = {
                        val accepted = autoReviewCoordinator.requestNow("Optionale Sofortprüfung")
                        autoReview = autoReviewCoordinator.snapshot()
                        localMessage = if (accepted) {
                            "Einmalige KI-Prüfung für den aktuellen Stand angestoßen"
                        } else {
                            "KI-Prüfung läuft oder wartet bereits • kein weiterer Lauf eingereiht"
                        }
                    },
                    enabled = autoReview.enabled && !autoReview.running &&
                        (externalAiStatus.geminiConfigured || externalAiStatus.glmConfigured),
                    modifier = Modifier.fillMaxWidth()
                ) { Text("OPTIONAL: EINMAL ERNEUT PRÜFEN") }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Datenschutz-Hinweis", fontWeight = FontWeight.Bold)
                Text(
                    "Wenn das GitHub-Repository öffentlich ist, sind auch die hochgeladenen Fahrdaten öffentlich sichtbar. " +
                        "Der Sync ergänzt weder GPS-Koordinaten noch die Bluetooth-Adresse; Zeitstempel und Fahrtelemetrie bleiben jedoch in den Dateien enthalten. " +
                        "Deep-READ-Rohdaten können außerdem vom Gerät gelieferte Serien-/Firmware-Identität enthalten; Provider-Berichte blenden bekannte Identitäts-Payloads aus.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Automatischer Upload & KI-Abgleich", fontWeight = FontWeight.Bold)
                        Text(if (snapshot.enabled) "Aktiv" else "Aus", style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(
                        checked = snapshot.enabled,
                        onCheckedChange = {
                            sync.setEnabled(it)
                            if (it) {
                                sync.start()
                                aiSync.refreshNow()
                            }
                            snapshot = sync.snapshot()
                        }
                    )
                }

                HorizontalDivider()
                Text("GitHub Fine-grained Token", fontWeight = FontWeight.Bold)
                Text(
                    "Nur VMAXDashboard mit 'Contents: Read and write'. Der Token bleibt mit Android Keystore verschlüsselt auf diesem Handy.",
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
                                sync.start()
                                aiSync.refreshNow()
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
                Text("Offene Uploadobjekte: ${snapshot.pendingBundles}")
                Text("Erfolgreich hochgeladene Messfahrten: ${snapshot.uploadedBundles}")
                Text("Token: ${if (snapshot.tokenConfigured) "✓ eingerichtet" else "○ fehlt"}")
                OutlinedButton(
                    onClick = {
                        sync.start()
                        sync.retryNow()
                        aiSync.refreshNow()
                        localMessage = "Upload und Decoder AI erneut angestoßen • externe KI nur bei neuer Evidenz"
                    },
                    enabled = snapshot.enabled && snapshot.tokenConfigured,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("JETZT ALLES SYNCHRONISIEREN") }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Was automatisch ausgewertet wird", fontWeight = FontWeight.Bold)
                Text("• BLE_Rohdaten.csv – jedes empfangene Byte")
                Text("• Live_Telemetrie.csv – bestätigte Referenzwerte")
                Text("• Ereignisse.csv – Bremse, Blinker, Licht, Laden usw.")
                Text("• Lernprofil.json – lokale Kandidaten über mehrere Fahrten")
                Text("• Numerische Korrelationen für weitere Speed/Akku/Volt/Strom/Temperatur/Weg-Felder")
                Text("• App: ein Provider pro Decoder-Evidenzstand, zweiter nur als Fallback")
                Text("• GitHub: Gemini + GLM je einmal; GPT optional nur als kurze Synthese")
                Spacer(Modifier.height(2.dp))
                Text(
                    "Neue Regeln werden erst nach hoher Konfidenz live verwendet. Unsichere Treffer bleiben Kandidaten.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        if (localMessage.isNotBlank()) Text(localMessage, fontWeight = FontWeight.SemiBold)
        OutlinedButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text("SCHLIESSEN") }
        Spacer(Modifier.height(12.dp))
    }
}

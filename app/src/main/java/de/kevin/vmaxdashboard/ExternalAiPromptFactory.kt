package de.kevin.vmaxdashboard

import java.nio.ByteBuffer
import java.security.MessageDigest

object ExternalAiPromptFactory {
    fun decoderContext(
        sync: GitHubSyncSnapshot,
        profile: AdaptiveProfileSnapshot
    ): String = buildString {
        appendLine("VMAXDashboard Decoder-Status")
        appendLine("Sync aktiviert: ${sync.enabled}")
        appendLine("Offene Messfahrten: ${sync.pendingBundles}")
        appendLine("Hochgeladene Messfahrten: ${sync.uploadedBundles}")
        appendLine("Letzter Sync-Status: ${sync.lastStatus.ifBlank { "unbekannt" }.take(300)}")
        appendLine("Decoder-Quelle: ${profile.source}")
        appendLine("Decoder-Revision: ${profile.revision.ifBlank { "keine" }}")
        appendLine("Regeln: ${profile.confirmedRuleCount} bestätigt / ${profile.ruleCount} gesamt")
        appendLine("Vertrauen: ${profile.confidenceSummary}")
        appendLine("Signale: ${profile.signals.sorted().joinToString(", ").ifBlank { "keine" }}")
        appendLine()
        appendLine("Ladeverhalten: Beim Einstecken schaltet der Controller normalerweise ab und BLE verschwindet. Kein Live-Monitoring während des Ladens fordern; nur Zustand davor, eventuelles kurzes READ-/Notify-Fenster nach POWER und Zustand nach Abziehen/Reconnect vergleichen. Ein kurzer Reconnect allein beweist keinen Ladezustand.")
        appendLine("Kontextgrenze: Diese App-Prüfung sieht nur Profil-/Sync-Metadaten. RAW-, Power- und Deep-READ-Evidenz wird separat im GitHub-Workflow geprüft.")
        appendLine("Wichtig: API-Schlüssel, GitHub-Token, Bluetooth-Adresse und GPS-Daten sind absichtlich nicht enthalten.")
    }

    /** Binds freshness to the exact request text, mode and output contract. */
    fun reviewInputFingerprint(
        prompt: String,
        context: String,
        mode: ExternalAiMode
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        listOf(
            externalAiReviewContractDescriptor(),
            mode.name,
            prompt,
            context
        ).forEach { value ->
            val bytes = value.toByteArray(Charsets.UTF_8)
            digest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(bytes.size).array())
            digest.update(bytes)
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xFF) }
    }
}

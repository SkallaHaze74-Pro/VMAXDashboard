package de.kevin.vmaxdashboard

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
        appendLine("Wichtig: API-Schlüssel, GitHub-Token, Bluetooth-Adresse und GPS-Daten sind absichtlich nicht enthalten.")
    }
}

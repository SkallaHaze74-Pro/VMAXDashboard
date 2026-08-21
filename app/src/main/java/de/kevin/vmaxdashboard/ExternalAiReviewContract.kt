package de.kevin.vmaxdashboard

import java.io.IOException

internal const val EXTERNAL_AI_REVIEW_FOOTER = "Freigabe: keine automatische Änderung."
internal const val MAX_EXTERNAL_AI_REVIEW_CHARS = 18_000
internal const val EXTERNAL_AI_REVIEW_CONTRACT_ID = "vmax-external-ai-review-contract-v3"

internal val EXTERNAL_AI_REQUIRED_SECTIONS = listOf(
    "Belastbare Evidenz",
    "Konflikte / mögliche Bugs",
    "Hypothesen (nicht bestätigt)",
    "Nächste sichere READ-ONLY-Tests",
    "Automatische Änderungen: KEINE"
)

internal class IncompleteExternalAiReviewException(message: String) : IOException(message)

internal fun externalAiReviewIsComplete(text: String): Boolean {
    val clean = text.trim()
    if (
        clean.length < 240 ||
        clean.length > MAX_EXTERNAL_AI_REVIEW_CHARS ||
        !clean.endsWith(EXTERNAL_AI_REVIEW_FOOTER)
    ) return false
    return EXTERNAL_AI_REQUIRED_SECTIONS.all { section -> clean.contains(section, ignoreCase = true) }
}

internal fun requireCompleteExternalAiReview(text: String): String {
    val clean = text.trim()
    if (!externalAiReviewIsComplete(clean)) {
        val detail = if (clean.length > MAX_EXTERNAL_AI_REVIEW_CHARS) {
            "Antwort ist zu lang (${clean.length} > $MAX_EXTERNAL_AI_REVIEW_CHARS) und wird nicht gekürzt"
        } else {
            "Pflichtabschnitt oder Abschlussmarker fehlt"
        }
        throw IncompleteExternalAiReviewException(
            "Unvollständige KI-Prüfung verworfen: $detail"
        )
    }
    return clean
}

internal fun prepareExternalAiReviewForPublication(text: String): String {
    val redacted = text
        .replace(Regex("gh[pousr]_[A-Za-z0-9_]+"), "<redacted-github-token>")
        .replace(Regex("AIza[0-9A-Za-z_-]{20,}"), "<redacted-google-key>")
        .replace(Regex("sk-[A-Za-z0-9_-]{20,}"), "<redacted-api-key>")
    return requireCompleteExternalAiReview(redacted)
}

internal fun requireCompletedGeminiInteractionStatus(status: String): String {
    if (status != "completed") {
        throw IncompleteExternalAiReviewException(
            "Gemini-Interaktion ist nicht vollständig (status=${status.ifBlank { "fehlt" }})"
        )
    }
    return status
}

internal fun requireCompletedGlmFinishReason(finishReason: String): String {
    if (finishReason !in setOf("stop", "completed")) {
        throw IncompleteExternalAiReviewException(
            "GLM-Antwort ist nicht vollständig (finish_reason=${finishReason.ifBlank { "fehlt" }})"
        )
    }
    return finishReason
}

internal fun externalAiReviewContractDescriptor(): String = buildString {
    appendLine(EXTERNAL_AI_REVIEW_CONTRACT_ID)
    EXTERNAL_AI_REQUIRED_SECTIONS.forEach { section -> appendLine(section) }
    append(EXTERNAL_AI_REVIEW_FOOTER)
}

/** Bounded fail-closed result when two valid drafts could not be synthesized. */
internal fun buildUnsynthesizedDuoReview(geminiModel: String, glmModel: String): String = buildString {
    appendLine("Belastbare Evidenz")
    appendLine("- Gemini ($geminiModel) und GLM ($glmModel) lieferten jeweils eine vollständige Antwort; daraus wurde keine gemeinsame Aussage automatisch übernommen.")
    appendLine("Konflikte / mögliche Bugs")
    appendLine("- Die gemeinsame Synthese war nicht verfügbar. Einzelantworten werden nicht verkettet oder als gegenseitige Bestätigung behandelt.")
    appendLine("Hypothesen (nicht bestätigt)")
    appendLine("- Ohne vollständige Synthese bleibt jede Modellinterpretation unveröffentlicht und unbestätigt.")
    appendLine("Nächste sichere READ-ONLY-Tests")
    appendLine("- Pro-Duo-Prüfung später mit identischem Evidenz-Fingerprint erneut ausführen; Messdaten und deterministische Guards bleiben maßgeblich.")
    appendLine("Automatische Änderungen: KEINE")
    append(EXTERNAL_AI_REVIEW_FOOTER)
}

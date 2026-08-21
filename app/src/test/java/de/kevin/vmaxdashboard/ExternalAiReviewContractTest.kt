package de.kevin.vmaxdashboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class ExternalAiReviewContractTest {
    private fun completeReview(): String = buildString {
        appendLine("Belastbare Evidenz")
        appendLine("- Messdaten und deterministische Guards bleiben allein maßgeblich; Details werden konkret benannt.")
        appendLine("Konflikte / mögliche Bugs")
        appendLine("- Keine unabhängige Evidenz darf durch Modell-Konsens ersetzt werden.")
        appendLine("Hypothesen (nicht bestätigt)")
        appendLine("- Offene Kandidaten bleiben ausdrücklich unbestätigt und werden nicht aktiviert.")
        appendLine("Nächste sichere READ-ONLY-Tests")
        appendLine("- Einen reproduzierbaren Stillstandstest mit getrenntem Diagnose-Dump durchführen.")
        appendLine("Automatische Änderungen: KEINE")
        append(EXTERNAL_AI_REVIEW_FOOTER)
    }

    @Test
    fun completeStructuredReviewPasses() {
        assertTrue(externalAiReviewIsComplete(completeReview()))
    }

    @Test
    fun footerAloneOrMissingSectionCannotBecomeLastGood() {
        assertFalse(externalAiReviewIsComplete("Junk\n$EXTERNAL_AI_REVIEW_FOOTER"))
        assertFalse(
            externalAiReviewIsComplete(
                completeReview().replace("Automatische Änderungen: KEINE\n", "")
            )
        )
    }

    @Test
    fun oversizedReviewIsRejectedBeforeItCanBecomeLastGood() {
        val oversized = completeReview().replace(
            "Automatische Änderungen: KEINE",
            "${"x".repeat(MAX_EXTERNAL_AI_REVIEW_CHARS)}\nAutomatische Änderungen: KEINE"
        )

        assertFalse(externalAiReviewIsComplete(oversized))
        assertThrows(IncompleteExternalAiReviewException::class.java) {
            requireCompleteExternalAiReview(oversized)
        }
    }

    @Test
    fun publicationRedactionPreservesAndRevalidatesExactOutput() {
        val withToken = completeReview().replace(
            "Messdaten",
            "Messdaten ghp_ABCDEFGHIJKLMNOPQRSTUVWXYZ1234567890"
        )

        val prepared = prepareExternalAiReviewForPublication(withToken)

        assertTrue(externalAiReviewIsComplete(prepared))
        assertFalse(prepared.contains("ghp_"))
        assertTrue(prepared.contains("<redacted-github-token>"))
        assertEquals(prepared, requireCompleteExternalAiReview(prepared))
    }

    @Test
    fun providerCompletionStatusMustBeExplicit() {
        assertEquals("completed", requireCompletedGeminiInteractionStatus("completed"))
        assertEquals("stop", requireCompletedGlmFinishReason("stop"))
        assertEquals("completed", requireCompletedGlmFinishReason("completed"))
        assertThrows(IncompleteExternalAiReviewException::class.java) {
            requireCompletedGeminiInteractionStatus("")
        }
        assertThrows(IncompleteExternalAiReviewException::class.java) {
            requireCompletedGeminiInteractionStatus("in_progress")
        }
        assertThrows(IncompleteExternalAiReviewException::class.java) {
            requireCompletedGeminiInteractionStatus("COMPLETED")
        }
        assertThrows(IncompleteExternalAiReviewException::class.java) {
            requireCompletedGlmFinishReason("")
        }
        assertThrows(IncompleteExternalAiReviewException::class.java) {
            requireCompletedGlmFinishReason("length")
        }
        assertThrows(IncompleteExternalAiReviewException::class.java) {
            requireCompletedGlmFinishReason("STOP")
        }
    }

    @Test
    fun duoPromotionRequiresBothProviderOpinions() {
        assertTrue(shouldPromoteExternalAiAnswer(ExternalAiMode.AUTO, providerCount = 1))
        assertFalse(shouldPromoteExternalAiAnswer(ExternalAiMode.PRO_DUO, providerCount = 1))
        assertTrue(shouldPromoteExternalAiAnswer(ExternalAiMode.PRO_DUO, providerCount = 2))
    }

    @Test
    fun automaticAppReviewUsesOneProviderWithFailoverEvenWhenBothKeysExist() {
        assertEquals(
            ExternalAiMode.AUTO,
            selectAutomaticExternalAiMode(
                ExternalAiSecretStatus(
                    geminiConfigured = true,
                    glmConfigured = true
                )
            )
        )
    }

    @Test
    fun geminiFallbackOrderIsStableAcrossAutoAndDuo() {
        assertEquals(
            listOf("gemini-3.7-flash", "gemini-3.6-flash", "gemini-3.5-flash-lite"),
            GEMINI_MODEL_FALLBACK_ORDER
        )
    }
}

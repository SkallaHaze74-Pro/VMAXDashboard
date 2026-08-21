package de.kevin.vmaxdashboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ExternalAiPromptFactoryTest {
    private val profile = AdaptiveProfileSnapshot(
        revision = "rev-1",
        ruleCount = 5,
        confirmedRuleCount = 4,
        generatedAtMs = 123L,
        source = "GitHub-Konsens",
        signals = setOf("speedKmh", "voltageV"),
        confidenceSummary = "Hoch: 4"
    )

    private val sync = GitHubSyncSnapshot(
        enabled = true,
        tokenConfigured = true,
        pendingBundles = 0,
        uploadedBundles = 13,
        lastStatus = "alles synchron"
    )

    @Test
    fun fingerprintIsSha256OfEvidenceModeAndContract() {
        val first = ExternalAiPromptFactory.reviewEvidenceFingerprint(
            prompt = "Prüfe exakt",
            profile = profile,
            mode = ExternalAiMode.PRO_DUO
        )

        assertEquals(64, first.length)
        assertEquals(
            first,
            ExternalAiPromptFactory.reviewEvidenceFingerprint(
                prompt = "Prüfe exakt",
                profile = profile,
                mode = ExternalAiMode.PRO_DUO
            )
        )
        assertNotEquals(
            first,
            ExternalAiPromptFactory.reviewEvidenceFingerprint(
                prompt = "Prüfe exakt geändert",
                profile = profile,
                mode = ExternalAiMode.PRO_DUO
            )
        )
        assertNotEquals(
            first,
            ExternalAiPromptFactory.reviewEvidenceFingerprint(
                prompt = "Prüfe exakt",
                profile = profile,
                mode = ExternalAiMode.AUTO
            )
        )
    }

    @Test
    fun transportStatusChangesDoNotTriggerAnotherProviderRun() {
        val baseContext = ExternalAiPromptFactory.decoderContext(sync, profile)
        val changedPending = sync.copy(pendingBundles = 1)
        val changedStatus = sync.copy(lastStatus = "temporärer Fehler")
        val changedPendingContext = ExternalAiPromptFactory.decoderContext(changedPending, profile)
        val changedStatusContext = ExternalAiPromptFactory.decoderContext(changedStatus, profile)
        val base = ExternalAiPromptFactory.reviewEvidenceFingerprint(
            "Prüfe exakt",
            profile,
            ExternalAiMode.PRO_DUO
        )

        assertNotEquals(baseContext, changedPendingContext)
        assertNotEquals(baseContext, changedStatusContext)
        assertEquals(
            base,
            ExternalAiPromptFactory.reviewEvidenceFingerprint(
                "Prüfe exakt",
                profile,
                ExternalAiMode.PRO_DUO
            )
        )
    }

    @Test
    fun decoderEvidenceChangeInvalidatesFingerprint() {
        val base = ExternalAiPromptFactory.reviewEvidenceFingerprint(
            "Prüfe exakt",
            profile,
            ExternalAiMode.PRO_DUO
        )
        val changedSignals = profile.copy(signals = profile.signals + "currentA")

        assertNotEquals(
            base,
            ExternalAiPromptFactory.reviewEvidenceFingerprint(
                "Prüfe exakt",
                changedSignals,
                ExternalAiMode.PRO_DUO
            )
        )
    }
}

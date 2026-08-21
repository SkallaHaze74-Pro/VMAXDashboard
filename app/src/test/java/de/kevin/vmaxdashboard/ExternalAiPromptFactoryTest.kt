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
    fun fingerprintIsSha256OfExactPromptContextModeAndContract() {
        val context = ExternalAiPromptFactory.decoderContext(sync, profile)
        val first = ExternalAiPromptFactory.reviewInputFingerprint(
            prompt = "Prüfe exakt",
            context = context,
            mode = ExternalAiMode.PRO_DUO
        )

        assertEquals(64, first.length)
        assertEquals(
            first,
            ExternalAiPromptFactory.reviewInputFingerprint(
                prompt = "Prüfe exakt",
                context = context,
                mode = ExternalAiMode.PRO_DUO
            )
        )
        assertNotEquals(
            first,
            ExternalAiPromptFactory.reviewInputFingerprint(
                prompt = "Prüfe exakt geändert",
                context = context,
                mode = ExternalAiMode.PRO_DUO
            )
        )
        assertNotEquals(
            first,
            ExternalAiPromptFactory.reviewInputFingerprint(
                prompt = "Prüfe exakt",
                context = context,
                mode = ExternalAiMode.AUTO
            )
        )
    }

    @Test
    fun everyPromptContextChangeInvalidatesFingerprint() {
        val baseContext = ExternalAiPromptFactory.decoderContext(sync, profile)
        val base = ExternalAiPromptFactory.reviewInputFingerprint(
            "Prüfe exakt",
            baseContext,
            ExternalAiMode.PRO_DUO
        )
        val changedPending = sync.copy(pendingBundles = 1)
        val changedStatus = sync.copy(lastStatus = "temporärer Fehler")
        val changedSignals = profile.copy(signals = profile.signals + "currentA")

        assertNotEquals(
            base,
            ExternalAiPromptFactory.reviewInputFingerprint(
                "Prüfe exakt",
                ExternalAiPromptFactory.decoderContext(changedPending, profile),
                ExternalAiMode.PRO_DUO
            )
        )
        assertNotEquals(
            base,
            ExternalAiPromptFactory.reviewInputFingerprint(
                "Prüfe exakt",
                ExternalAiPromptFactory.decoderContext(changedStatus, profile),
                ExternalAiMode.PRO_DUO
            )
        )
        assertNotEquals(
            base,
            ExternalAiPromptFactory.reviewInputFingerprint(
                "Prüfe exakt",
                ExternalAiPromptFactory.decoderContext(sync, changedSignals),
                ExternalAiMode.PRO_DUO
            )
        )
    }
}

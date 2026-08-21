package de.kevin.vmaxdashboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalAiReviewRequestGateTest {
    @Test
    fun rapidManualRequestsProduceOnlyOneProviderEligibleRequest() {
        val gate = ExternalAiReviewRequestGate()

        assertTrue(gate.submit(force = true, reason = "Tap 1"))
        assertFalse(gate.submit(force = true, reason = "Tap 2"))
        assertFalse(gate.submit(force = true, reason = "Tap 3"))

        assertEquals(ExternalAiQueuedReview(force = true, reason = "Tap 1"), gate.current())
        assertNull(gate.completeAndTakeNext())
        assertFalse(gate.isBusy())
    }

    @Test
    fun evidenceChangesAreCoalescedBehindOneActiveRequest() {
        val gate = ExternalAiReviewRequestGate()

        assertTrue(gate.submit(force = false, reason = "App-Start"))
        assertFalse(gate.submit(force = false, reason = "Cloud-Profil A"))
        assertFalse(gate.submit(force = false, reason = "Cloud-Profil B"))

        assertEquals(
            ExternalAiQueuedReview(force = false, reason = "Cloud-Profil B"),
            gate.completeAndTakeNext()
        )
        assertNull(gate.completeAndTakeNext())
    }

    @Test
    fun manualRequestCanBeAcceptedAgainOnlyAfterActiveRequestFinishes() {
        val gate = ExternalAiReviewRequestGate()

        assertTrue(gate.submit(force = true, reason = "Erster Lauf"))
        assertNull(gate.completeAndTakeNext())
        assertTrue(gate.submit(force = true, reason = "Bewusster neuer Lauf"))
    }
}

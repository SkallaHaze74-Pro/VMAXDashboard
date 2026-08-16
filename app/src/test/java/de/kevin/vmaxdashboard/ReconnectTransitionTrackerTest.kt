package de.kevin.vmaxdashboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReconnectTransitionTrackerTest {
    @Test
    fun linkReconnectAndFreshTelemetryAreSeparateTransitions() {
        val tracker = ReconnectTransitionTracker(initialConnected = true)

        assertEquals(
            listOf(ReconnectTransition.UNEXPECTED_DISCONNECT),
            tracker.observe(connected = false, telemetryReady = false, recordingActive = true)
        )
        assertEquals(
            listOf(ReconnectTransition.LINK_RESTORED),
            tracker.observe(connected = true, telemetryReady = false, recordingActive = true)
        )
        assertEquals(
            listOf(ReconnectTransition.TELEMETRY_RESTORED),
            tracker.observe(connected = true, telemetryReady = true, recordingActive = true)
        )
        assertTrue(tracker.observe(true, true, true).isEmpty())
    }

    @Test
    fun manualDisconnectDoesNotCreateAutomaticReconnectTransitions() {
        val tracker = ReconnectTransitionTracker(initialConnected = true)

        assertTrue(tracker.observe(connected = false, telemetryReady = false, recordingActive = false).isEmpty())
    }

    @Test
    fun secondDropBeforeTelemetryStartsANewLinkWait() {
        val tracker = ReconnectTransitionTracker(initialConnected = true)

        tracker.observe(false, false, true)
        tracker.observe(true, false, true)
        assertEquals(
            listOf(ReconnectTransition.UNEXPECTED_DISCONNECT),
            tracker.observe(false, false, true)
        )
        assertEquals(
            listOf(ReconnectTransition.LINK_RESTORED),
            tracker.observe(true, false, true)
        )
        assertEquals(
            listOf(ReconnectTransition.TELEMETRY_RESTORED),
            tracker.observe(true, true, true)
        )
    }

    @Test
    fun stoppingMeasurementClearsPendingReconnect() {
        val tracker = ReconnectTransitionTracker(initialConnected = true)

        tracker.observe(false, false, true)
        assertEquals(
            listOf(ReconnectTransition.CLEAR_PENDING),
            tracker.observe(false, false, false)
        )
        assertTrue(tracker.observe(true, true, false).isEmpty())
    }
}

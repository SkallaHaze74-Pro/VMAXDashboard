package de.kevin.vmaxdashboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfirmedRideEventTrackerTest {
    @Test
    fun firstSampleAndDuplicatesOnlyEstablishConfirmedBaselines() {
        val tracker = ConfirmedRideEventTracker()

        assertTrue(tracker.observe(1L, "1508", lightRaw = 0, rideModeRaw = 1).isEmpty())
        assertTrue(tracker.observe(1L, "1508", lightRaw = 0, rideModeRaw = 1).isEmpty())
    }

    @Test
    fun confirmedLightTransitionsProduceDeterministicMarkers() {
        val tracker = ConfirmedRideEventTracker()
        tracker.observe(1L, "1508", lightRaw = 0, rideModeRaw = 1)

        assertEquals(
            listOf("AUTO_BESTÄTIGT • Licht AUS → AN • 1508/0"),
            tracker.observe(1L, "1508", lightRaw = 1, rideModeRaw = 1)
        )
        assertEquals(
            listOf("AUTO_BESTÄTIGT • Licht AN → AUS • 1508/0"),
            tracker.observe(1L, "1508", lightRaw = 0, rideModeRaw = 1)
        )
    }

    @Test
    fun confirmedRideModeTransitionsProduceDeterministicMarkers() {
        val tracker = ConfirmedRideEventTracker()
        tracker.observe(1L, "1508", lightRaw = 0, rideModeRaw = 1)

        assertEquals(
            listOf("AUTO_BESTÄTIGT • Fahrmodus ECO → SPORT • 1508/3"),
            tracker.observe(1L, "1508", lightRaw = 0, rideModeRaw = 2)
        )
        assertEquals(
            listOf("AUTO_BESTÄTIGT • Fahrmodus SPORT → ECO • 1508/3"),
            tracker.observe(1L, "1508", lightRaw = 0, rideModeRaw = 1)
        )
    }

    @Test
    fun simultaneousChangesKeepLightBeforeRideMode() {
        val tracker = ConfirmedRideEventTracker()
        tracker.observe(1L, "1508", lightRaw = 0, rideModeRaw = 1)

        assertEquals(
            listOf(
                "AUTO_BESTÄTIGT • Licht AUS → AN • 1508/0",
                "AUTO_BESTÄTIGT • Fahrmodus ECO → SPORT • 1508/3"
            ),
            tracker.observe(1L, "1508", lightRaw = 1, rideModeRaw = 2)
        )
    }

    @Test
    fun unknownValuesInvalidateOnlyTheirOwnBaseline() {
        val tracker = ConfirmedRideEventTracker()
        tracker.observe(1L, "1508", lightRaw = 0, rideModeRaw = 1)

        assertTrue(tracker.observe(1L, "1508", lightRaw = 7, rideModeRaw = 1).isEmpty())
        assertTrue(tracker.observe(1L, "1508", lightRaw = 1, rideModeRaw = 1).isEmpty())
        assertTrue(tracker.observe(1L, "1508", lightRaw = 1, rideModeRaw = 9).isEmpty())
        assertTrue(tracker.observe(1L, "1508", lightRaw = 1, rideModeRaw = 2).isEmpty())

        assertEquals(
            listOf(
                "AUTO_BESTÄTIGT • Licht AN → AUS • 1508/0",
                "AUTO_BESTÄTIGT • Fahrmodus SPORT → ECO • 1508/3"
            ),
            tracker.observe(1L, "1508", lightRaw = 0, rideModeRaw = 1)
        )
    }

    @Test
    fun non1508PacketsCannotCreateOrAlterConfirmedBaselines() {
        val tracker = ConfirmedRideEventTracker()

        assertTrue(tracker.observe(1L, "1509", lightRaw = 0, rideModeRaw = 1).isEmpty())
        assertTrue(tracker.observe(1L, "1508", lightRaw = 0, rideModeRaw = 1).isEmpty())
        assertTrue(tracker.observe(1L, "1509", lightRaw = 1, rideModeRaw = 2).isEmpty())
        assertEquals(
            listOf(
                "AUTO_BESTÄTIGT • Licht AUS → AN • 1508/0",
                "AUTO_BESTÄTIGT • Fahrmodus ECO → SPORT • 1508/3"
            ),
            tracker.observe(1L, "1508", lightRaw = 1, rideModeRaw = 2)
        )
    }

    @Test
    fun reconnectStartsANewBaselineWithoutInventingEvents() {
        val tracker = ConfirmedRideEventTracker()
        tracker.observe(1L, "1508", lightRaw = 0, rideModeRaw = 1)
        tracker.observe(1L, "1508", lightRaw = 1, rideModeRaw = 2)

        assertTrue(tracker.observe(2L, "1508", lightRaw = 0, rideModeRaw = 1).isEmpty())
        assertEquals(
            listOf("AUTO_BESTÄTIGT • Licht AUS → AN • 1508/0"),
            tracker.observe(2L, "1508", lightRaw = 1, rideModeRaw = 1)
        )
    }
}

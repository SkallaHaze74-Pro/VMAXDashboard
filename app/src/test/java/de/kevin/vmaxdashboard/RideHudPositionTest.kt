package de.kevin.vmaxdashboard

import org.junit.Assert.assertEquals
import org.junit.Test

class RideHudPositionTest {
    @Test
    fun dragKeepsWholeHudInsideSafeDisplayBounds() {
        assertEquals(
            RideHudPosition(x = 0, y = 0),
            moveRideHudPosition(
                start = RideHudPosition(20, 30),
                deltaX = -100,
                deltaY = -100,
                displayWidth = 1_080,
                displayHeight = 2_400,
                hudWidth = 300,
                hudHeight = 180
            )
        )
        assertEquals(
            RideHudPosition(x = 780, y = 2_220),
            moveRideHudPosition(
                start = RideHudPosition(700, 2_100),
                deltaX = 500,
                deltaY = 500,
                displayWidth = 1_080,
                displayHeight = 2_400,
                hudWidth = 300,
                hudHeight = 180
            )
        )
    }
}

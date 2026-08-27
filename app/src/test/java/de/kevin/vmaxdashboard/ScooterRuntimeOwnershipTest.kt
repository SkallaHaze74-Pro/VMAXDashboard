package de.kevin.vmaxdashboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScooterRuntimeOwnershipTest {
    @Test
    fun sharedManagerDisconnectsOnlyAfterLastOwnerReleases() {
        var disconnects = 0
        val ownership = ScooterRuntimeOwnership(onNoOwners = { disconnects++ })
        val dashboard = ownership.acquire()
        val hud = ownership.acquire()

        assertEquals(2, ownership.ownerCount)
        assertTrue(ownership.release(dashboard))
        assertEquals(0, disconnects)
        assertEquals(1, ownership.ownerCount)
        assertFalse(ownership.release(dashboard))
        assertEquals(0, disconnects)

        assertTrue(ownership.release(hud))
        assertEquals(1, disconnects)
        assertEquals(0, ownership.ownerCount)
    }

    @Test
    fun closingHudDoesNotCloseRunningDashboardOrItsBleSession() {
        var disconnects = 0
        val ownership = ScooterRuntimeOwnership(onNoOwners = { disconnects++ })
        val dashboard = ownership.acquire()
        val hud = ownership.acquire()

        assertTrue(ownership.release(hud))
        assertEquals(0, disconnects)
        assertEquals(1, ownership.ownerCount)

        assertTrue(ownership.release(dashboard))
        assertEquals(1, disconnects)
    }
}

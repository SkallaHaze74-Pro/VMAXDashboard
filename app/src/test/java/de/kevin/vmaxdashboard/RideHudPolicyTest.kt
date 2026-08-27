package de.kevin.vmaxdashboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RideHudPolicyTest {
    @Test
    fun explicitEnableWithoutPermissionRequestsSettingsButNeverStartsService() {
        assertEquals(
            RideHudAction.REQUEST_OVERLAY_PERMISSION,
            rideHudAction(
                requestedEnabled = true,
                overlayPermissionGranted = false,
                overlayVisible = false,
                userInitiated = true
            )
        )
        assertEquals(
            RideHudAction.NONE,
            rideHudAction(
                requestedEnabled = true,
                overlayPermissionGranted = false,
                overlayVisible = false,
                userInitiated = false
            )
        )
    }

    @Test
    fun grantedPermissionStartsExactlyOnce() {
        assertEquals(
            RideHudAction.SHOW_OVERLAY,
            rideHudAction(true, true, overlayVisible = false, userInitiated = true)
        )
        assertEquals(
            RideHudAction.NONE,
            rideHudAction(true, true, overlayVisible = true, userInitiated = false)
        )
    }

    @Test
    fun disableOrRevokedPermissionStopsWithoutOpeningSettingsLoop() {
        assertEquals(
            RideHudAction.HIDE_OVERLAY,
            rideHudAction(false, true, overlayVisible = true, userInitiated = true)
        )
        assertEquals(
            RideHudAction.HIDE_OVERLAY,
            rideHudAction(true, false, overlayVisible = true, userInitiated = false)
        )
    }

    @Test
    fun closeHidesOnlyOverlayAndKeepsBackgroundServiceRunning() {
        val hidden = rideHudHiddenRuntimeState("Mini-HUD ausgeblendet")

        assertFalse(hidden.active)
        assertTrue(hidden.serviceRunning)
        assertEquals("Mini-HUD ausgeblendet", hidden.message)
    }

    @Test
    fun deniedNotificationNeverOverwritesASynchronousStartFailure() {
        assertEquals(null, rideHudDeniedNotificationStatus(startAccepted = false, overlayVisible = false))
        assertEquals(
            RideHudRuntimeState(
                active = false,
                message = "Mini-HUD wird gestartet; Begleitmeldung nur im Android-Task-Manager"
            ),
            rideHudDeniedNotificationStatus(startAccepted = true, overlayVisible = false)
        )
    }
}

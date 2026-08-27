package de.kevin.vmaxdashboard

import org.junit.Assert.assertEquals
import org.junit.Test

class RideHudReconnectPolicyTest {
    @Test
    fun periodicTickRetriesAfterBluetoothWasOffWithoutNeedingAStateEmission() {
        assertEquals(
            RideHudCaptureAction.START_SCAN,
            rideHudCaptureAction(
                connectionDesired = true,
                connected = false,
                scanning = false,
                scanStartedAtElapsedMs = 0L,
                recordingActive = true,
                recordingDesired = true,
                permissionsGranted = true,
                nowElapsedMs = 50_000L
            )
        )
    }

    @Test
    fun staleScanIsRetiredButFreshScanIsNotDuplicated() {
        assertEquals(
            RideHudCaptureAction.NONE,
            rideHudCaptureAction(
                connectionDesired = true,
                connected = false,
                scanning = true,
                scanStartedAtElapsedMs = 20_000L,
                recordingActive = true,
                recordingDesired = true,
                permissionsGranted = true,
                nowElapsedMs = 49_999L
            )
        )
        assertEquals(
            RideHudCaptureAction.RESTART_SCAN,
            rideHudCaptureAction(
                connectionDesired = true,
                connected = false,
                scanning = true,
                scanStartedAtElapsedMs = 20_000L,
                recordingActive = true,
                recordingDesired = true,
                permissionsGranted = true,
                nowElapsedMs = 50_001L
            )
        )
    }

    @Test
    fun manualDisconnectStopsHudAndConnectedRecorderCanResume() {
        assertEquals(
            RideHudCaptureAction.STOP_HUD,
            rideHudCaptureAction(
                connectionDesired = false,
                connected = false,
                scanning = false,
                scanStartedAtElapsedMs = 0L,
                recordingActive = false,
                recordingDesired = false,
                permissionsGranted = true,
                nowElapsedMs = 1L
            )
        )
        assertEquals(
            RideHudCaptureAction.START_MEASUREMENT,
            rideHudCaptureAction(
                connectionDesired = true,
                connected = true,
                scanning = false,
                scanStartedAtElapsedMs = 0L,
                recordingActive = false,
                recordingDesired = true,
                permissionsGranted = true,
                nowElapsedMs = 1L
            )
        )
        assertEquals(
            RideHudCaptureAction.STOP_HUD,
            rideHudCaptureAction(
                connectionDesired = true,
                connected = true,
                scanning = false,
                scanStartedAtElapsedMs = 0L,
                recordingActive = true,
                recordingDesired = true,
                permissionsGranted = false,
                nowElapsedMs = 1L
            )
        )
    }
}

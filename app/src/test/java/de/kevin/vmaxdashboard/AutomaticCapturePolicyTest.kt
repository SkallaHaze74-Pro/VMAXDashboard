package de.kevin.vmaxdashboard

import org.junit.Assert.assertEquals
import org.junit.Test

class AutomaticCapturePolicyTest {
    @Test
    fun savingWhileStillConnectedImmediatelyStartsAFreshAutomaticRecording() {
        assertEquals(
            AutomaticCaptureAction.START_MEASUREMENT,
            automaticCaptureAction(
                connectionDesired = true,
                connected = true,
                scanning = false,
                recordingActive = false,
                recordingDesired = true,
                permissionsGranted = true
            )
        )
    }

    @Test
    fun stoppedOrRestartedAppKeepsSearchingWhenConnectionIsStillDesired() {
        assertEquals(
            AutomaticCaptureAction.START_SCAN,
            automaticCaptureAction(
                connectionDesired = true,
                connected = false,
                scanning = false,
                recordingActive = false,
                recordingDesired = true,
                permissionsGranted = true
            )
        )
    }

    @Test
    fun explicitManualDisconnectAlwaysWins() {
        assertEquals(
            AutomaticCaptureAction.NONE,
            automaticCaptureAction(
                connectionDesired = false,
                connected = false,
                scanning = false,
                recordingActive = false,
                recordingDesired = false,
                permissionsGranted = true
            )
        )
    }

    @Test
    fun activeScanActiveRecordingAndMissingPermissionDoNotDuplicateWork() {
        assertEquals(
            AutomaticCaptureAction.NONE,
            automaticCaptureAction(true, false, true, false, true, true)
        )
        assertEquals(
            AutomaticCaptureAction.NONE,
            automaticCaptureAction(true, true, false, true, true, true)
        )
        assertEquals(
            AutomaticCaptureAction.NONE,
            automaticCaptureAction(true, false, false, false, true, false)
        )
    }

    @Test
    fun explicitRecordingPauseKeepsTheConnectionButDoesNotRestartCapture() {
        assertEquals(
            AutomaticCaptureAction.NONE,
            automaticCaptureAction(
                connectionDesired = true,
                connected = true,
                scanning = false,
                recordingActive = false,
                recordingDesired = false,
                permissionsGranted = true
            )
        )
        assertEquals(
            AutomaticCaptureAction.START_SCAN,
            automaticCaptureAction(
                connectionDesired = true,
                connected = false,
                scanning = false,
                recordingActive = false,
                recordingDesired = false,
                permissionsGranted = true
            )
        )
    }
}

package de.kevin.vmaxdashboard

import org.junit.Assert.assertEquals
import org.junit.Test

class ChargeReconnectPolicyTest {
    @Test
    fun offlineChargingRefreshesPowerArmEvenWhileBleScanIsAlreadyRunning() {
        assertEquals(
            ChargeReconnectAction(refreshPowerArm = true, startBleScan = false),
            chargeReconnectAction(
                chargeMode = true,
                connected = false,
                scanning = true,
                scanStartedAtElapsedMs = 10_000L,
                nowElapsedMs = 20_000L
            )
        )
    }

    @Test
    fun staleOrExplicitPowerScanRetiresTheOldGenerationAndStartsOneFreshScan() {
        assertEquals(
            ChargeReconnectAction(
                refreshPowerArm = true,
                restartBleScan = true,
                startBleScan = true
            ),
            chargeReconnectAction(
                chargeMode = true,
                connected = false,
                scanning = true,
                scanStartedAtElapsedMs = 10_000L,
                nowElapsedMs = 40_001L
            )
        )
        assertEquals(
            ChargeReconnectAction(
                refreshPowerArm = true,
                restartBleScan = true,
                startBleScan = true
            ),
            chargeReconnectAction(
                chargeMode = true,
                connected = false,
                scanning = true,
                scanStartedAtElapsedMs = 39_000L,
                nowElapsedMs = 40_000L,
                explicitPowerAttempt = true
            )
        )
    }

    @Test
    fun offlineIdleChargingArmsPowerAndStartsExactlyOneBleScan() {
        assertEquals(
            ChargeReconnectAction(refreshPowerArm = true, startBleScan = true),
            chargeReconnectAction(chargeMode = true, connected = false, scanning = false)
        )
    }

    @Test
    fun connectedOrInactiveChargingDoesNothing() {
        assertEquals(
            ChargeReconnectAction(),
            chargeReconnectAction(chargeMode = true, connected = true, scanning = false)
        )
        assertEquals(
            ChargeReconnectAction(),
            chargeReconnectAction(chargeMode = false, connected = false, scanning = false)
        )
    }
}

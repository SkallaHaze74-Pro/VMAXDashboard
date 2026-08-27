package de.kevin.vmaxdashboard

internal data class ChargeReconnectAction(
    val refreshPowerArm: Boolean = false,
    val startBleScan: Boolean = false,
    val restartBleScan: Boolean = false
)

internal fun chargeReconnectAction(
    chargeMode: Boolean,
    connected: Boolean,
    scanning: Boolean,
    scanStartedAtElapsedMs: Long = 0L,
    nowElapsedMs: Long = 0L,
    explicitPowerAttempt: Boolean = false
): ChargeReconnectAction {
    if (!chargeMode || connected) return ChargeReconnectAction()
    val scanAgeMs = nowElapsedMs - scanStartedAtElapsedMs
    val staleScan = scanning && (
        scanStartedAtElapsedMs <= 0L || nowElapsedMs <= 0L ||
            scanAgeMs < 0L || scanAgeMs > MAX_CHARGE_SCAN_LEASE_MS
        )
    val restartScan = scanning && (explicitPowerAttempt || staleScan)
    return ChargeReconnectAction(
        refreshPowerArm = true,
        startBleScan = !scanning || restartScan,
        restartBleScan = restartScan
    )
}

internal const val MAX_CHARGE_SCAN_LEASE_MS = 30_000L

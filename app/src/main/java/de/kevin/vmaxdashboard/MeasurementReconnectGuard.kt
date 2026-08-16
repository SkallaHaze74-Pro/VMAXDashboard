package de.kevin.vmaxdashboard

/**
 * Keeps an active measurement continuous across an unexpected BLE disconnect.
 *
 * A measurement is longer-lived than one GATT connection. The manager now
 * retains those rows itself; this guard still keeps a defensive snapshot and
 * restores it only if a future/reset path did not retain the rows.
 *
 * It does not read or write scooter characteristics and it does not change any
 * decoder or motor setting.
 */
class MeasurementReconnectGuard(private val manager: BleScooterManager) {
    private var pendingRows: List<String>? = null
    private var pendingLastRow: String? = null

    fun captureIfRecording(): Int {
        if (!manager.state.value.recordingActive) return 0
        val snapshot = manager.snapshotMeasurementRowsForReconnect() ?: return 0
        pendingRows = snapshot
        pendingLastRow = snapshot.lastOrNull()
        return snapshot.size
    }

    fun restoreIfPending(): Int {
        val backup = pendingRows ?: return 0
        if (!manager.state.value.recordingActive) {
            clear()
            return 0
        }

        // New managers retain measurement rows themselves; this method remains a
        // compatibility guard for any reset path and never duplicates retained data.
        val restored = manager.restoreMeasurementRowsForReconnect(backup, pendingLastRow)
        clear()
        return restored
    }

    fun clear() {
        pendingRows = null
        pendingLastRow = null
    }

}

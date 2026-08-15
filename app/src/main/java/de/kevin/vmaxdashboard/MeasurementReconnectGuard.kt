package de.kevin.vmaxdashboard

/**
 * Keeps an active measurement continuous across an unexpected BLE disconnect.
 *
 * BleScooterManager intentionally resets per-connection bookkeeping when a new
 * GATT connection comes up. A measurement, however, is a longer-lived session.
 * This guard snapshots only the measurement RAW rows while disconnected and
 * prepends them again after the next connection is established.
 *
 * It does not read or write scooter characteristics and it does not change any
 * decoder or motor setting.
 */
class MeasurementReconnectGuard(private val manager: BleScooterManager) {
    private var pendingRows: List<String>? = null
    private var pendingLastRow: String? = null

    fun captureIfRecording(): Int {
        if (!manager.state.value.recordingActive) return 0
        val rows = sessionRows() ?: return 0
        val snapshot = rows.toList()
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

        val rows = sessionRows() ?: return 0
        val last = pendingLastRow

        // If the manager did not clear the list, do not duplicate anything.
        if (last != null && rows.contains(last)) {
            clear()
            return 0
        }

        if (backup.isNotEmpty()) rows.addAll(0, backup)
        val restored = backup.size
        clear()
        return restored
    }

    fun clear() {
        pendingRows = null
        pendingLastRow = null
    }

    @Suppress("UNCHECKED_CAST")
    private fun sessionRows(): MutableList<String>? = runCatching {
        val field = BleScooterManager::class.java.getDeclaredField("sessionRows")
        field.isAccessible = true
        field.get(manager) as? MutableList<String>
    }.getOrNull()
}

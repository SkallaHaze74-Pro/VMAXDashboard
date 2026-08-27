package de.kevin.vmaxdashboard

import java.io.File

/**
 * Process-wide ownership for active measurement journals.
 *
 * Multiple Android activities can briefly own separate manager instances. A
 * journal is therefore identified by its root and start time, while the owner
 * token prevents one manager from releasing another manager's live segment.
 * Production ownership transitions are ordered on the shared journal I/O lane.
 */
internal object ProcessActiveMeasurementJournals {
    private data class SegmentKey(val normalizedRoot: String, val startedAt: Long)

    private val owners = mutableMapOf<SegmentKey, Long>()

    @Synchronized
    fun register(root: File, startedAt: Long, ownerToken: Long): Boolean {
        require(ownerToken > 0L) { "Journal-Eigentümer muss positiv sein" }
        val key = SegmentKey(normalizeRoot(root), startedAt)
        val current = owners[key]
        if (current != null && current != ownerToken) return false
        owners[key] = ownerToken
        return true
    }

    @Synchronized
    fun release(root: File, startedAt: Long, ownerToken: Long): Boolean {
        val key = SegmentKey(normalizeRoot(root), startedAt)
        if (owners[key] != ownerToken) return false
        owners.remove(key)
        return true
    }

    @Synchronized
    fun ownedStarts(root: File): Set<Long> {
        val normalizedRoot = normalizeRoot(root)
        return owners.keys
            .asSequence()
            .filter { it.normalizedRoot == normalizedRoot }
            .mapTo(linkedSetOf()) { it.startedAt }
    }

    private fun normalizeRoot(root: File): String =
        root.toPath().toAbsolutePath().normalize().toString()
}

/** Must run on the same ordered journal lane as register, append, and clear. */
internal fun recoverUnownedActiveMeasurementJournals(
    store: ActiveMeasurementJournalStore,
    root: File
): ActiveMeasurementJournalRecovery {
    val recovery = store.recoverPendingExportsWithDiagnostics()
    return recovery.copy(
        pendingExports = excludeProcessOwnedActiveExports(
            recovered = recovery.pendingExports,
            processOwnedStartedAt = ProcessActiveMeasurementJournals.ownedStarts(root)
        )
    )
}

internal data class ActiveMeasurementJournalStartupRecovery(
    val recovery: ActiveMeasurementJournalRecovery,
    val segmentsNotCleared: Set<Long>,
    val cleanupErrors: List<String>
)

/**
 * Startup handoff transaction. Callers run this on the process-wide ordered
 * journal lane so no claim/start/append can interleave with filtering and
 * deletion.
 */
internal fun recoverStageAndClearUnownedActiveMeasurementJournals(
    store: ActiveMeasurementJournalStore,
    root: File,
    stage: (PendingMeasurementExport) -> Unit
): ActiveMeasurementJournalStartupRecovery {
    val recovery = recoverUnownedActiveMeasurementJournals(store, root)
    val segmentsNotCleared = mutableSetOf<Long>()
    val cleanupErrors = mutableListOf<String>()
    recovery.pendingExports.forEach { pending ->
        val cleanupError = runCatching {
            stage(pending)
            store.clearSegment(pending.snapshot.startedAt)
        }.exceptionOrNull()
        if (cleanupError != null) {
            segmentsNotCleared += pending.snapshot.startedAt
            cleanupErrors += cleanupError.message ?: cleanupError.javaClass.simpleName
        }
    }
    return ActiveMeasurementJournalStartupRecovery(
        recovery = recovery,
        segmentsNotCleared = segmentsNotCleared,
        cleanupErrors = cleanupErrors
    )
}

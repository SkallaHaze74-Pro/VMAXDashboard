package de.kevin.vmaxdashboard

internal class ScooterRuntimeOwnerToken internal constructor()

/** Keeps the shared scooter runtime alive until its final unique owner leaves. */
internal class ScooterRuntimeOwnership(
    private val onNoOwners: () -> Unit
) {
    private val owners = mutableSetOf<ScooterRuntimeOwnerToken>()

    val ownerCount: Int
        get() = synchronized(owners) { owners.size }

    fun acquire(): ScooterRuntimeOwnerToken = synchronized(owners) {
        ScooterRuntimeOwnerToken().also(owners::add)
    }

    fun release(lease: ScooterRuntimeOwnerToken): Boolean {
        val notifyNoOwners = synchronized(owners) {
            if (!owners.remove(lease)) return false
            owners.isEmpty()
        }
        if (notifyNoOwners) onNoOwners()
        return true
    }
}

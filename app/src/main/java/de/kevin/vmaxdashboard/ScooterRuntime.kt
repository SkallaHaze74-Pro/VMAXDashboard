package de.kevin.vmaxdashboard

import android.content.Context
import java.util.concurrent.atomic.AtomicBoolean

/** One process-wide physical scooter session shared by dashboard and HUD. */
internal class ScooterRuntime(context: Context) {
    val bleManager = BleScooterManager(context.applicationContext).also {
        it.retryPendingMeasurementExports()
    }
    val gattReadScanner = GattReadScanner(bleManager)

    private val ownership = ScooterRuntimeOwnership(onNoOwners = bleManager::disconnect)

    fun acquire(): ScooterRuntimeLease {
        val ownerToken = ownership.acquire()
        return ScooterRuntimeLease { ownership.release(ownerToken) }
    }
}

/** Idempotent lease so lifecycle callbacks can never release another owner. */
internal class ScooterRuntimeLease(
    private val releaseOwner: () -> Unit
) : AutoCloseable {
    private val closed = AtomicBoolean(false)

    override fun close() {
        if (closed.compareAndSet(false, true)) releaseOwner()
    }
}

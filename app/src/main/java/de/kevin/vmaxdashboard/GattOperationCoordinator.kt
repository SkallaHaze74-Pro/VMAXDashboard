package de.kevin.vmaxdashboard

import java.util.ArrayDeque

internal enum class GattOperationKind {
    NOTIFICATION_SETUP,
    MOTOR_TUNING,
    START_MODE_WRITE,
    DIAGNOSTIC_READ_SCAN
}

internal data class GattOperationToken(
    val connectionEpoch: Long,
    val operationId: Long,
    val kind: GattOperationKind
)

/**
 * Pure ownership ledger for the single-operation Android GATT contract.
 * BleScooterManager serializes access to this coordinator with its GATT lock.
 */
internal class GattOperationCoordinator {
    private var nextConnectionEpoch = 0L
    private var nextOperationId = 0L
    private var activeConnectionEpoch: Long? = null
    private var currentOperation: GattOperationToken? = null

    fun openConnection(): Long {
        val epoch = ++nextConnectionEpoch
        activeConnectionEpoch = epoch
        currentOperation = null
        return epoch
    }

    fun closeConnection(connectionEpoch: Long): Boolean {
        if (activeConnectionEpoch != connectionEpoch) return false
        activeConnectionEpoch = null
        currentOperation = null
        return true
    }

    fun isActiveConnection(connectionEpoch: Long): Boolean =
        activeConnectionEpoch == connectionEpoch

    fun tryBegin(connectionEpoch: Long, kind: GattOperationKind): GattOperationToken? {
        if (!isActiveConnection(connectionEpoch) || currentOperation != null) return null
        return GattOperationToken(connectionEpoch, ++nextOperationId, kind).also {
            currentOperation = it
        }
    }

    fun isCurrent(token: GattOperationToken?): Boolean =
        token != null && activeConnectionEpoch == token.connectionEpoch && currentOperation == token

    fun finish(token: GattOperationToken?): Boolean {
        if (!isCurrent(token)) return false
        currentOperation = null
        return true
    }

    fun currentKind(connectionEpoch: Long): GattOperationKind? =
        currentOperation?.kind?.takeIf { activeConnectionEpoch == connectionEpoch }
}

/** A pending write still owns GATT while its notify-only verification is outstanding. */
internal fun keepMotorTuningOperationForNotifyOnlyVerification(hasPendingWrite: Boolean): Boolean =
    hasPendingWrite

/** Pure callback/timeout state machine used by the Android diagnostic READ scanner. */
internal class SequentialGattReadCoordinator<T : Any> {
    private val queue = ArrayDeque<T>()

    var inFlight: T? = null
        private set

    val running: Boolean
        get() = inFlight != null || queue.isNotEmpty()

    val remaining: Int
        get() = queue.size

    fun reset(items: List<T>) {
        queue.clear()
        queue.addAll(items)
        inFlight = null
    }

    fun beginNext(): T? {
        if (inFlight != null) return null
        return queue.pollFirst()?.also { inFlight = it }
    }

    fun complete(item: T): Boolean {
        if (inFlight != item) return false
        inFlight = null
        return true
    }

    fun timeout(item: T): Boolean = complete(item)

    fun cancel() {
        queue.clear()
        inFlight = null
    }
}

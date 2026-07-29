package de.kevin.vmaxdashboard

import android.annotation.SuppressLint
import android.bluetooth.BluetoothGatt

/**
 * Test-only BLE tuning for the parallel "Aktuell" app.
 *
 * This does not write any scooter setting or characteristic. It only asks
 * Android for a low-latency connection and a larger MTU. The scooter remains
 * free to choose its own notification interval.
 */
@SuppressLint("MissingPermission")
fun BleScooterManager.enableRealtimeBleMode(): RealtimeBleResult {
    return runCatching {
        val field = BleScooterManager::class.java.getDeclaredField("gatt")
        field.isAccessible = true
        val gatt = field.get(this) as? BluetoothGatt
            ?: return RealtimeBleResult(false, false, "BLE-GATT noch nicht bereit")

        val priorityAccepted = gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
        val mtuAccepted = gatt.requestMtu(247)
        val message = when {
            priorityAccepted && mtuAccepted -> "Hohe BLE-Priorität + MTU 247 angefordert"
            priorityAccepted -> "Hohe BLE-Priorität aktiv; MTU-Anfrage abgelehnt"
            mtuAccepted -> "MTU 247 angefordert; Prioritätsanfrage abgelehnt"
            else -> "Android hat beide Echtzeitanfragen abgelehnt"
        }
        RealtimeBleResult(priorityAccepted, mtuAccepted, message)
    }.getOrElse { error ->
        RealtimeBleResult(false, false, "Echtzeitmodus nicht verfügbar: ${error.message ?: error.javaClass.simpleName}")
    }
}

data class RealtimeBleResult(
    val highPriorityRequested: Boolean,
    val mtuRequested: Boolean,
    val message: String
) {
    val active: Boolean get() = highPriorityRequested || mtuRequested
}

package de.kevin.vmaxdashboard

import android.annotation.SuppressLint
import android.bluetooth.BluetoothGatt

/**
 * Read-only BLE tuning for the parallel "Aktuell" test app.
 *
 * No scooter characteristic or setting is written. Android is only asked to
 * prefer a low-latency BLE connection. The scooter still controls how often
 * it sends notifications.
 */
@SuppressLint("MissingPermission")
fun BleScooterManager.enableRealtimeBleMode(): RealtimeBleResult {
    return runCatching {
        val field = BleScooterManager::class.java.getDeclaredField("gatt")
        field.isAccessible = true
        val gatt = field.get(this) as? BluetoothGatt
            ?: return RealtimeBleResult(false, "BLE-Verbindung noch nicht bereit")

        val accepted = gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
        RealtimeBleResult(
            highPriorityRequested = accepted,
            message = if (accepted) {
                "Hohe BLE-Priorität angefordert – keine Scooter-Einstellung verändert"
            } else {
                "Android hat die hohe BLE-Priorität abgelehnt"
            }
        )
    }.getOrElse { error ->
        RealtimeBleResult(false, "Echtzeitmodus nicht verfügbar: ${error.message ?: error.javaClass.simpleName}")
    }
}

data class RealtimeBleResult(
    val highPriorityRequested: Boolean,
    val message: String
)

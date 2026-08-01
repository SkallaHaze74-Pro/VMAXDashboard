package de.kevin.vmaxdashboard

import android.annotation.SuppressLint
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.os.Handler
import android.os.Looper

/**
 * Rein lesender Geräte-Scan. Es werden ausschließlich Characteristics mit
 * PROPERTY_READ abgefragt. Schreib- und Tuningkanäle werden ignoriert.
 * Antworten laufen über den vorhandenen BluetoothGattCallback des Managers
 * und landen dadurch automatisch in Rohdaten, Kanalliste und Messaufzeichnung.
 */
@SuppressLint("MissingPermission")
fun BleScooterManager.inspectReadableControllerData(): ControllerInspectionResult {
    return runCatching {
        val field = BleScooterManager::class.java.getDeclaredField("gatt")
        field.isAccessible = true
        val gatt = field.get(this) as? BluetoothGatt
            ?: return ControllerInspectionResult(0, "Controller-Scan wartet auf BLE-Verbindung")

        val readable = gatt.services
            .flatMap { it.characteristics }
            .filter { characteristic ->
                characteristic.properties and BluetoothGattCharacteristic.PROPERTY_READ != 0 &&
                    shortUuid(characteristic) != "160D"
            }
            .distinctBy { it.uuid }
            .sortedBy { shortUuid(it) }

        val handler = Handler(Looper.getMainLooper())
        readable.forEachIndexed { index, characteristic ->
            handler.postDelayed({
                runCatching { gatt.readCharacteristic(characteristic) }
            }, 650L * index)
        }

        ControllerInspectionResult(
            readable.size,
            if (readable.isEmpty()) "Keine zusätzlich lesbaren Controllerkanäle gefunden"
            else "${readable.size} sichere READ-Kanäle werden automatisch abgefragt"
        )
    }.getOrElse { error ->
        ControllerInspectionResult(0, "Controller-Scan nicht verfügbar: ${error.message ?: error.javaClass.simpleName}")
    }
}

private fun shortUuid(characteristic: BluetoothGattCharacteristic): String =
    characteristic.uuid.toString().substring(4, 8).uppercase()

data class ControllerInspectionResult(
    val requestedChannels: Int,
    val message: String
)

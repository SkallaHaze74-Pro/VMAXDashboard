package de.kevin.vmaxdashboard

import android.app.Application
import androidx.lifecycle.AndroidViewModel

/** Retains the single physical GATT owner and Deep-READ scanner across rotation. */
class MainDashboardViewModel(application: Application) : AndroidViewModel(application) {
    val bleManager = BleScooterManager(application.applicationContext).also {
        it.retryPendingMeasurementExports()
    }
    val gattReadScanner = GattReadScanner(bleManager)

    override fun onCleared() {
        bleManager.disconnect()
        super.onCleared()
    }
}

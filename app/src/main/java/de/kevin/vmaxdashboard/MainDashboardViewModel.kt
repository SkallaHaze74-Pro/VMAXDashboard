package de.kevin.vmaxdashboard

import android.app.Application
import androidx.lifecycle.AndroidViewModel

/** Retains the single physical GATT owner and Deep-READ scanner across rotation. */
class MainDashboardViewModel(application: Application) : AndroidViewModel(application) {
    private val scooterRuntime = (application as VMAXSyncApplication).scooterRuntime
    private val runtimeLease = scooterRuntime.acquire()
    val bleManager = scooterRuntime.bleManager
    val gattReadScanner = scooterRuntime.gattReadScanner

    override fun onCleared() {
        runtimeLease.close()
        super.onCleared()
    }
}

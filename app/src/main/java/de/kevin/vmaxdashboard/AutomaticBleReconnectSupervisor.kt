package de.kevin.vmaxdashboard

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Automatically keeps an active measurement alive across an unexpected BLE drop.
 *
 * Manual disconnect is intentionally not reconnected because BleScooterManager
 * ends/exports the active measurement before disconnecting. An unexpected link
 * loss leaves recordingActive=true, which is the signal used here.
 */
class AutomaticBleReconnectSupervisor private constructor(
    private val application: Application
) : Application.ActivityLifecycleCallbacks {

    companion object {
        fun install(application: Application) {
            val supervisor = AutomaticBleReconnectSupervisor(application)
            application.registerActivityLifecycleCallbacks(supervisor)
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var observeJob: Job? = null
    private var observedManager: BleScooterManager? = null
    private var reconnectGuard: MeasurementReconnectGuard? = null
    private var previousConnected = false
    private var unexpectedDisconnectPending = false

    override fun onActivityResumed(activity: Activity) {
        if (activity !is MainActivity) return
        val manager = resolveManager(activity) ?: return
        if (observedManager === manager && observeJob?.isActive == true) return
        attach(manager)
    }

    private fun attach(manager: BleScooterManager) {
        observeJob?.cancel()
        observedManager = manager
        reconnectGuard = MeasurementReconnectGuard(manager)
        previousConnected = manager.state.value.connected
        unexpectedDisconnectPending = false

        observeJob = scope.launch {
            manager.state
                .map { Triple(it.connected, it.recordingActive, it.scanning) }
                .distinctUntilChanged()
                .collect { (connected, recordingActive, scanning) ->
                    mainHandler.post {
                        handleState(manager, connected, recordingActive, scanning)
                    }
                }
        }
    }

    private fun handleState(
        manager: BleScooterManager,
        connected: Boolean,
        recordingActive: Boolean,
        scanning: Boolean
    ) {
        if (observedManager !== manager) return

        val wasConnected = previousConnected
        previousConnected = connected

        if (wasConnected && !connected && recordingActive) {
            val savedRows = reconnectGuard?.captureIfRecording() ?: 0
            unexpectedDisconnectPending = true
            manager.addMeasurementMarker("BLE getrennt • RAW $savedRows gesichert")
        }

        if (!wasConnected && connected && recordingActive && unexpectedDisconnectPending) {
            val restoredRows = reconnectGuard?.restoreIfPending() ?: 0
            unexpectedDisconnectPending = false
            manager.addMeasurementMarker("BLE wieder verbunden • RAW $restoredRows wiederhergestellt")
        }

        if (!recordingActive && unexpectedDisconnectPending) {
            reconnectGuard?.clear()
            unexpectedDisconnectPending = false
        }

        // Unexpected disconnect: recording stays active, so reconnect automatically.
        // Manual disconnect stops the recording first and therefore never reaches here.
        if (!connected && recordingActive && !scanning && manager.hasRequiredPermissions()) {
            manager.startScan()
        }
    }

    private fun resolveManager(activity: MainActivity): BleScooterManager? = runCatching {
        val field = MainActivity::class.java.getDeclaredField("bleManager")
        field.isAccessible = true
        field.get(activity) as? BleScooterManager
    }.getOrNull()

    override fun onActivityDestroyed(activity: Activity) {
        if (activity !is MainActivity) return
        val manager = resolveManager(activity)
        if (manager != null && observedManager === manager) {
            observeJob?.cancel()
            observeJob = null
            observedManager = null
            reconnectGuard = null
            unexpectedDisconnectPending = false
        }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

    @Suppress("unused")
    fun shutdown() {
        observeJob?.cancel()
        scope.cancel()
        application.unregisterActivityLifecycleCallbacks(this)
    }
}

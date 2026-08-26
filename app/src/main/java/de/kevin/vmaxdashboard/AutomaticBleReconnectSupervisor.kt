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
 * Automatically keeps the desired BLE connection and recorder alive.
 *
 * Manual disconnect is intentionally not reconnected because BleScooterManager
 * clears connectionDesired before ending/exporting the active measurement.
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
    private var observedActivity: MainActivity? = null
    private var reconnectGuard: MeasurementReconnectGuard? = null
    private var transitionTracker: ReconnectTransitionTracker? = null

    private data class Observation(
        val connected: Boolean,
        val telemetryReady: Boolean,
        val recordingActive: Boolean,
        val recordingDesired: Boolean,
        val scanning: Boolean,
        val connectionDesired: Boolean
    )

    override fun onActivityResumed(activity: Activity) {
        if (activity !is MainActivity) return
        observedActivity = activity
        val manager = activity.bleManagerForReconnectSupervisor
        if (observedManager === manager && observeJob?.isActive == true) return
        attach(manager)
    }

    private fun attach(manager: BleScooterManager) {
        observeJob?.cancel()
        observedManager = manager
        reconnectGuard = MeasurementReconnectGuard(manager)
        transitionTracker = ReconnectTransitionTracker(manager.state.value.connected)

        observeJob = scope.launch {
            manager.state
                .map {
                    Observation(
                        it.connected,
                        it.telemetryReady,
                        it.recordingActive,
                        it.recordingDesired,
                        it.scanning,
                        it.connectionDesired
                    )
                }
                .distinctUntilChanged()
                .collect { observation ->
                    mainHandler.post {
                        handleState(manager, observation)
                    }
                }
        }
    }

    private fun handleState(
        manager: BleScooterManager,
        observation: Observation
    ) {
        if (observedManager !== manager) return
        transitionTracker?.observe(
            observation.connected,
            observation.telemetryReady,
            observation.recordingActive
        ).orEmpty().forEach { transition ->
            when (transition) {
                ReconnectTransition.UNEXPECTED_DISCONNECT -> {
                    val savedRows = reconnectGuard?.captureIfRecording() ?: 0
                    manager.addMeasurementMarker("BLE getrennt • RAW $savedRows gesichert")
                }
                ReconnectTransition.LINK_RESTORED -> {
                    val restoredRows = reconnectGuard?.restoreIfPending() ?: 0
                    manager.addMeasurementMarker(
                        if (restoredRows > 0) {
                            "BLE-Link wieder verbunden • RAW $restoredRows wiederhergestellt"
                        } else {
                            "BLE-Link wieder verbunden • Messfahrtdaten erhalten"
                        }
                    )
                }
                ReconnectTransition.TELEMETRY_RESTORED ->
                    manager.addMeasurementMarker("Telemetrie wieder aktiv")
                ReconnectTransition.CLEAR_PENDING -> reconnectGuard?.clear()
            }
        }

        // Re-read the latest state: export/restart can advance while an older
        // StateFlow observation is waiting on the main thread.
        val current = manager.state.value
        when (
            automaticCaptureAction(
                connectionDesired = current.connectionDesired,
                connected = current.connected,
                scanning = current.scanning,
                recordingActive = current.recordingActive,
                recordingDesired = current.recordingDesired,
                permissionsGranted = manager.hasRequiredPermissions()
            )
        ) {
            AutomaticCaptureAction.START_SCAN -> manager.startScan()
            AutomaticCaptureAction.START_MEASUREMENT -> manager.startMeasurement()
            AutomaticCaptureAction.NONE -> Unit
        }
    }

    override fun onActivityDestroyed(activity: Activity) {
        if (activity !is MainActivity || activity.isChangingConfigurations || observedActivity !== activity) return
        observeJob?.cancel()
        observeJob = null
        observedManager = null
        observedActivity = null
        reconnectGuard = null
        transitionTracker = null
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

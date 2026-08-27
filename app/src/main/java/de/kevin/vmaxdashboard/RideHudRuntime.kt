package de.kevin.vmaxdashboard

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

internal data class RideHudRuntimeState(
    val active: Boolean = false,
    val serviceRunning: Boolean = false,
    val message: String = "Mini-HUD ist aus"
)

internal fun rideHudHiddenRuntimeState(message: String): RideHudRuntimeState =
    RideHudRuntimeState(active = false, serviceRunning = true, message = message)

/** Process-visible HUD status; it never owns BLE or Android windows. */
internal object RideHudRuntime {
    private val _state = MutableStateFlow(RideHudRuntimeState())
    val state: StateFlow<RideHudRuntimeState> = _state

    fun report(
        active: Boolean,
        message: String,
        serviceRunning: Boolean = _state.value.serviceRunning
    ) {
        _state.value = RideHudRuntimeState(
            active = active,
            serviceRunning = serviceRunning,
            message = message
        )
    }
}

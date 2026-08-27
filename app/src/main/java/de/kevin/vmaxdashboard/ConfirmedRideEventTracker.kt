package de.kevin.vmaxdashboard

internal const val CONFIRMED_RIDE_EVENT_PREFIX = "AUTO_BESTÄTIGT •"

/**
 * Emits events only for directly confirmed BT638 fields on live channel 1508.
 *
 * A new connection epoch and every unknown value invalidate the affected
 * baseline. This deliberately prefers missing an event over inventing a
 * transition across a reconnect or an unrecognized controller state.
 */
internal class ConfirmedRideEventTracker {
    private var baselineConnectionEpoch: Long? = null
    private var previousLightRaw: Int? = null
    private var previousRideModeRaw: Int? = null

    fun observe(
        connectionEpoch: Long,
        sourceChannel: String,
        lightRaw: Int?,
        rideModeRaw: Int?
    ): List<String> {
        if (sourceChannel != CONFIRMED_SOURCE_CHANNEL) return emptyList()

        val confirmedLight = lightRaw?.takeIf { it in CONFIRMED_LIGHT_VALUES }
        val confirmedRideMode = rideModeRaw?.takeIf { it in CONFIRMED_RIDE_MODE_VALUES }
        if (baselineConnectionEpoch != connectionEpoch) {
            baselineConnectionEpoch = connectionEpoch
            previousLightRaw = confirmedLight
            previousRideModeRaw = confirmedRideMode
            return emptyList()
        }

        val events = buildList {
            val previousLight = previousLightRaw
            if (previousLight != null && confirmedLight != null && previousLight != confirmedLight) {
                add(
                    "$CONFIRMED_RIDE_EVENT_PREFIX Licht ${lightLabel(previousLight)} → " +
                        "${lightLabel(confirmedLight)} • 1508/0"
                )
            }

            val previousRideMode = previousRideModeRaw
            if (
                previousRideMode != null && confirmedRideMode != null &&
                previousRideMode != confirmedRideMode
            ) {
                add(
                    "$CONFIRMED_RIDE_EVENT_PREFIX Fahrmodus ${rideModeLabel(previousRideMode)} → " +
                        "${rideModeLabel(confirmedRideMode)} • 1508/3"
                )
            }
        }
        previousLightRaw = confirmedLight
        previousRideModeRaw = confirmedRideMode
        return events
    }

    private fun lightLabel(raw: Int): String = if (raw == 1) "AN" else "AUS"

    private fun rideModeLabel(raw: Int): String = if (raw == 2) "SPORT" else "ECO"

    private companion object {
        const val CONFIRMED_SOURCE_CHANNEL = "1508"
        val CONFIRMED_LIGHT_VALUES = setOf(0, 1)
        val CONFIRMED_RIDE_MODE_VALUES = setOf(1, 2)
    }
}

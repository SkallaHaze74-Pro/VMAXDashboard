package de.kevin.vmaxdashboard

/** Channels that carry periodic controller state rather than static READ/diagnostic data. */
internal object ConnectionTelemetryPolicy {
    private val liveNotificationChannels = setOf("1505", "1506", "1508", "1509", "150A", "150C")

    fun isLiveNotificationChannel(channel: String): Boolean =
        channel.uppercase() in liveNotificationChannels
}

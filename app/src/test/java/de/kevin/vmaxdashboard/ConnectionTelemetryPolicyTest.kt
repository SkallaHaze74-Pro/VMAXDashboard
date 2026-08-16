package de.kevin.vmaxdashboard

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionTelemetryPolicyTest {
    @Test
    fun onlyKnownPeriodicLiveChannelsRestoreTelemetryReadiness() {
        listOf("1505", "1506", "1508", "1509", "150A", "150C").forEach { channel ->
            assertTrue(channel, ConnectionTelemetryPolicy.isLiveNotificationChannel(channel))
        }

        listOf("1501", "1502", "1504", "150D", "1518", "160C", "unknown").forEach { channel ->
            assertFalse(channel, ConnectionTelemetryPolicy.isLiveNotificationChannel(channel))
        }
    }
}

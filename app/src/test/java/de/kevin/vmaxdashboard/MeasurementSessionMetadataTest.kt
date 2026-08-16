package de.kevin.vmaxdashboard

import org.junit.Assert.assertEquals
import org.junit.Test

class MeasurementSessionMetadataTest {
    @Test
    fun channelsCoverAllConnectionEpochsEvenWhenOnlyLatestConnectionIsLive() {
        val rows = listOf(
            "10;1000;1505;Fahrleistung;12;1;0;00-00;NOTIFICATION;0",
            "20;1010;1509;Akku;11;1;0;00-00;NOTIFICATION;0",
            // Offline gap and reconnect: the latest in-memory state may now contain only 1508.
            "5020;6010;1508;Settings;17;1;0;00-00;NOTIFICATION;1",
            "5030;6020;1505;Fahrleistung;12;2;0;00-00;NOTIFICATION;1",
            "broken",
            "relative_ms;timestamp_ms;channel;meaning"
        )

        assertEquals(listOf("1505", "1508", "1509"), measurementChannelsFromRawRows(rows))
    }
}

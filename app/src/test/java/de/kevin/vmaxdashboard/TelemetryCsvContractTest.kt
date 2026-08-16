package de.kevin.vmaxdashboard

import org.junit.Assert.assertEquals
import org.junit.Test

class TelemetryCsvContractTest {
    @Test
    fun manualAndBundleRawExportShareTheTenColumnSchema() {
        val row = "10;1000;1505;Fahrdaten;17;1;0;00-00;NOTIFICATION;2"
        val csv = buildRawTelemetryCsv(listOf(row))
        val lines = csv.lines()

        assertEquals(
            listOf(
                "relative_ms", "timestamp_ms", "channel", "meaning", "length",
                "packet_no", "changed_bytes", "hex", "origin", "connection_epoch"
            ),
            lines[0].split(';')
        )
        assertEquals(lines[0].split(';').size, lines[1].split(';').size)
        assertEquals(10, lines[1].split(';').size)
    }
}

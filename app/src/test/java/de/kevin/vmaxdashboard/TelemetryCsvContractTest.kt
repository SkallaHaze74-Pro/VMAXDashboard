package de.kevin.vmaxdashboard

import org.junit.Assert.assertEquals
import org.junit.Test

class TelemetryCsvContractTest {
    @Test
    fun manualAndBundleRawExportShareTheFullGattOriginSchema() {
        val row =
            "10;1000;1505;Fahrdaten;17;1;0;00-00;NOTIFICATION;2;" +
                "da1a1500-d532-4285-be94-b07a3e11a098;da1a1505-d532-4285-be94-b07a3e11a098;18"
        val csv = buildRawTelemetryCsv(listOf(row))
        val lines = csv.lines()

        assertEquals(
            listOf(
                "relative_ms", "timestamp_ms", "channel", "meaning", "length",
                "packet_no", "changed_bytes", "hex", "origin", "connection_epoch",
                "service_uuid", "characteristic_uuid", "properties_raw"
            ),
            lines[0].split(';')
        )
        assertEquals(lines[0].split(';').size, lines[1].split(';').size)
        assertEquals(13, lines[1].split(';').size)
    }
}

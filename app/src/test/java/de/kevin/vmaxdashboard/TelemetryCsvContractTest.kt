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

    @Test
    fun liveTelemetryV3NamesRawStableAndKnown1508FieldsWithoutAmbiguity() {
        val row =
            "10;1000;21.8;27;32;RECOVERING_AFTER_LOAD;42.8;19.0;813.0;813.2;" +
                "1509_direct;41.0;24.0;0.9;701.2;218;813;1;3;0;1509"
        val csv = buildLiveTelemetryCsv(listOf(row))
        val lines = csv.lines()

        assertEquals(
            listOf(
                "relative_ms", "timestamp_ms", "speed_kmh_candidate",
                "battery_percent_raw", "battery_percent_stable", "battery_stability",
                "voltage_v", "current_a", "power_w", "electrical_power_w",
                "power_provenance", "motor_temp_c", "battery_temp_c", "trip_km",
                "odometer_km", "speed_raw_1505_u16be_b6_b7", "motor_load_raw_be",
                "light_state_1508_b0", "ride_mode_1508_b3", "start_mode_1508_b11",
                "source_channel"
            ),
            lines[0].split(';')
        )
        assertEquals(lines[0].split(';').size, lines[1].split(';').size)
        assertEquals(21, lines[1].split(';').size)
    }

    @Test
    fun startModeExportKeepsExact1508ByteWithoutWeakeningWriteConfirmation() {
        val packet = ByteArray(17).also { it[VmaxStartModeProtocol.LIVE_VALUE_OFFSET] = 2 }

        assertEquals(2, resolveStartModeRawForExport("1508", packet, previousRaw = 0))
        assertEquals(0, resolveStartModeRawForExport("1509", packet, previousRaw = 0))
    }
}

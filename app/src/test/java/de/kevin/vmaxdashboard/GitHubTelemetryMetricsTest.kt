package de.kevin.vmaxdashboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GitHubTelemetryMetricsTest {
    @Test
    fun schema3KeepsV2PowerRulesWithExplicitBatteryAnd1508Contract() {
        val metrics = telemetryCsvMetrics(
            sequenceOf(
                LIVE_TELEMETRY_CSV_HEADER_V3,
                "0;1000;;27;32;RECOVERING_AFTER_LOAD;42.8;19.0;834;810;1509_direct;" +
                    ";;;;218;834;1;3;0;1509"
            )
        )

        assertEquals(3, metrics.schemaVersion)
        assertEquals(834.0, metrics.maxDirectPowerW!!, 0.0001)
        assertEquals(810.0, metrics.maxElectricalPowerW!!, 0.0001)
    }

    @Test
    fun schema3RequiresEveryColumnFromTheLiveTelemetryV3Contract() {
        val requiredColumns = LIVE_TELEMETRY_CSV_HEADER_V3.split(';')
        val v2RequiredColumns =
            setOf("power_w", "electrical_power_w", "power_provenance", "source_channel")
        assertEquals(21, requiredColumns.size)

        requiredColumns.indices.forEach { omittedIndex ->
            val incompleteHeader = requiredColumns
                .filterIndexed { index, _ -> index != omittedIndex }
                .joinToString(";")

            val metrics = telemetryCsvMetrics(sequenceOf(incompleteHeader))
            val expectedFallbackSchema =
                if (requiredColumns[omittedIndex] in v2RequiredColumns) 1 else 2

            assertEquals(
                "missing ${requiredColumns[omittedIndex]} must use the conservative fallback schema",
                expectedFallbackSchema,
                metrics.schemaVersion
            )
        }
    }

    @Test
    fun schema2MaximaUseOnlyFreshCanonicalSourceRows() {
        val csv = sequenceOf(
            "relative_ms;timestamp_ms;speed_kmh_candidate;battery_percent;voltage_v;current_a;power_w;electrical_power_w;power_provenance;source_channel",
            "0;1000;21.8;70;49.2;5.0;99;246;1509_direct;1509",
            "10;1010;21.8;70;49.2;5.0;990;260;1509_direct_carried;1509",
            "20;1020;21.8;70;49.2;5.0;834;810;1509_direct;1509",
            "30;1030;999.0;70;49.2;5.0;9999;9999;voltage_x_current_fallback;1509",
            "40;1040;27.9;70;49.2;5.0;4000;4000;1509_direct;1505"
        )

        val metrics = telemetryCsvMetrics(csv)

        assertEquals(2, metrics.schemaVersion)
        assertEquals(5, metrics.liveRows)
        assertEquals(27.9, metrics.maxSpeedKmh!!, 0.0001)
        assertEquals(834.0, metrics.maxDirectPowerW!!, 0.0001)
        assertEquals(9999.0, metrics.maxElectricalPowerW!!, 0.0001)
    }

    @Test
    fun schema1UsesOnlyPowerConfirmedByLegacyRawField() {
        val csv = sequenceOf(
            "relative_ms;power_w;motor_load_raw_be;speed_kmh_candidate;source_channel",
            "0;420;420;18.1;1509",
            "10;990;420;18.1;1509",
            "20;1200;1200;18.1;150A",
            "30;420;420;27.4;1505"
        )

        val metrics = telemetryCsvMetrics(csv)

        assertEquals(1, metrics.schemaVersion)
        assertEquals(4, metrics.liveRows)
        assertEquals(27.4, metrics.maxSpeedKmh!!, 0.0001)
        assertEquals(420.0, metrics.maxDirectPowerW!!, 0.0001)
        assertNull(metrics.maxElectricalPowerW)
    }

    @Test
    fun schema2HeaderDetectionIsNormalizedAndOrderIndependent() {
        val metrics = telemetryCsvMetrics(
            sequenceOf(
                "\uFEFFsource_channel; POWER_PROVENANCE ;power_w; electrical_power_w ;speed_kmh_candidate",
                "1509;1509_direct;321;300;"
            )
        )

        assertEquals(2, metrics.schemaVersion)
        assertEquals(321.0, metrics.maxDirectPowerW!!, 0.0001)
    }

    @Test
    fun incompleteOrMissingV2HeaderFallsBackConservativelyToSchema1() {
        val provenanceOnly = telemetryCsvMetrics(
            sequenceOf(
                "source_channel;power_w;power_provenance",
                "1509;999;1509_direct"
            )
        )
        val electricalOnly = telemetryCsvMetrics(
            sequenceOf(
                "source_channel;power_w;electrical_power_w",
                "1509;999;900"
            )
        )
        val empty = telemetryCsvMetrics(emptySequence())

        assertEquals(1, provenanceOnly.schemaVersion)
        assertNull(provenanceOnly.maxDirectPowerW)
        assertEquals(1, electricalOnly.schemaVersion)
        assertNull(electricalOnly.maxDirectPowerW)
        assertEquals(1, empty.schemaVersion)
        assertEquals(0, empty.liveRows)
    }

    @Test
    fun speedMaximumRejectsNonFiniteAndOutOfRangeLegacyValues() {
        val metrics = telemetryCsvMetrics(
            sequenceOf(
                "relative_ms;timestamp_ms;speed_kmh_candidate;battery_percent;voltage_v;current_a;power_w;motor_temp_c;battery_temp_c;trip_km;odometer_km;drive_raw_1505_b7;motor_load_raw_be;battery_state_raw_1509_b6;accessory_raw_b0;accessory_raw_b3;source_channel",
                "0;1000;28.7;91;51.9;3.0;155.7;;;;701.2;287;155;;0;1;1505",
                "543429;1786641977452;1259.4;91;51.9;3.0;155.7;;;;701.2;12594;155;;0;1;1505",
                "543430;1786641977453;NaN;91;51.9;3.0;155.7;;;;701.2;0;155;;0;1;1505",
                "543431;1786641977454;Infinity;91;51.9;3.0;155.7;;;;701.2;0;155;;0;1;1505",
                "543432;1786641977455;-0.1;91;51.9;3.0;155.7;;;;701.2;0;155;;0;1;1505",
                "543433;1786641977456;100.0;91;51.9;3.0;155.7;;;;701.2;1000;155;;0;1;1505"
            )
        )

        assertEquals(1, metrics.schemaVersion)
        assertEquals(100.0, metrics.maxSpeedKmh!!, 0.0001)
    }

    @Test
    fun schema2DirectPowerRejectsNonFiniteAndOutOfRangeValues() {
        val metrics = telemetryCsvMetrics(
            sequenceOf(
                "source_channel;power_w;electrical_power_w;power_provenance",
                "1509;834;810;1509_direct",
                "1509;NaN;810;1509_direct",
                "1509;Infinity;810;1509_direct",
                "1509;-1;810;1509_direct",
                "1509;65534;810;1509_direct",
                "1509;30000;810;1509_direct"
            )
        )

        assertEquals(2, metrics.schemaVersion)
        assertEquals(30000.0, metrics.maxDirectPowerW!!, 0.0001)
    }

    @Test
    fun schema1DirectPowerRejectsFffeAndNonFiniteValues() {
        val metrics = telemetryCsvMetrics(
            sequenceOf(
                "source_channel;power_w;motor_load_raw_be",
                "1509;65534;65534",
                "1509;NaN;NaN",
                "1509;Infinity;Infinity",
                "1509;834;834"
            )
        )

        assertEquals(1, metrics.schemaVersion)
        assertEquals(834.0, metrics.maxDirectPowerW!!, 0.0001)
    }
}

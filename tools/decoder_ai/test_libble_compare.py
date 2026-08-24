#!/usr/bin/env python3
import tempfile
import unittest
from pathlib import Path

from libble_compare import (
    FIELD_LAYOUTS,
    PLAUSIBLE,
    aggregate,
    analyze_ride,
    live_reference_number,
    nearest_live,
)


RAW_V1_HEADER = "relative_ms;timestamp_ms;channel;meaning;length;packet_no;changed_bytes;hex"
RAW_V2_HEADER = RAW_V1_HEADER + ";origin;connection_epoch"
LIVE_V2_HEADER = (
    "relative_ms;timestamp_ms;speed_kmh_candidate;battery_percent;voltage_v;current_a;"
    "power_w;electrical_power_w;power_provenance;motor_temp_c;battery_temp_c;trip_km;"
    "odometer_km;drive_raw_1505_b7;motor_load_raw_be;battery_state_raw_1509_b6;"
    "accessory_raw_b0;accessory_raw_b3;source_channel"
)
LIVE_V3_HEADER = (
    "relative_ms;timestamp_ms;speed_kmh_candidate;battery_percent_raw;"
    "battery_percent_stable;battery_stability;voltage_v;current_a;power_w;"
    "electrical_power_w;power_provenance;motor_temp_c;battery_temp_c;trip_km;"
    "odometer_km;speed_raw_1505_u16be_b6_b7;motor_load_raw_be;"
    "light_state_1508_b0;ride_mode_1508_b3;start_mode_1508_b11;source_channel"
)


def write_rows(path: Path, header: str, rows):
    path.write_text(header + "\n" + "\n".join(";".join(row) for row in rows) + "\n", encoding="utf-8")


class LibbleComparisonTests(unittest.TestCase):
    def test_legacy_blank_payload_does_not_invent_a_quarantine_origin(self):
        with tempfile.TemporaryDirectory() as tmp:
            ride = Path(tmp) / "Messfahrt_legacy_redacted"
            ride.mkdir()
            write_rows(
                ride / "BLE_Rohdaten.csv",
                RAW_V1_HEADER,
                [["0", "1000", "1509", "Legacy redacted", "11", "1", "0", ""]],
            )

            result = analyze_ride(ride)

            observations = result["export_observations"]
            self.assertIsNone(observations["observed_exported_read_rows"])
            self.assertEqual(0, observations["observed_exported_quarantined_rows"])
            self.assertEqual(0, observations["observed_exported_hybrid_rows"])

    def test_privacy_redacted_origins_count_without_decoder_samples(self):
        with tempfile.TemporaryDirectory() as tmp:
            ride = Path(tmp) / "Messfahrt_privacy_redacted"
            ride.mkdir()
            write_rows(
                ride / "BLE_Rohdaten.csv",
                RAW_V2_HEADER,
                [
                    ["0", "1000", "1509", "Redacted READ", "11", "1", "0", "", "READ", "0"],
                    [
                        "10",
                        "1010",
                        "1509",
                        "Redacted hybrid",
                        "11",
                        "2",
                        "0",
                        "",
                        "NOTIFICATION_QUARANTINED",
                        "0",
                    ],
                    [
                        "20",
                        "1020",
                        "1509",
                        "Redacted diagnostic",
                        "11",
                        "3",
                        "0",
                        "",
                        "NOTIFICATION_DIAGNOSTIC",
                        "0",
                    ],
                ],
            )

            result = analyze_ride(ride)

            self.assertEqual(3, result["raw_export_rows"])
            self.assertEqual(0, result["accepted_export_rows"])
            self.assertEqual({}, result["fields"])
            self.assertEqual(1, result["export_observations"]["observed_exported_read_rows"])
            self.assertEqual(1, result["export_observations"]["observed_exported_hybrid_rows"])
            self.assertEqual(2, result["export_observations"]["observed_exported_quarantined_rows"])

    def test_real_export_shape_uses_summary_for_rejection_quality(self):
        with tempfile.TemporaryDirectory() as tmp:
            ride = Path(tmp) / "Messfahrt_test"
            ride.mkdir()
            write_rows(
                ride / "BLE_Rohdaten.csv",
                RAW_V2_HEADER,
                [
                    ["0", "1000", "1505", "Fahrleistung", "12", "1", "0", "03-E8-03-B6-00-7B-00-7B-01-2C-FF-FF", "NOTIFICATION", "0"],
                    ["10", "1010", "1509", "Akku-Livedaten", "11", "1", "0", "05-DC-00-FA-50-BD-74-05-78-00-48", "NOTIFICATION", "0"],
                    ["20", "1020", "1509", "Rejected hybrid", "11", "2", "0", "05-DC-00-FA-50-BD-74-05-78-00-48", "NOTIFICATION_REJECTED_HYBRID", "0"],
                    ["30", "1030", "1509", "Diagnostic observation", "11", "3", "0", "05-DC-00-FA-50-BD-74-05-78-00-48", "DIAGNOSTIC_OBSERVATION", "0"],
                ],
            )
            write_rows(
                ride / "Live_Telemetrie.csv",
                LIVE_V3_HEADER,
                [
                    ["0", "1000", "12.3", "80", "80", "STABLE", "48.5", "1.5", "72", "72.75", "1509_direct_carried", "", "25.0", "", "", "123", "72", "", "", "", "1505"],
                    ["10", "1010", "12.3", "80", "80", "STABLE", "48.5", "1.5", "72", "72.75", "1509_direct", "", "25.0", "", "", "123", "72", "", "", "", "1509"],
                ],
            )
            (ride / "Zusammenfassung.txt").write_text(
                "VMAX Dashboard Messfahrt\n"
                "BLE_Pakete: 2\n"
                "BLE_Empfangen: 5\n"
                "BLE_Akzeptiert: 2\n"
                "READ_Verworfen: 1\n"
                "Hybrid_Verworfen: 2\n",
                encoding="utf-8",
            )

            result = analyze_ride(ride)
            fields = result["fields"]
            self.assertEqual(100.0, fields["1505.speed_kmh"]["match_percent"])
            self.assertEqual(100.0, fields["1509.current_A"]["match_percent"])
            self.assertEqual(100.0, fields["1509.battery_temp_C"]["match_percent"])
            self.assertEqual(100.0, fields["1509.soc_percent"]["match_percent"])
            self.assertEqual(100.0, fields["1509.voltage_V"]["match_percent"])
            self.assertEqual(100.0, fields["1509.direct_power_W"]["match_percent"])
            self.assertEqual("observed", fields["1509.direct_power_W"]["sdk_status"])
            self.assertEqual("same_raw_app_export_layout_consistency", fields["1509.direct_power_W"]["evidence_type"])
            self.assertFalse(fields["1509.direct_power_W"]["independent_semantic_confirmation"])
            self.assertAlmostEqual(100.0, fields["1505.powerA_W"]["mean"])
            self.assertAlmostEqual(1.23, fields["1505.torque_Nm"]["mean"])
            self.assertAlmostEqual(300.0, fields["1505.rpm"]["mean"])
            self.assertNotIn("1505.remaining_range_km", fields)
            self.assertNotIn("1505.distance_raw", fields)
            self.assertIn(("remaining_range_km", 10, 2, "u16be", 1.0, None), FIELD_LAYOUTS["1505"])
            self.assertEqual((0, 1000), PLAUSIBLE["remaining_range_km"])
            self.assertEqual(4, result["raw_export_rows"])
            self.assertEqual(2, result["accepted_export_rows"])
            self.assertEqual(0, result["export_observations"]["observed_exported_read_rows"])
            self.assertEqual(0, result["export_observations"]["observed_exported_hybrid_rows"])
            self.assertEqual(2, result["export_observations"]["observed_exported_quarantined_rows"])
            self.assertEqual("Zusammenfassung.txt", result["quality_counters"]["source"])
            self.assertEqual(1, result["quality_counters"]["rejected_read_packets"])
            self.assertEqual(2, result["quality_counters"]["rejected_hybrid_packets"])

    def test_battery_reference_prefers_v3_raw_and_keeps_v2_compatibility(self):
        self.assertEqual(
            27.0,
            live_reference_number(
                {"battery_percent_raw": "27", "battery_percent_stable": "32", "battery_percent": "99"},
                "battery_percent_raw",
            ),
        )
        self.assertEqual(80.0, live_reference_number({"battery_percent": "80"}, "battery_percent_raw"))

    def test_ride_level_match_is_layout_consistency_not_live_confirmation(self):
        with tempfile.TemporaryDirectory() as tmp:
            ride = Path(tmp) / "Messfahrt_layout_consistency"
            ride.mkdir()
            write_rows(
                ride / "BLE_Rohdaten.csv",
                RAW_V2_HEADER,
                [
                    [str(index * 10), str(1000 + index * 10), "1509", "Akku-Livedaten", "11", str(index + 1), "0", "05-DC-00-FA-50-BD-74-05-78-00-48", "NOTIFICATION", "0"]
                    for index in range(10)
                ],
            )
            write_rows(
                ride / "Live_Telemetrie.csv",
                "timestamp_ms;power_w;source_channel",
                [[str(1000 + index * 10), "72", "1509"] for index in range(10)],
            )

            field = analyze_ride(ride)["fields"]["1509.direct_power_W"]
            self.assertEqual(10, field["comparisons_to_app_live"])
            self.assertEqual(100.0, field["match_percent"])
            self.assertEqual("app_export_consistent_with_sdk_layout", field["sdk_status"])
            self.assertEqual("same_raw_app_export_layout_consistency", field["evidence_type"])
            self.assertFalse(field["independent_semantic_confirmation"])
            self.assertNotEqual("confirmed_on_bt638", field["sdk_status"])

    def test_old_real_export_without_quality_counters_stays_unknown(self):
        with tempfile.TemporaryDirectory() as tmp:
            ride = Path(tmp) / "Messfahrt_old"
            ride.mkdir()
            write_rows(
                ride / "BLE_Rohdaten.csv",
                RAW_V1_HEADER,
                [["0", "1000", "1509", "Akku-Livedaten", "11", "1", "0", "05-DC-00-FA-50-BD-74-05-78-00-48"]],
            )
            write_rows(
                ride / "Live_Telemetrie.csv",
                "timestamp_ms;current_a;source_channel",
                [["1000", "1.5", "1509"]],
            )
            (ride / "Zusammenfassung.txt").write_text(
                "VMAX Dashboard Messfahrt\nBLE_Pakete: 1\n",
                encoding="utf-8",
            )

            result = analyze_ride(ride)
            self.assertFalse(result["export_observations"]["origin_column_available"])
            self.assertIsNone(result["export_observations"]["observed_exported_read_rows"])
            self.assertEqual("unknown", result["quality_counters"]["source"])
            self.assertIsNone(result["quality_counters"]["rejected_read_packets"])
            self.assertIsNone(result["quality_counters"]["rejected_hybrid_packets"])

    def test_direct_power_compares_to_power_export_not_duplicate_raw_column(self):
        with tempfile.TemporaryDirectory() as tmp:
            ride = Path(tmp) / "Messfahrt_power_mismatch"
            ride.mkdir()
            write_rows(
                ride / "BLE_Rohdaten.csv",
                RAW_V2_HEADER,
                [["0", "2000", "1509", "Akku-Livedaten", "11", "1", "0", "05-DC-00-FA-50-BD-74-05-78-00-48", "NOTIFICATION", "0"]],
            )
            write_rows(
                ride / "Live_Telemetrie.csv",
                "timestamp_ms;power_w;motor_load_raw_be;source_channel",
                [["2000", "999", "72", "1509"]],
            )
            result = analyze_ride(ride)
            self.assertEqual(0.0, result["fields"]["1509.direct_power_W"]["match_percent"])

    def test_nearest_live_requires_same_source_channel(self):
        stale_other_channel = [{"timestamp_ms": "1010", "source_channel": "150A", "current_a": "1.5"}]
        self.assertIsNone(nearest_live(stale_other_channel, 1010, "1509"))

    def test_aggregate_observation_without_comparison_is_not_called_layout_consistency(self):
        result = aggregate([
            {
                "fields": {
                    "1505.powerA_W": {
                        "samples": 10,
                        "comparisons_to_app_live": 0,
                        "match_percent": None,
                        "mae_to_app_live": None,
                    }
                }
            }
        ])

        self.assertEqual("sdk_layout_observation", result["1505.powerA_W"]["evidence_type"])
        self.assertEqual("OBSERVED_NEEDS_MORE_PROOF", result["1505.powerA_W"]["verdict"])


if __name__ == "__main__":
    unittest.main()

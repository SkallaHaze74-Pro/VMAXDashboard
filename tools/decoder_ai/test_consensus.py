import importlib.util
import sys
import tempfile
import unittest
from pathlib import Path

MODULE_PATH = Path(__file__).with_name("consensus.py")
spec = importlib.util.spec_from_file_location("decoder_consensus", MODULE_PATH)
consensus = importlib.util.module_from_spec(spec)
sys.modules["decoder_consensus"] = consensus
spec.loader.exec_module(consensus)


class ConsensusPolicyTests(unittest.TestCase):
    def test_150d_is_not_a_live_speed_source(self):
        self.assertFalse(consensus.candidate_allowed("speedKmh", "150D", 0, "u16be"))

    def test_sdk_speed_mapping_is_canonical(self):
        self.assertTrue(consensus.candidate_allowed("speedKmh", "1505", 6, "u16be"))
        self.assertFalse(consensus.candidate_allowed("speedKmh", "1505", 0, "u16be"))

    def test_battery_current_keeps_signed_encoding(self):
        self.assertTrue(consensus.candidate_allowed("currentA", "1509", 0, "s16be"))
        self.assertFalse(consensus.candidate_allowed("currentA", "1509", 0, "u16be"))

    def test_odometer_keeps_original_width(self):
        self.assertTrue(consensus.candidate_allowed("odometerKm", "1506", 0, "u32be"))
        self.assertFalse(consensus.candidate_allowed("odometerKm", "1506", 2, "u16be"))

    def test_direct_power_keeps_original_sdk_layout(self):
        self.assertTrue(consensus.candidate_allowed("powerW", "1509", 9, "u16be"))
        self.assertFalse(consensus.candidate_allowed("powerW", "1509", 7, "u16be"))
        self.assertFalse(consensus.candidate_allowed("powerW", "150A", 9, "u16be"))

    def test_discrete_rules_cannot_reinterpret_known_telemetry(self):
        self.assertTrue(consensus.discrete_candidate_allowed("lightOn", "1508", 0))
        self.assertFalse(consensus.discrete_candidate_allowed("lightOn", "1509", 0))
        self.assertFalse(consensus.discrete_candidate_allowed("brakeActive", "150D", 8))
        self.assertFalse(consensus.discrete_candidate_allowed("brakeActive", "1508", 3))
        self.assertFalse(consensus.discrete_candidate_allowed("charging", "151D", 2))

    def test_live_reference_values_keep_their_real_source_channel(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "Live_Telemetrie.csv"
            path.write_text(
                "relative_ms;timestamp_ms;speed_kmh_candidate;battery_percent;voltage_v;current_a;power_w;motor_temp_c;battery_temp_c;trip_km;odometer_km;source_channel\n"
                "0;1000;12.3;75;50.6;4.0;202.4;;;;708.1;1505\n"
                "10;1010;12.3;75;50.6;4.0;202.4;;;;708.1;1509\n",
                encoding="utf-8",
            )
            values = consensus.read_live_lookup(path)
            self.assertEqual({"speedKmh": 12.3}, values[(0, "1505")])
            self.assertNotIn("speedKmh", values[(10, "1509")])
            self.assertEqual(75.0, values[(10, "1509")]["batteryPercent"])
            self.assertNotIn("tripDistanceKm", values[(0, "1505")])

    def test_canonical_signal_cannot_move_to_another_channel(self):
        self.assertFalse(consensus.candidate_allowed("currentA", "150A", 0, "s16be"))
        self.assertFalse(consensus.candidate_allowed("speedKmh", "151D", 6, "u16be"))

    def test_raw_reader_rejects_read_origin_and_ascii_hybrid(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "BLE_Rohdaten.csv"
            path.write_text(
                "relative_ms;timestamp_ms;channel;meaning;length;packet_no;changed_bytes;hex;origin\n"
                "0;1000;1505;x;18;1;0;00-00-00-00-FF-FF-00-7B-FF-FF-FF-FF-FF-00-00-00-00-00;NOTIFICATION\n"
                "10;1010;1505;x;18;2;0;00-00-00-00-FF-FF-00-7B-FF-FF-FF-FF-FF-00-00-00-00-00;READ\n"
                "20;1020;1505;x;18;3;0;00-00-00-00-FF-FF-00-00-33-34-35-36-37-38-39-35-3D-14;NOTIFICATION\n"
                "30;1030;1505;x;18;4;0;00-00-00-00-FF-FF-00-7B-FF-FF-FF-FF-FF-00-00-00-00-00;NOTIFICATION_REJECTED_HYBRID\n"
                "40;1040;1505;x;18;5;0;00-00-00-00-FF-FF-00-7B-FF-FF-FF-FF-FF-00-00-00-00-00;DIAGNOSTIC_OBSERVATION\n"
                "50;1050;1505;x;18;6;0;00-00-00-00-FF-FF-00-7B-FF-FF-FF-FF-FF-00-00-00-00-00;\n",
                encoding="utf-8",
            )
            rows = consensus.read_raw_rows(path)
            self.assertEqual(1, len(rows))
            self.assertEqual(0, rows[0]["relative_ms"])


if __name__ == "__main__":
    unittest.main()

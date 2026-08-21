import tempfile
import unittest
from pathlib import Path

from power_crosscheck import analyze


class PowerCrosscheckTests(unittest.TestCase):
    def test_uses_fresh_1509_direct_candidate_against_electrical_power(self):
        with tempfile.TemporaryDirectory() as tmp:
            ride = Path(tmp) / "Messfahrt_test"
            ride.mkdir()
            (ride / "Live_Telemetrie.csv").write_text(
                "relative_ms;timestamp_ms;motor_load_raw_be;electrical_power_w;source_channel\n"
                "0;1000;100;95;1509\n"
                "10;1010;200;205;1509\n"
                "20;1020;999;10;1505\n",
                encoding="utf-8",
            )
            result = analyze(Path(tmp))
            self.assertEqual(2, result["comparisons"])
            self.assertEqual(5.0, result["maeW"])
            self.assertEqual(100.0, result["closePercent"])
            self.assertEqual(1, result["ridesWithComparisons"])
            self.assertFalse(result["independentExternalConfirmation"])

    def test_negative_electrical_power_is_compared_as_magnitude(self):
        with tempfile.TemporaryDirectory() as tmp:
            ride = Path(tmp) / "Messfahrt_regen"
            ride.mkdir()
            (ride / "Live_Telemetrie.csv").write_text(
                "relative_ms;timestamp_ms;motor_load_raw_be;electrical_power_w;source_channel\n"
                "0;1000;120;-118;1509\n",
                encoding="utf-8",
            )
            result = analyze(Path(tmp))
            self.assertEqual(1, result["comparisons"])
            self.assertEqual(2.0, result["maeW"])

    def test_missing_or_wrong_channel_rows_are_not_invented(self):
        with tempfile.TemporaryDirectory() as tmp:
            ride = Path(tmp) / "Messfahrt_missing"
            ride.mkdir()
            (ride / "Live_Telemetrie.csv").write_text(
                "relative_ms;timestamp_ms;motor_load_raw_be;electrical_power_w;source_channel\n"
                "0;1000;;95;1509\n"
                "10;1010;200;;1509\n"
                "20;1020;200;205;1505\n",
                encoding="utf-8",
            )
            result = analyze(Path(tmp))
            self.assertEqual(0, result["comparisons"])
            self.assertEqual("NEEDS_MORE_COMPARISONS", result["status"])
            self.assertIsNone(result["maeW"])


if __name__ == "__main__":
    unittest.main()

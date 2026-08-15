import importlib.util
import sys
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


if __name__ == "__main__":
    unittest.main()

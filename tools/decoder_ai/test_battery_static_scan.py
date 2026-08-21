import tempfile
import unittest
from pathlib import Path

import battery_static_scan


HEADER = "relative_ms;timestamp_ms;channel;meaning;length;packet_no;changed_bytes;hex;origin;connection_epoch"


class BatteryStaticScanTests(unittest.TestCase):
    def test_repeated_static_words_and_150c_presence_are_observations_only(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp) / "fahrdaten"
            ride = root / "2026-01-01" / "Messfahrt_test"
            ride.mkdir(parents=True)
            rows = [
                ["0", "1000", "1502", "Akkuinformationen", "16", "1", "0", "47-18-FF-FF-FF-FF-47-18-FF-FF-FF-FF-FF-FF-00-00", "NOTIFICATION", "0"],
                ["10", "1010", "1502", "Akkuinformationen", "16", "2", "0", "47-18-FF-FF-FF-FF-47-18-FF-FF-FF-FF-FF-FF-00-00", "NOTIFICATION", "0"],
                ["20", "1020", "150C", "BatteryCellUpdate", "13", "1", "0", "FF-FF-FF-80-00-80-00-80-00-00-00-00-00", "NOTIFICATION", "0"],
                ["30", "1030", "1502", "Rejected hybrid", "16", "3", "0", "00-01-00-02-00-03-00-04-00-05-00-06-00-07-00-08", "NOTIFICATION_REJECTED_HYBRID", "0"],
                ["40", "1040", "150C", "Diagnostic observation", "13", "2", "0", "01-02-03-04-05-06-07-08-09-0A-0B-0C-0D", "DIAGNOSTIC_OBSERVATION", "0"],
                ["50", "1050", "1502", "Unclassified legacy-like row", "16", "4", "0", "00-01-00-02-00-03-00-04-00-05-00-06-00-07-00-08", "", "0"],
            ]
            (ride / "BLE_Rohdaten.csv").write_text(
                HEADER + "\n" + "\n".join(";".join(row) for row in rows) + "\n", encoding="utf-8"
            )
            result = battery_static_scan.analyze(root)
            self.assertEqual(2, result["channel1502"]["packets"])
            word0 = next(item for item in result["channel1502"]["alignedU16be"] if item["offset"] == 0)
            word6 = next(item for item in result["channel1502"]["alignedU16be"] if item["offset"] == 6)
            self.assertEqual(18200, word0["topValues"][0]["value"])
            self.assertEqual(18200, word6["topValues"][0]["value"])
            self.assertTrue(any(item["offsetA"] == 0 and item["offsetB"] == 6 for item in result["channel1502"]["highEqualityWordPairs"]))
            self.assertEqual(1, result["channel150C"]["packets"])
            self.assertIn("No capacity/SOH meaning", result["channel1502"]["interpretation"])


if __name__ == "__main__":
    unittest.main()

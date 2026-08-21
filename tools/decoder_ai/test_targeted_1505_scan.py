import tempfile
import unittest
from pathlib import Path

import targeted_1505_scan


HEADER = "relative_ms;timestamp_ms;channel;meaning;length;packet_no;changed_bytes;hex;origin;connection_epoch"


def write_rows(path: Path, rows: list[list[str]]):
    path.write_text(HEADER + "\n" + "\n".join(";".join(row) for row in rows) + "\n", encoding="utf-8")


class Targeted1505ScanTests(unittest.TestCase):
    def test_duplicate_power_and_sentinel_rpm_range_are_reported(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp) / "fahrdaten"
            ride = root / "2026-01-01" / "Messfahrt_test"
            ride.mkdir(parents=True)
            rows = []
            for index in range(5):
                ts = 1000 + index * 100
                # A=100 W, B=100 W, speed=10 km/h, RPM/Range unavailable (FFFF)
                rows.append([
                    str(index * 100), str(ts), "1505", "Fahrleistung", "12", str(index + 1), "0",
                    "03-E8-03-E8-00-00-00-64-FF-FF-FF-FF", "NOTIFICATION", "0"
                ])
                # current=2 A, SOC=80, voltage=50 V => |V*I|=100 W
                rows.append([
                    str(index * 100 + 10), str(ts + 10), "1509", "Akku", "11", str(index + 1), "0",
                    "07-D0-00-00-50-C3-50-00-00-00-64", "NOTIFICATION", "0"
                ])
            rows += [
                ["1000", "2000", "1505", "Rejected hybrid", "12", "6", "0", "00-01-00-02-00-00-00-03-00-04-00-05", "NOTIFICATION_REJECTED_HYBRID", "0"],
                ["1010", "2010", "1509", "Diagnostic observation", "11", "6", "0", "07-D0-00-00-50-C3-50-00-00-00-64", "DIAGNOSTIC_OBSERVATION", "0"],
                ["1020", "2020", "1505", "Missing origin", "12", "7", "0", "00-01-00-02-00-00-00-03-00-04-00-05", "", "0"],
            ]
            write_rows(ride / "BLE_Rohdaten.csv", rows)

            result = targeted_1505_scan.analyze(root)
            self.assertEqual(5, result["packets1505"])
            self.assertEqual("DUPLICATED_ON_CURRENT_BT638_DATA", result["powerAB"]["status"])
            self.assertEqual(100.0, result["powerAB"]["equalPercent"])
            self.assertEqual(5, result["rpm1505_8"]["rawStates"]["ffff"])
            self.assertEqual(5, result["remainingDistance1505_10"]["rawStates"]["ffff"])
            self.assertEqual(0, result["rpm1505_8"]["values"]["samples"])
            self.assertEqual(0, result["remainingDistance1505_10"]["values"]["samples"])
            self.assertEqual(5, result["powerAB"]["powerA_vs_absVI"]["comparisons"])
            self.assertEqual(100.0, result["powerAB"]["powerA_vs_absVI"]["closePercent"])

    def test_real_rpm_and_range_values_are_kept_as_observations_only(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp) / "fahrdaten"
            ride = root / "2026-01-01" / "Messfahrt_test"
            ride.mkdir(parents=True)
            rows = [
                ["0", "1000", "1505", "Fahrleistung", "12", "1", "0", "00-64-00-32-00-00-00-64-01-2C-00-32", "NOTIFICATION", "0"],
                ["100", "1100", "1505", "Fahrleistung", "12", "2", "0", "00-C8-00-64-00-00-00-C8-02-58-00-31", "NOTIFICATION", "0"],
                ["200", "1200", "1505", "Fahrleistung", "12", "3", "0", "01-2C-00-96-00-00-01-2C-03-84-00-30", "NOTIFICATION", "0"],
            ]
            write_rows(ride / "BLE_Rohdaten.csv", rows)
            result = targeted_1505_scan.analyze(root)
            self.assertEqual("DIFFERENCES_OBSERVED", result["powerAB"]["status"])
            self.assertEqual("RPM_VALUES_OBSERVED_NEEDS_VALIDATION", result["rpm1505_8"]["status"])
            self.assertEqual("RANGE_VALUES_OBSERVED_NEEDS_VALIDATION", result["remainingDistance1505_10"]["status"])
            self.assertEqual(300.0, result["rpm1505_8"]["values"]["min"])
            self.assertEqual(50.0, result["remainingDistance1505_10"]["values"]["max"])


if __name__ == "__main__":
    unittest.main()

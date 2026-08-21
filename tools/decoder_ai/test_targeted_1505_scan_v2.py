import tempfile
import unittest
from pathlib import Path

import targeted_1505_scan_v2


HEADER = "relative_ms;timestamp_ms;channel;meaning;length;packet_no;changed_bytes;hex;origin;connection_epoch"


class Targeted1505ScanV2Tests(unittest.TestCase):
    def test_ascii_firmware_hybrid_is_not_treated_as_rpm_or_range(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp) / "fahrdaten"
            ride = root / "2026-01-01" / "Messfahrt_test"
            ride.mkdir(parents=True)
            rows = [
                # Genuine packet: RPM/range unavailable.
                ["0", "1000", "1505", "BikePerformance", "18", "1", "0",
                 "00-64-00-64-FF-FF-00-64-FF-FF-FF-FF-FF-00-00-00-00-00", "NOTIFICATION", "0"],
                # Historical contamination pattern. Bytes 8..11 are ASCII '3456'.
                ["10", "1010", "1505", "BikePerformance", "18", "2", "0",
                 "00-49-4B-45-5F-30-31-32-33-34-35-36-37-38-39-35-3D-14", "NOTIFICATION", "0"],
            ]
            (ride / "BLE_Rohdaten.csv").write_text(
                HEADER + "\n" + "\n".join(";".join(row) for row in rows) + "\n",
                encoding="utf-8",
            )

            result = targeted_1505_scan_v2.analyze(root)
            self.assertEqual(1, result["filtered1505HybridPackets"])
            self.assertEqual(1, result["packets1505"])
            self.assertEqual({"ffff": 1}, result["rpm1505_8"]["rawStates"])
            self.assertEqual({"ffff": 1}, result["remainingDistance1505_10"]["rawStates"])
            self.assertEqual(100.0, result["powerAB"]["equalPercent"])


if __name__ == "__main__":
    unittest.main()

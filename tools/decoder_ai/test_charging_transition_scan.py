import tempfile
import unittest
from pathlib import Path

import charging_transition_scan as scan


RAW_HEADER = "relative_ms;timestamp_ms;channel;meaning;length;packet_no;changed_bytes;hex;origin;connection_epoch"
EVENT_HEADER = "relative_ms;timestamp_ms;marker"


def write(path: Path, header: str, rows: list[list[str]]) -> None:
    path.write_text(header + "\n" + "\n".join(";".join(row) for row in rows) + "\n", encoding="utf-8")


class ChargingTransitionScanTests(unittest.TestCase):
    def test_explicit_charge_gap_compares_real_before_after_samples(self):
        with tempfile.TemporaryDirectory() as tmp:
            ride = Path(tmp) / "Messfahrt_test"
            ride.mkdir()
            write(
                ride / "BLE_Rohdaten.csv",
                RAW_HEADER,
                [
                    ["0", "1000", "1509", "Battery", "11", "1", "0", "00-00-FF-FF-50-C3-50-00-00-00-00", "NOTIFICATION", "0"],
                    ["100", "1100", "1502", "Battery static", "16", "1", "0", "47-18-FF-FF-FF-FF-47-18-FF-FF-FF-FF-FF-FF-00-00", "NOTIFICATION", "0"],
                    ["120000", "121000", "1509", "Battery", "11", "2", "0", "00-00-FF-FF-5A-C8-00-00-00-00-00", "NOTIFICATION", "1"],
                    ["120100", "121100", "1502", "Battery static", "16", "2", "0", "47-18-FF-FF-FF-FF-47-18-FF-FF-FF-FF-FF-FF-00-00", "NOTIFICATION", "1"],
                ],
            )
            write(
                ride / "Ereignisse.csv",
                EVENT_HEADER,
                [["1000", "2000", "Ladegerät einstecken"], ["119000", "120000", "Ladegerät abziehen"]],
            )

            result = scan.analyze(Path(tmp))
            self.assertEqual(1, result["transitionCount"])
            transition = result["transitions"][0]
            self.assertEqual("EXPLICIT_CHARGE_GAP_OBSERVED", transition["status"])
            self.assertEqual(10, transition["delta"]["socPercent"])
            self.assertTrue(transition["connectionEpochChanged"])
            self.assertEqual(
                "47-18-FF-FF-FF-FF-47-18-FF-FF-FF-FF-FF-FF-00-00",
                transition["before"]["raw1502"],
            )

    def test_gap_without_charge_marker_does_not_claim_charging(self):
        before = scan.BatterySample(1000, 0, 0, 80, 50.0, 0.0, None, "x")
        after = scan.BatterySample(12000, 11000, 1, 80, 50.05, 0.0, None, "y")
        status, reasons = scan.classify_gap(before, after, [])
        self.assertEqual("BLE_GAP_NO_CLEAR_CHARGE_EVIDENCE", status)
        self.assertEqual([], reasons)

    def test_read_rows_are_not_used_as_live_battery_samples(self):
        row = {
            "relative_ms": "0",
            "timestamp_ms": "1000",
            "channel": "1509",
            "hex": "00-00-FF-FF-50-C3-50-00-00-00-00",
            "origin": "READ",
            "connection_epoch": "0",
        }
        self.assertIsNone(scan.decode_1509(row))


if __name__ == "__main__":
    unittest.main()

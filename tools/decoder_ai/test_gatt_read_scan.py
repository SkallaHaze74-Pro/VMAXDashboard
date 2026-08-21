import tempfile
import unittest
from pathlib import Path

import gatt_read_scan


HEADER = "timestamp_ms;service_uuid;characteristic_uuid;short_id;status;length;hex;evidence;meaning"


class GattReadScanTests(unittest.TestCase):
    def test_read_payloads_are_aggregated_without_semantic_confirmation(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp) / "diagnostics"
            first = root / "2026-08-21" / "DeepRead_1"
            second = root / "2026-08-21" / "DeepRead_2"
            first.mkdir(parents=True)
            second.mkdir(parents=True)
            (first / "Gatt_READ_Diagnose.csv").write_text(
                HEADER + "\n" +
                "1;1500;1516;1516;0;3;31-32-33;SDK;Serial\n" +
                "2;1500;150C;150C;0;4;FF-80-00-FF;SDK;BatteryCell\n",
                encoding="utf-8",
            )
            (second / "Gatt_READ_Diagnose.csv").write_text(
                HEADER + "\n" +
                "3;1500;1516;1516;0;3;31-32-33;SDK;Serial\n" +
                "4;1500;150C;150C;0;4;FF-80-01-FF;SDK;BatteryCell\n" +
                "5;1500;1517;1517;-1002;0;;SDK;ErrorString\n",
                encoding="utf-8",
            )

            payload = gatt_read_scan.build_report(root)
            self.assertEqual(2, payload["diagnosticBundles"])
            self.assertEqual(5, payload["records"])

            serial = next(field for field in payload["fields"] if field["characteristic"] == "1516")
            self.assertEqual(2, serial["successes"])
            self.assertEqual(1, serial["uniquePayloadCount"])
            self.assertFalse(serial["dynamicAcrossScans"])
            self.assertFalse(serial["independentSemanticConfirmation"])

            cells = next(field for field in payload["fields"] if field["characteristic"] == "150C")
            self.assertTrue(cells["dynamicAcrossScans"])
            self.assertEqual([2], cells["variableByteIndexes"])
            self.assertFalse(cells["sentinelLikeOnly"])
            self.assertFalse(cells["independentSemanticConfirmation"])

    def test_sentinel_only_payload_is_flagged_but_not_interpreted(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp) / "diagnostics"
            folder = root / "DeepRead"
            folder.mkdir(parents=True)
            (folder / "Gatt_READ_Diagnose.csv").write_text(
                HEADER + "\n" +
                "1;1500;150C;150C;0;6;FF-FF-80-00-80-00;SDK;BatteryCell\n",
                encoding="utf-8",
            )
            payload = gatt_read_scan.build_report(root)
            field = payload["fields"][0]
            self.assertTrue(field["sentinelLikeOnly"])
            self.assertFalse(field["independentSemanticConfirmation"])
            report = gatt_read_scan.render_markdown(payload)
            self.assertIn("beweist noch keine Byte-Semantik", report)


if __name__ == "__main__":
    unittest.main()

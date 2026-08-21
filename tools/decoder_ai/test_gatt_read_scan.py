import hashlib
import tempfile
import unittest
from pathlib import Path

import gatt_read_scan


HEADER = "timestamp_ms;service_uuid;characteristic_uuid;short_id;status;length;hex;evidence;meaning"
V2_HEADER = (
    "timestamp_ms;service_uuid;characteristic_uuid;short_id;properties;status;length;hex;"
    "connection_epoch;measurement_connection_epoch;evidence;meaning"
)
V3_HEADER = (
    "timestamp_ms;scan_id;record_kind;outcome;callback_received;service_uuid;characteristic_uuid;"
    "short_id;properties;properties_raw;status;length;hex;payload_valid;payload_sha256;"
    "public_redaction;connection_epoch;measurement_connection_epoch;rssi;evidence;meaning"
)


class GattReadScanTests(unittest.TestCase):
    def test_exact_hex_and_its_stored_hash_are_one_payload_variant(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp) / "diagnostics"
            first = root / "DeepRead_1"
            second = root / "DeepRead_2"
            first.mkdir(parents=True)
            second.mkdir(parents=True)
            payload_hex = "47-18-FF-FF-FF-FF-47-18-FF-FF-FF-FF-FF-FF-00-00"
            payload_hash = hashlib.sha256(bytes.fromhex(payload_hex.replace("-", ""))).hexdigest()
            first_row = (
                "1;scan-a;GATT_READ_CALLBACK;CALLBACK_SUCCESS;true;1500;1502;1502;READ;2;0;16;"
                f"{payload_hex};true;{payload_hash};;7;;;BT638;Static block\n"
            )
            second_row = first_row.replace("1;scan-a", "2;scan-b")
            (first / "Gatt_READ_Diagnose.csv").write_text(V3_HEADER + "\n" + first_row, encoding="utf-8")
            (second / "Gatt_READ_Diagnose.csv").write_text(V3_HEADER + "\n" + second_row, encoding="utf-8")

            payload = gatt_read_scan.build_report(root)
            field = payload["fields"][0]

            self.assertEqual(1, field["uniquePayloadCount"])
            self.assertFalse(field["dynamicAcrossScans"])
            self.assertEqual([], field["variableByteIndexes"])
            self.assertEqual([payload_hex], field["samplePayloads"])
            self.assertEqual([payload_hash], field["samplePayloadHashes"])

    def test_exact_and_redacted_hash_only_rows_share_one_payload_variant(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp) / "diagnostics"
            exact = root / "DeepRead_exact"
            redacted = root / "DeepRead_redacted"
            exact.mkdir(parents=True)
            redacted.mkdir(parents=True)
            payload_hex = "FF-56-4D-41-58-00"
            payload_hash = hashlib.sha256(bytes.fromhex(payload_hex.replace("-", ""))).hexdigest()
            exact_row = (
                "1;scan-a;GATT_READ_CALLBACK;CALLBACK_SUCCESS;true;1500;1511;1511;READ;2;0;6;"
                f"{payload_hex};true;{payload_hash};;7;;;BT638;Unknown field\n"
            )
            redacted_row = (
                "2;scan-b;GATT_READ_CALLBACK;CALLBACK_SUCCESS;true;1500;1511;1511;READ;2;0;6;;"
                f"true;{payload_hash};identity_or_free_form;7;;;BT638;REDACTED_IDENTITY_OR_FREE_FORM\n"
            )
            (exact / "Gatt_READ_Diagnose.csv").write_text(V3_HEADER + "\n" + exact_row, encoding="utf-8")
            (redacted / "Gatt_READ_Diagnose.csv").write_text(V3_HEADER + "\n" + redacted_row, encoding="utf-8")

            payload = gatt_read_scan.build_report(root)
            field = payload["fields"][0]

            self.assertEqual(2, field["validPayloadCount"])
            self.assertEqual(1, field["uniquePayloadCount"])
            self.assertFalse(field["dynamicAcrossScans"])
            self.assertEqual([], field["samplePayloads"])
            self.assertEqual([payload_hash], field["samplePayloadHashes"])
            self.assertTrue(field["sensitivePayloadRedacted"])

    def test_invalid_callback_hash_does_not_create_a_valid_payload_variant(self):
        with tempfile.TemporaryDirectory() as tmp:
            folder = Path(tmp) / "diagnostics" / "DeepRead"
            folder.mkdir(parents=True)
            valid_hex = "01-02-03"
            invalid_hex = "09-09-09"
            valid_hash = hashlib.sha256(bytes.fromhex(valid_hex.replace("-", ""))).hexdigest()
            invalid_hash = hashlib.sha256(bytes.fromhex(invalid_hex.replace("-", ""))).hexdigest()
            valid_row = (
                "1;scan-a;GATT_READ_CALLBACK;CALLBACK_SUCCESS;true;1500;1509;1509;READ;2;0;3;"
                f"{valid_hex};true;{valid_hash};;7;;;BT638;Battery\n"
            )
            invalid_row = (
                "2;scan-b;GATT_READ_CALLBACK;CALLBACK_ERROR;true;1500;1509;1509;READ;2;133;3;"
                f"{invalid_hex};false;{invalid_hash};;7;;;BT638;Battery\n"
            )
            (folder / "Gatt_READ_Diagnose.csv").write_text(
                V3_HEADER + "\n" + valid_row + invalid_row,
                encoding="utf-8",
            )

            field = gatt_read_scan.build_report(Path(tmp) / "diagnostics")["fields"][0]

            self.assertEqual(1, field["uniquePayloadCount"])
            self.assertFalse(field["dynamicAcrossScans"])
            self.assertEqual([invalid_hash], field["invalidPayloadHashes"])

    def test_different_exact_plus_hash_payloads_remain_dynamic(self):
        with tempfile.TemporaryDirectory() as tmp:
            folder = Path(tmp) / "diagnostics" / "DeepRead"
            folder.mkdir(parents=True)
            payloads = ["01-02-03-04", "01-02-09-04"]
            rows = []
            for index, payload_hex in enumerate(payloads, start=1):
                payload_hash = hashlib.sha256(bytes.fromhex(payload_hex.replace("-", ""))).hexdigest()
                rows.append(
                    f"{index};scan-{index};GATT_READ_CALLBACK;CALLBACK_SUCCESS;true;1500;1509;1509;"
                    f"READ;2;0;4;{payload_hex};true;{payload_hash};;7;;;BT638;Battery\n"
                )
            (folder / "Gatt_READ_Diagnose.csv").write_text(
                V3_HEADER + "\n" + "".join(rows),
                encoding="utf-8",
            )

            field = gatt_read_scan.build_report(Path(tmp) / "diagnostics")["fields"][0]

            self.assertEqual(2, field["uniquePayloadCount"])
            self.assertTrue(field["dynamicAcrossScans"])
            self.assertEqual([2], field["variableByteIndexes"])

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
            self.assertEqual([], serial["samplePayloads"])
            self.assertEqual(1, len(serial["samplePayloadHashes"]))
            self.assertEqual(64, len(serial["samplePayloadHashes"][0]))
            self.assertTrue(serial["sensitivePayloadRedacted"])

            cells = next(field for field in payload["fields"] if field["characteristic"] == "150C")
            self.assertTrue(cells["dynamicAcrossScans"])
            self.assertEqual([2], cells["variableByteIndexes"])
            self.assertFalse(cells["sentinelLikeOnly"])
            self.assertFalse(cells["independentSemanticConfirmation"])
            self.assertEqual(["FF-80-00-FF", "FF-80-01-FF"], cells["samplePayloads"])

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

    def test_v2_full_uuids_keep_short_target_properties_and_epoch(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp) / "fahrdaten"
            folder = root / "2026-08-21" / "Messfahrt_1"
            folder.mkdir(parents=True)
            (folder / "Gatt_READ_Diagnose.csv").write_text(
                V2_HEADER + "\n" +
                "1;da1a1500-d532-4285-be94-b07a3e11a098;"
                "da1a1516-d532-4285-be94-b07a3e11a098;1516;READ|NOTIFY;0;3;31-32-33;7;0;SDK;Serial\n",
                encoding="utf-8",
            )

            payload = gatt_read_scan.build_report(root)
            field = payload["fields"][0]
            self.assertEqual("1500", field["service"])
            self.assertEqual("1516", field["characteristic"])
            self.assertEqual("SerialNumbers", field["knownTarget"])
            self.assertEqual(["READ|NOTIFY"], field["properties"])
            self.assertEqual(["7"], field["connectionEpochs"])
            self.assertEqual(["0"], field["measurementConnectionEpochs"])
            self.assertTrue(field["characteristicUuid"].startswith("da1a1516"))

    def test_multiple_roots_are_scanned_without_duplicate_root_paths(self):
        with tempfile.TemporaryDirectory() as tmp:
            base = Path(tmp)
            standalone = base / "diagnostics" / "DeepRead"
            embedded = base / "fahrdaten" / "Messfahrt"
            standalone.mkdir(parents=True)
            embedded.mkdir(parents=True)
            for index, folder in enumerate((standalone, embedded), start=1):
                (folder / "Gatt_READ_Diagnose.csv").write_text(
                    HEADER + "\n" + f"{index};1500;151{index};151{index};0;1;0{index};SDK;Target\n",
                    encoding="utf-8",
                )

            payload = gatt_read_scan.build_report([base / "diagnostics", base / "fahrdaten"])
            self.assertEqual(2, payload["diagnosticBundles"])
            self.assertEqual(2, payload["records"])

    def test_standalone_and_measurement_copy_of_same_scan_are_counted_once(self):
        with tempfile.TemporaryDirectory() as tmp:
            base = Path(tmp)
            standalone = base / "diagnostics" / "DeepRead"
            embedded = base / "fahrdaten" / "Messfahrt"
            standalone.mkdir(parents=True)
            embedded.mkdir(parents=True)
            standalone_row = (
                V2_HEADER + "\n" +
                "1;da1a1500-d532-4285-be94-b07a3e11a098;"
                "da1a150c-d532-4285-be94-b07a3e11a098;150C;READ;0;2;01-02;7;;SDK;BatteryCell\n"
            )
            embedded_row = standalone_row.replace(";7;;SDK;", ";7;0;SDK;")
            (standalone / "Gatt_READ_Diagnose.csv").write_text(standalone_row, encoding="utf-8")
            (embedded / "Gatt_READ_Diagnose.csv").write_text(embedded_row, encoding="utf-8")

            payload = gatt_read_scan.build_report([base / "diagnostics", base / "fahrdaten"])

            self.assertEqual(1, payload["diagnosticBundles"])
            self.assertEqual(2, payload["sourceBundleCopies"])
            self.assertEqual(1, payload["duplicateBundleCopies"])
            self.assertEqual(1, payload["records"])
            self.assertEqual(["0"], payload["fields"][0]["measurementConnectionEpochs"])

    def test_v1_and_v2_uuid_forms_share_one_canonical_characteristic(self):
        with tempfile.TemporaryDirectory() as tmp:
            base = Path(tmp)
            old = base / "diagnostics" / "old"
            new = base / "diagnostics" / "new"
            old.mkdir(parents=True)
            new.mkdir(parents=True)
            (old / "Gatt_READ_Diagnose.csv").write_text(
                HEADER + "\n1;1500;1516;1516;0;1;31;SDK;Serial\n",
                encoding="utf-8",
            )
            (new / "Gatt_READ_Diagnose.csv").write_text(
                V2_HEADER + "\n2;da1a1500-d532-4285-be94-b07a3e11a098;"
                "da1a1516-d532-4285-be94-b07a3e11a098;1516;READ;0;1;32;7;0;SDK;Serial\n",
                encoding="utf-8",
            )

            payload = gatt_read_scan.build_report(base / "diagnostics")

            self.assertEqual(1, payload["characteristics"])
            self.assertEqual(2, payload["fields"][0]["attempts"])
            self.assertEqual(["1500", "da1a1500-d532-4285-be94-b07a3e11a098"], payload["fields"][0]["serviceUuidForms"])

    def test_scan_id_dedupes_two_standalone_scans_inside_one_ride_copy(self):
        with tempfile.TemporaryDirectory() as tmp:
            base = Path(tmp)
            standalone_a = base / "diagnostics" / "a"
            standalone_b = base / "diagnostics" / "b"
            embedded = base / "fahrdaten" / "ride"
            for folder in (standalone_a, standalone_b, embedded):
                folder.mkdir(parents=True)
            row_a = (
                "1;scan-a;GATT_READ_CALLBACK;CALLBACK_SUCCESS;true;"
                "da1a1500-d532-4285-be94-b07a3e11a098;da1a150c-d532-4285-be94-b07a3e11a098;"
                "150C;READ;2;0;1;01;true;;;7;;;SDK;Cells\n"
            )
            row_b = (
                "2;scan-b;GATT_READ_CALLBACK;CALLBACK_SUCCESS;true;"
                "da1a1500-d532-4285-be94-b07a3e11a098;da1a1514-d532-4285-be94-b07a3e11a098;"
                "1514;READ;2;0;1;02;true;;;7;;;SDK;Error\n"
            )
            (standalone_a / "Gatt_READ_Diagnose.csv").write_text(V3_HEADER + "\n" + row_a, encoding="utf-8")
            (standalone_b / "Gatt_READ_Diagnose.csv").write_text(V3_HEADER + "\n" + row_b, encoding="utf-8")
            (embedded / "Gatt_READ_Diagnose.csv").write_text(
                V3_HEADER + "\n" + row_a.replace(";7;;;", ";7;0;;") + row_b.replace(";7;;;", ";7;1;;"),
                encoding="utf-8",
            )

            payload = gatt_read_scan.build_report([base / "diagnostics", base / "fahrdaten"])

            self.assertEqual(2, payload["diagnosticBundles"])
            self.assertEqual(4, payload["sourceBundleCopies"])
            self.assertEqual(2, payload["duplicateBundleCopies"])
            self.assertEqual(2, payload["records"])

    def test_non_callback_events_are_not_reported_as_read_callbacks(self):
        with tempfile.TemporaryDirectory() as tmp:
            folder = Path(tmp) / "diagnostics" / "events"
            folder.mkdir(parents=True)
            callback = (
                "1;scan-a;GATT_READ_CALLBACK;CALLBACK_ERROR;true;1500;1516;1516;READ;2;133;"
                "1;31;false;hash;identity_or_free_form;7;;;SDK;Serial\n"
            )
            observation = (
                "2;scan-a;BLE_OBSERVATION;ADVERTISEMENT_OBSERVED;false;;;;;0;;2;01-02;true;;;"
                "7;;-55;OBSERVED;Advertisement\n"
            )
            (folder / "Gatt_READ_Diagnose.csv").write_text(
                V3_HEADER + "\n" + callback + observation,
                encoding="utf-8",
            )

            payload = gatt_read_scan.build_report(Path(tmp) / "diagnostics")

            self.assertEqual(2, payload["records"])
            self.assertEqual(1, payload["readAttempts"])
            self.assertEqual(1, payload["readCallbacks"])
            self.assertEqual(1, payload["payloadCallbacks"])
            self.assertEqual(0, payload["validPayloadCallbacks"])
            self.assertEqual(1, payload["observations"])
            self.assertEqual(1, payload["characteristics"])
            field = payload["fields"][0]
            self.assertEqual(1, field["payloadCount"])
            self.assertEqual(0, field["validPayloadCount"])
            self.assertEqual(1, field["invalidPayloadCount"])
            self.assertEqual([], field["samplePayloads"])
            self.assertEqual(["hash"], field["invalidPayloadHashes"])

    def test_full_uuid_in_short_id_is_still_canonicalized(self):
        with tempfile.TemporaryDirectory() as tmp:
            folder = Path(tmp) / "diagnostics" / "uuid"
            folder.mkdir(parents=True)
            row = (
                "1;scan-a;GATT_READ_CALLBACK;CALLBACK_SUCCESS;true;"
                "00001800-0000-1000-8000-00805f9b34fb;00002a00-0000-1000-8000-00805f9b34fb;"
                "00002a00-0000-1000-8000-00805f9b34fb;READ;2;0;2;01-02;true;;;7;;;SDK;Name\n"
            )
            (folder / "Gatt_READ_Diagnose.csv").write_text(V3_HEADER + "\n" + row, encoding="utf-8")

            payload = gatt_read_scan.build_report(Path(tmp) / "diagnostics")

            self.assertEqual("1800", payload["fields"][0]["service"])
            self.assertEqual("2A00", payload["fields"][0]["characteristic"])
            self.assertTrue(payload["fields"][0]["sensitivePayloadRedacted"])


if __name__ == "__main__":
    unittest.main()

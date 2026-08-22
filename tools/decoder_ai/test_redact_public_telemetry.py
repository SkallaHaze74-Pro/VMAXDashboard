import csv
import contextlib
import hashlib
import io
import tempfile
import unittest
from pathlib import Path

import redact_public_telemetry as redaction


RAW_HEADER = "relative_ms;timestamp_ms;channel;meaning;length;packet_no;changed_bytes;hex"
DEEP_HEADER = (
    "timestamp_ms;scan_id;record_kind;outcome;callback_received;service_uuid;"
    "characteristic_uuid;short_id;properties;properties_raw;status;length;hex;"
    "payload_valid;payload_sha256;public_redaction;connection_epoch;"
    "measurement_connection_epoch;rssi;evidence;meaning"
)
DEEP_V1_HEADER = (
    "timestamp_ms;service_uuid;characteristic_uuid;short_id;status;length;hex;evidence;meaning"
)
DEEP_V2_HEADER = (
    "timestamp_ms;service_uuid;characteristic_uuid;short_id;properties;status;length;hex;"
    "connection_epoch;measurement_connection_epoch;evidence;meaning"
)


def parse(text: str) -> list[dict[str, str]]:
    return list(csv.DictReader(io.StringIO(text), delimiter=";"))


class PublicTelemetryRedactionTests(unittest.TestCase):
    def test_raw_sensitive_ids_and_free_form_are_hashed_but_binary_stays_exact(self):
        text_payload = "53-45-43-52-45-54"
        identity_payload = "DE-AD-BE-EF"
        binary_payload = "00-00-1B-BB-00-02-27-3B-00-00-02-C5-00-00-00-00"
        source = "\n".join(
            [
                RAW_HEADER,
                f"1;1001;1516;Serial;4;1;-;{identity_payload}",
                f"2;1002;9999;Unknown;6;2;-;{text_payload}",
                f"3;1003;1509;Battery;16;3;-;{binary_payload}",
            ]
        )

        result = redaction.redact_raw_csv(source)
        rows = parse(result.public_text)

        self.assertEqual(2, result.modified_rows)
        self.assertEqual(2, result.exact_payload_rows)
        self.assertEqual("", rows[0]["hex"])
        self.assertEqual("", rows[1]["hex"])
        self.assertEqual(binary_payload, rows[2]["hex"])
        self.assertEqual(redaction.payload_sha256(identity_payload), rows[0]["payload_sha256"])
        self.assertEqual(redaction.payload_sha256(text_payload), rows[1]["payload_sha256"])
        self.assertEqual(redaction.PUBLIC_REDACTION, rows[0]["public_redaction"])
        self.assertEqual("", rows[2]["public_redaction"])

    def test_deep_read_redacts_target_meaning_observation_and_free_form(self):
        def row(index: int, short_id: str, kind: str, meaning: str, payload: str) -> str:
            return (
                f"{index};scan-{index};{kind};CALLBACK_SUCCESS;true;1500;{short_id};{short_id};"
                f"READ;2;0;4;{payload};true;;;7;;;SDK;{meaning}"
            )

        exact = [
            row(1, "1516", "GATT_READ_CALLBACK", "Unknown", "DE-AD-BE-EF"),
            row(2, "9998", "GATT_READ_CALLBACK", "Serial candidate", "01-02-03-04"),
            row(3, "9997", "BLE_OBSERVATION", "Unknown", "01-02-03-04"),
            row(4, "9996", "GATT_READ_CALLBACK", "Unknown", "41-42"),
        ]
        binary = "00-00-1B-BB-00-02-27-3B-00-00-02-C5-00-00-00-00"
        source = "\n".join([DEEP_HEADER, *exact, row(5, "1509", "GATT_READ_CALLBACK", "Battery", binary)])

        result = redaction.redact_deep_read_csv(source)
        rows = parse(result.public_text)

        self.assertEqual(4, result.modified_rows)
        self.assertEqual(4, result.exact_payload_rows)
        for item in rows[:4]:
            self.assertEqual("", item["hex"])
            self.assertEqual(redaction.PUBLIC_MEANING, item["meaning"])
            self.assertEqual(redaction.PUBLIC_REDACTION, item["public_redaction"])
            self.assertEqual(64, len(item["payload_sha256"]))
        self.assertEqual(binary, rows[4]["hex"])
        self.assertEqual("Battery", rows[4]["meaning"])

    def test_full_uuid_is_canonicalized_before_sensitive_match(self):
        full_uuid = "00002a00-0000-1000-8000-00805f9b34fb"
        raw_source = (
            RAW_HEADER
            + "\n"
            + f"1;1001;{full_uuid};Unknown;4;1;-;DE-AD-BE-EF\n"
        )
        deep_source = (
            DEEP_HEADER
            + "\n"
            + f"1;scan-1;GATT_READ_CALLBACK;CALLBACK_SUCCESS;true;1800;{full_uuid};;"
            + "READ;2;0;4;DE-AD-BE-EF;true;;;7;;;SDK;Unknown\n"
        )

        raw_result = redaction.redact_raw_csv(raw_source)
        deep_result = redaction.redact_deep_read_csv(deep_source)

        self.assertEqual(1, raw_result.modified_rows)
        self.assertEqual("", parse(raw_result.public_text)[0]["hex"])
        self.assertEqual(1, deep_result.modified_rows)
        self.assertEqual("", parse(deep_result.public_text)[0]["hex"])

    def test_payload_hash_matches_fixed_android_contract_vectors(self):
        expected_bytes = "a665a45920422f9d417e4867efdc4fb8a04a1f3fff1fa07e998e86f7f7a27ae3"
        expected_text = "95340811d2f3ab05aa125fb6d059b6e02911560314c125fd2e99ad01d995cbd2"

        self.assertEqual(expected_bytes, redaction.payload_sha256("31-32-33"))
        self.assertEqual(expected_bytes, redaction.payload_sha256("31:32:33"))
        self.assertEqual(expected_text, redaction.payload_sha256("not-hex"))

    def test_text_detector_matches_android_contract_examples(self):
        text_payloads = [
            "C3-84-42",
            "41-00-42-00",
            "00-41-00-42",
            "FF-FE-41-00-42-00",
            "41-42",
            "FE-54-45-53-54-2D-31-32-33-81",
            "01-C3-84-42-43-31-32-33-00",
            "7F-41-00-42-00-43-00-44-00-81",
            "81-00-41-00-42-00-43-00-44-A5",
            "7F-FF-FE-60-4F-7D-59-4C-75-BA-4E-81",
        ]
        binary_payloads = [
            "00-00-00-00",
            "00-00-FF-FF-45-C3-B4-00-00-00-00",
            "00-00-1B-BB-00-02-27-3B-00-00-02-C5-00-00-00-00",
        ]

        for payload in text_payloads:
            with self.subTest(payload_hash=hashlib.sha256(payload.encode()).hexdigest()[:8]):
                self.assertTrue(redaction.looks_like_free_form_text(payload))
        for payload in binary_payloads:
            with self.subTest(payload_hash=hashlib.sha256(payload.encode()).hexdigest()[:8]):
                self.assertFalse(redaction.looks_like_free_form_text(payload))

    def test_1505_text_hybrid_is_redacted_while_binary_live_packet_is_preserved(self):
        framed_text = "FF-54-45-53-54-2D-49-44-2D-31-32-33-34-35-36-37-38-00"
        binary_live = "00-00-1B-BB-00-02-27-3B-00-00-02-C5-00-00-00-00"
        source = "\n".join(
            [
                RAW_HEADER,
                f"1;1001;1505;Hybrid;18;1;-;{framed_text}",
                f"2;1002;1505;Live;16;2;-;{binary_live}",
            ]
        )

        result = redaction.redact_raw_csv(source)
        rows = parse(result.public_text)

        self.assertEqual(1, result.exact_payload_rows)
        self.assertEqual("", rows[0]["hex"])
        self.assertEqual(binary_live, rows[1]["hex"])

    def test_redaction_is_idempotent(self):
        source = RAW_HEADER + "\n1;1001;1516;Serial;3;1;-;31-32-33\n"

        once = redaction.redact_raw_csv(source)
        twice = redaction.redact_raw_csv(once.public_text)

        self.assertEqual(1, once.modified_rows)
        self.assertEqual(0, twice.modified_rows)
        self.assertEqual(once.public_text, twice.public_text)

    def test_hash_only_deep_read_row_still_redacts_identity_metadata(self):
        payload_hash = hashlib.sha256(b"already removed").hexdigest()
        source = (
            DEEP_HEADER
            + "\n"
            + "1;scan-1;BLE_OBSERVATION;ADVERTISEMENT_OBSERVED;false;advertisement;"
            + "advertisement;ADV;;0;;4;;true;"
            + payload_hash
            + ";;7;;;BT638;BLE advertisement - device=PRIVATE-NAME\n"
        )

        result = redaction.redact_deep_read_csv(source)
        row = parse(result.public_text)[0]

        self.assertEqual(1, result.modified_rows)
        self.assertEqual(0, result.exact_payload_rows)
        self.assertEqual(payload_hash, row["payload_sha256"])
        self.assertEqual(redaction.PUBLIC_REDACTION, row["public_redaction"])
        self.assertEqual(redaction.PUBLIC_MEANING, row["meaning"])

    def test_tree_write_changes_only_affected_files_and_preserves_row_count(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            affected = root / "ride" / redaction.RAW_FILE_NAME
            unaffected = root / "other" / redaction.RAW_FILE_NAME
            affected.parent.mkdir()
            unaffected.parent.mkdir()
            affected_source = RAW_HEADER + "\n1;1001;1516;Serial;3;1;-;31-32-33\n"
            unaffected_source = RAW_HEADER + "\n1;1001;1509;Battery;4;1;-;00-00-00-00\n"
            affected.write_text(affected_source, encoding="utf-8")
            unaffected.write_text(unaffected_source, encoding="utf-8")

            dry_run = redaction.sanitize_tree(root)
            written = redaction.sanitize_tree(root, write=True)
            clean = redaction.sanitize_tree(root)

            self.assertEqual(2, dry_run.scanned_files)
            self.assertEqual(1, dry_run.affected_files)
            self.assertEqual(1, dry_run.modified_rows)
            self.assertEqual(1, written.exact_payload_rows)
            self.assertEqual(unaffected_source, unaffected.read_text(encoding="utf-8"))
            self.assertEqual(1, len(parse(affected.read_text(encoding="utf-8"))))
            self.assertEqual(0, clean.exact_payload_rows)

    def test_supported_legacy_and_current_schemas_all_redact(self):
        raw_headers = [
            RAW_HEADER,
            RAW_HEADER + ";origin;connection_epoch",
            RAW_HEADER
            + ";origin;connection_epoch;service_uuid;characteristic_uuid;properties_raw;"
            + "payload_sha256;public_redaction",
        ]
        raw_values = {
            "relative_ms": "1",
            "timestamp_ms": "1001",
            "channel": "1516",
            "meaning": "Serial",
            "length": "3",
            "packet_no": "1",
            "changed_bytes": "-",
            "hex": "31-32-33",
            "origin": "NOTIFICATION_DIAGNOSTIC",
            "connection_epoch": "7",
            "service_uuid": "1500",
            "characteristic_uuid": "1516",
            "properties_raw": "2",
            "payload_sha256": "",
            "public_redaction": "",
        }
        for header in raw_headers:
            with self.subTest(raw_columns=len(header.split(";"))):
                row = ";".join(raw_values[name] for name in header.split(";"))
                result = redaction.redact_raw_csv(header + "\n" + row + "\n")
                self.assertEqual(1, result.exact_payload_rows)
                self.assertEqual("", parse(result.public_text)[0]["hex"])

        deep_headers = [DEEP_V1_HEADER, DEEP_V2_HEADER, DEEP_HEADER]
        deep_values = {
            "timestamp_ms": "1001",
            "scan_id": "scan-a",
            "record_kind": "GATT_READ_CALLBACK",
            "outcome": "CALLBACK_SUCCESS",
            "callback_received": "true",
            "service_uuid": "1500",
            "characteristic_uuid": "1516",
            "short_id": "1516",
            "properties": "READ",
            "properties_raw": "2",
            "status": "0",
            "length": "3",
            "hex": "31-32-33",
            "payload_valid": "true",
            "payload_sha256": "",
            "public_redaction": "",
            "connection_epoch": "7",
            "measurement_connection_epoch": "0",
            "rssi": "",
            "evidence": "SDK",
            "meaning": "Serial",
        }
        for header in deep_headers:
            with self.subTest(deep_columns=len(header.split(";"))):
                row = ";".join(deep_values[name] for name in header.split(";"))
                result = redaction.redact_deep_read_csv(header + "\n" + row + "\n")
                self.assertEqual(1, result.exact_payload_rows)
                self.assertEqual("", parse(result.public_text)[0]["hex"])

    def test_bom_crlf_and_quoted_multiline_cell_are_handled(self):
        source = (
            "\ufeff"
            + RAW_HEADER
            + "\r\n"
            + '1;1001;1516;"Serial;\r\nCandidate";3;1;-;31-32-33\r\n'
        )

        result = redaction.redact_raw_csv(source)
        row = parse(result.public_text)[0]

        self.assertEqual(1, result.exact_payload_rows)
        self.assertEqual("Serial;\r\nCandidate", row["meaning"])
        self.assertEqual("", row["hex"])

    def test_malformed_file_prevents_all_writes(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            good = root / "a" / redaction.RAW_FILE_NAME
            bad = root / "b" / redaction.DEEP_READ_FILE_NAME
            good.parent.mkdir()
            bad.parent.mkdir()
            good_source = RAW_HEADER + "\n1;1001;1516;Serial;3;1;-;31-32-33\n"
            bad_source = "timestamp_ms;short_id;meaning\n1;1516;Serial\n"
            good.write_text(good_source, encoding="utf-8")
            bad.write_text(bad_source, encoding="utf-8")

            with self.assertRaises(redaction.TelemetryRedactionError):
                redaction.sanitize_tree(root, write=True)

            self.assertEqual(good_source, good.read_text(encoding="utf-8"))
            self.assertEqual(bad_source, bad.read_text(encoding="utf-8"))

    def test_ambiguous_or_overwide_schema_fails_closed(self):
        cases = {
            "duplicate": "channel;hex;hex\n1516;31-32-33;31-32-33\n",
            "overwide": "channel;hex\n1516;31-32-33;TRAILING\n",
            "missing_identity": "timestamp_ms;hex;meaning\n1;31-32-33;Serial\n",
        }

        for label, source in cases.items():
            with self.subTest(label=label):
                function = (
                    redaction.redact_deep_read_csv
                    if label == "missing_identity"
                    else redaction.redact_raw_csv
                )
                with self.assertRaises(redaction.TelemetryRedactionError):
                    function(source)

    def test_invalid_utf8_cli_error_does_not_echo_file_bytes(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            target = root / redaction.RAW_FILE_NAME
            target.write_bytes(b"channel;hex\n1516;\xffPRIVATE-CONTENT\n")
            output = io.StringIO()

            with contextlib.redirect_stdout(output):
                result = redaction.main(["--check", str(root)])

            self.assertEqual(2, result)
            self.assertIn("not valid UTF-8", output.getvalue())
            self.assertNotIn("PRIVATE-CONTENT", output.getvalue())
            self.assertNotIn("0xff", output.getvalue())

    def test_empty_root_is_not_reported_as_clean(self):
        with tempfile.TemporaryDirectory() as directory:
            output = io.StringIO()

            with contextlib.redirect_stdout(output):
                result = redaction.main(["--check", directory])

            self.assertEqual(2, result)
            self.assertIn("no supported telemetry CSV files", output.getvalue())

    def test_symbolic_link_roots_and_files_are_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory)
            target_root = base / "target"
            target_root.mkdir()
            target = target_root / redaction.RAW_FILE_NAME
            target.write_text(
                RAW_HEADER + "\n1;1001;1516;Serial;3;1;-;31-32-33\n",
                encoding="utf-8",
            )
            root_link = base / "root-link"
            root_link.symlink_to(target_root, target_is_directory=True)

            with self.assertRaises(redaction.TelemetryRedactionError):
                redaction.sanitize_tree(root_link)

            file_root = base / "file-root"
            file_root.mkdir()
            (file_root / redaction.RAW_FILE_NAME).symlink_to(target)
            with self.assertRaises(redaction.TelemetryRedactionError):
                redaction.sanitize_tree(file_root)

    def test_source_change_after_planning_is_not_overwritten(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            target = root / redaction.RAW_FILE_NAME
            target.write_text(
                RAW_HEADER + "\n1;1001;1516;Serial;3;1;-;31-32-33\n",
                encoding="utf-8",
            )
            plans = redaction.build_redaction_plans(root)
            concurrent = RAW_HEADER + "\n1;1001;1509;Battery;4;1;-;00-00-00-00\n"
            target.write_text(concurrent, encoding="utf-8")

            with self.assertRaises(redaction.TelemetryRedactionError):
                redaction.apply_redaction_plans(plans)

            self.assertEqual(concurrent, target.read_text(encoding="utf-8"))


if __name__ == "__main__":
    unittest.main()

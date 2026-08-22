#!/usr/bin/env python3
"""Redact identity and free-form payloads from public telemetry CSV files.

The Android upload boundary keeps exact bytes on the phone and replaces public
identity/free-form payloads with a stable SHA-256 digest.  This maintenance tool
applies the same contract to legacy files that already reached ``telemetry-data``.

The default mode is read-only.  ``--check`` exits non-zero while public privacy
changes remain; ``--write`` validates the complete batch and then replaces each
affected file atomically.  A multi-file run is not a filesystem transaction, so
the documented recovery ref and private backup remain mandatory.  Neither mode
prints payload values.
"""

from __future__ import annotations

import argparse
import csv
import hashlib
import os
import stat
import tempfile
import unicodedata
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable, Sequence


RAW_FILE_NAME = "BLE_Rohdaten.csv"
DEEP_READ_FILE_NAME = "Gatt_READ_Diagnose.csv"
SENSITIVE_SHORT_IDS = {"1511", "1513", "1516", "1517", "1518", "2A00", "2A25"}
SENSITIVE_MEANING_TERMS = (
    "serial",
    "debug",
    "errorstring",
    "device name",
    "identity",
    "identität",
    "advertisement",
)
PUBLIC_REDACTION = "identity_or_free_form"
PUBLIC_MEANING = "REDACTED_IDENTITY_OR_FREE_FORM"


class TelemetryRedactionError(ValueError):
    """Raised when a CSV cannot be transformed without guessing its schema."""


@dataclass(frozen=True)
class RedactionPlan:
    path: Path
    file_kind: str
    source_text: str
    source_sha256: str
    public_text: str
    modified_rows: int
    exact_payload_rows: int

    @property
    def changed(self) -> bool:
        return self.source_text != self.public_text


@dataclass(frozen=True)
class RedactionSummary:
    scanned_files: int
    affected_files: int
    modified_rows: int
    exact_payload_rows: int
    plans: tuple[RedactionPlan, ...]


@dataclass(frozen=True)
class CsvRedaction:
    public_text: str
    modified_rows: int
    exact_payload_rows: int


def short_uuid(value: str) -> str:
    """Mirror DiagnosticReadEvidence.diagnosticShortUuid()."""
    normalized = value.strip().upper()
    if len(normalized) == 4:
        return normalized
    if len(normalized) == 8 and all(char in "0123456789ABCDEF" for char in normalized):
        return normalized[-4:]
    if len(normalized) >= 8:
        return normalized[4:8]
    return normalized


def _hex_bytes_for_text_detection(value: str) -> bytes:
    """Parse valid byte tokens while matching the Kotlin text detector."""
    parsed: list[int] = []
    for part in value.replace(":", "-").split("-"):
        if not part.strip() or len(part) != 2:
            continue
        try:
            parsed.append(int(part, 16))
        except ValueError:
            continue
    return bytes(parsed)


def payload_sha256(value: str) -> str:
    """Hash exact bytes when value is canonical hex, otherwise its UTF-8 text."""
    parts = [part for part in value.replace(":", "-").split("-") if part.strip()]
    try:
        payload = bytes(int(part, 16) for part in parts) if (
            parts and all(len(part) == 2 for part in parts)
        ) else value.encode("utf-8")
    except ValueError:
        payload = value.encode("utf-8")
    return hashlib.sha256(payload).hexdigest()


def _looks_like_human_text(value: str, minimum_code_points: int = 1) -> bool:
    clean = value.strip("\x00\ufeff \t\r\n")
    if len(clean) < minimum_code_points:
        return False
    readable = sum(
        char.isalnum()
        or unicodedata.category(char) in {"Zs", "Zl", "Zp"}
        or char in "-_.:/@"
        for char in clean
    )
    return (
        any(char.isalnum() for char in clean)
        and readable >= minimum_code_points
        and readable / len(clean) >= 0.80
    )


def _decode_strict(payload: bytes, encoding: str) -> str | None:
    try:
        return payload.decode(encoding, errors="strict")
    except UnicodeDecodeError:
        return None


def _has_utf16_bom(payload: bytes) -> bool:
    return len(payload) >= 2 and payload[:2] in (b"\xff\xfe", b"\xfe\xff")


def _has_strong_utf16_shape(payload: bytes, minimum_bytes: int = 8) -> bool:
    if len(payload) < minimum_bytes or len(payload) % 2:
        return False
    code_units = len(payload) // 2
    even_nulls = sum(payload[index] == 0 for index in range(0, len(payload), 2))
    odd_nulls = sum(payload[index] == 0 for index in range(1, len(payload), 2))
    dominant = max(even_nulls, odd_nulls)
    other = min(even_nulls, odd_nulls)
    return dominant * 4 >= code_units * 3 and other * 4 <= code_units


def _looks_like_encoded_human_text(payload: bytes, minimum_code_points: int = 1) -> bool:
    utf8 = _decode_strict(payload, "utf-8")
    if utf8 is not None and _looks_like_human_text(utf8, minimum_code_points):
        return True
    if len(payload) >= 4 and len(payload) % 2 == 0:
        if _has_strong_utf16_shape(payload, minimum_bytes=4) or _has_utf16_bom(payload):
            for encoding in ("utf-16-le", "utf-16-be"):
                decoded = _decode_strict(payload, encoding)
                if decoded is not None and _looks_like_human_text(decoded, minimum_code_points):
                    return True
    return False


def _is_protocol_framing_byte(value: int) -> bool:
    return value < 0x20 or value >= 0x7F


def _looks_like_protocol_framed_human_text(payload: bytes) -> bool:
    if len(payload) < 7:
        return False
    maximum_edge_bytes = min(4, len(payload) - 1)
    for trimmed_start in range(maximum_edge_bytes + 1):
        for trimmed_end in range(maximum_edge_bytes + 1):
            total_trimmed = trimmed_start + trimmed_end
            if total_trimmed == 0 or total_trimmed >= len(payload) or total_trimmed > 4:
                continue
            prefix = payload[:trimmed_start]
            suffix = payload[len(payload) - trimmed_end :] if trimmed_end else b""
            if not all(_is_protocol_framing_byte(value) for value in prefix):
                continue
            if not all(_is_protocol_framing_byte(value) for value in suffix):
                continue
            end_exclusive = len(payload) - trimmed_end if trimmed_end else len(payload)
            candidate = payload[trimmed_start:end_exclusive]
            utf8 = _decode_strict(candidate, "utf-8")
            if utf8 is not None and _looks_like_human_text(utf8, minimum_code_points=6):
                return True
            if _has_strong_utf16_shape(candidate) or _has_utf16_bom(candidate):
                for encoding in ("utf-16-le", "utf-16-be"):
                    decoded = _decode_strict(candidate, encoding)
                    if decoded is not None and _looks_like_human_text(
                        decoded, minimum_code_points=4
                    ):
                        return True
    return False


def looks_like_free_form_text(value: str) -> bool:
    payload = _hex_bytes_for_text_detection(value)
    if not payload:
        return False
    return _looks_like_encoded_human_text(payload) or _looks_like_protocol_framed_human_text(
        payload
    )


def _parse_csv(text: str, path: Path) -> list[list[str]]:
    try:
        rows = [
            row
            for row in csv.reader(
                text.splitlines(keepends=True), delimiter=";", strict=True
            )
        ]
    except csv.Error as error:
        raise TelemetryRedactionError(f"{path}: CSV parse failed: {error}") from error
    rows = [row for row in rows if any(cell for cell in row)]
    if not rows and text.strip():
        raise TelemetryRedactionError(f"{path}: CSV could not be parsed safely")
    if rows and rows[0]:
        rows[0][0] = rows[0][0].removeprefix("\ufeff")
        normalized_header = [cell.strip().lower() for cell in rows[0]]
        if len(normalized_header) != len(set(normalized_header)):
            raise TelemetryRedactionError(f"{path}: ambiguous duplicate CSV columns")
        for row_number, row in enumerate(rows[1:], start=2):
            if len(row) > len(rows[0]):
                raise TelemetryRedactionError(
                    f"{path}: row {row_number} has more cells than the CSV header"
                )
    return rows


def _render_csv(rows: Sequence[Sequence[str]]) -> str:
    from io import StringIO

    target = StringIO(newline="")
    writer = csv.writer(target, delimiter=";", lineterminator="\n", quoting=csv.QUOTE_MINIMAL)
    writer.writerows(rows)
    return target.getvalue()


def _ensure_column(header: list[str], name: str) -> int:
    try:
        return header.index(name)
    except ValueError:
        header.append(name)
        return len(header) - 1


def _required_column(header: Sequence[str], name: str, path: Path) -> int:
    try:
        return header.index(name)
    except ValueError as error:
        raise TelemetryRedactionError(f"{path}: missing required {name!r} column") from error


def _cell(row: Sequence[str], index: int | None) -> str:
    if index is None or index < 0 or index >= len(row):
        return ""
    return row[index]


def _pad(row: list[str], width: int) -> None:
    if len(row) < width:
        row.extend("" for _ in range(width - len(row)))


def _deep_read_row_is_sensitive(row: Sequence[str], indexes: dict[str, int | None]) -> bool:
    short_id = _cell(row, indexes["short_id"])
    characteristic = _cell(row, indexes["characteristic_uuid"])
    canonical = short_uuid(short_id if short_id.strip() else characteristic)
    meaning = _cell(row, indexes["meaning"]).lower()
    record_kind = _cell(row, indexes["record_kind"])
    exact_hex = _cell(row, indexes["hex"])
    return (
        canonical in SENSITIVE_SHORT_IDS
        or record_kind.lower() == "ble_observation"
        or any(term in meaning for term in SENSITIVE_MEANING_TERMS)
        or looks_like_free_form_text(exact_hex)
    )


def redact_deep_read_csv(
    text: str, path: Path = Path(DEEP_READ_FILE_NAME)
) -> CsvRedaction:
    rows = _parse_csv(text, path)
    if not rows:
        return CsvRedaction(text, 0, 0)
    header = list(rows[0])
    indexes: dict[str, int | None] = {
        "hex": _required_column(header, "hex", path),
        "short_id": header.index("short_id") if "short_id" in header else None,
        "characteristic_uuid": (
            header.index("characteristic_uuid") if "characteristic_uuid" in header else None
        ),
        "meaning": header.index("meaning") if "meaning" in header else None,
        "record_kind": header.index("record_kind") if "record_kind" in header else None,
    }
    if indexes["short_id"] is None and indexes["characteristic_uuid"] is None:
        raise TelemetryRedactionError(
            f"{path}: missing required characteristic identifier column"
        )
    existing_redaction_index = (
        header.index("public_redaction") if "public_redaction" in header else None
    )
    sensitive_rows = [
        _deep_read_row_is_sensitive(source, indexes) for source in rows[1:]
    ]
    needs_redaction = [
        sensitive
        and (
            bool(_cell(source, indexes["hex"]))
            or _cell(source, existing_redaction_index) != PUBLIC_REDACTION
            or (
                indexes["meaning"] is not None
                and _cell(source, indexes["meaning"]) != PUBLIC_MEANING
            )
        )
        for source, sensitive in zip(rows[1:], sensitive_rows)
    ]
    if not any(needs_redaction):
        return CsvRedaction(text, 0, 0)

    hash_index = _ensure_column(header, "payload_sha256")
    redaction_index = _ensure_column(header, "public_redaction")
    hex_index = indexes["hex"]
    if hex_index is None:  # Kept explicit for static checkers; the column is required above.
        raise TelemetryRedactionError(f"{path}: missing required 'hex' column")
    output: list[list[str]] = [header]
    modified_rows = 0
    exact_payload_rows = 0
    for source, sensitive, needs_change in zip(rows[1:], sensitive_rows, needs_redaction):
        row = list(source)
        _pad(row, len(header))
        exact_hex = _cell(row, hex_index)
        if sensitive:
            if exact_hex:
                row[hash_index] = payload_sha256(exact_hex)
                exact_payload_rows += 1
            row[hex_index] = ""
            meaning_index = indexes["meaning"]
            if meaning_index is not None:
                row[meaning_index] = PUBLIC_MEANING
            row[redaction_index] = PUBLIC_REDACTION
            if needs_change:
                modified_rows += 1
        output.append(row)
    return CsvRedaction(_render_csv(output), modified_rows, exact_payload_rows)


def redact_raw_csv(text: str, path: Path = Path(RAW_FILE_NAME)) -> CsvRedaction:
    rows = _parse_csv(text, path)
    if not rows:
        return CsvRedaction(text, 0, 0)
    header = list(rows[0])
    channel_index = _required_column(header, "channel", path)
    hex_index = _required_column(header, "hex", path)
    existing_redaction_index = (
        header.index("public_redaction") if "public_redaction" in header else None
    )
    sensitive_rows = [
        short_uuid(_cell(source, channel_index)) in SENSITIVE_SHORT_IDS
        or looks_like_free_form_text(_cell(source, hex_index))
        for source in rows[1:]
    ]
    needs_redaction = [
        sensitive
        and (
            bool(_cell(source, hex_index))
            or _cell(source, existing_redaction_index) != PUBLIC_REDACTION
        )
        for source, sensitive in zip(rows[1:], sensitive_rows)
    ]
    if not any(needs_redaction):
        return CsvRedaction(text, 0, 0)

    hash_index = _ensure_column(header, "payload_sha256")
    redaction_index = _ensure_column(header, "public_redaction")
    output: list[list[str]] = [header]
    modified_rows = 0
    exact_payload_rows = 0
    for source, sensitive, needs_change in zip(rows[1:], sensitive_rows, needs_redaction):
        row = list(source)
        _pad(row, len(header))
        exact_hex = _cell(row, hex_index)
        if sensitive:
            if exact_hex:
                row[hash_index] = payload_sha256(exact_hex)
                exact_payload_rows += 1
            row[hex_index] = ""
            row[redaction_index] = PUBLIC_REDACTION
            if needs_change:
                modified_rows += 1
        output.append(row)
    return CsvRedaction(_render_csv(output), modified_rows, exact_payload_rows)


def telemetry_csv_paths(root: Path) -> list[Path]:
    if not root.exists():
        raise TelemetryRedactionError(f"{root}: root does not exist")
    if root.is_symlink():
        raise TelemetryRedactionError(f"{root}: symbolic-link roots are not allowed")
    if root.is_file():
        if root.name not in {RAW_FILE_NAME, DEEP_READ_FILE_NAME}:
            raise TelemetryRedactionError(f"{root}: unsupported file name")
        return [root]
    paths = sorted(
        path
        for path in root.rglob("*.csv")
        if path.is_file() and path.name in {RAW_FILE_NAME, DEEP_READ_FILE_NAME}
    )
    symbolic = [path for path in paths if path.is_symlink()]
    if symbolic:
        raise TelemetryRedactionError(
            f"{root}: symbolic-link telemetry files are not allowed"
        )
    return paths


def build_redaction_plans(
    root: Path, paths: Sequence[Path] | None = None
) -> tuple[RedactionPlan, ...]:
    plans: list[RedactionPlan] = []
    selected_paths = list(paths) if paths is not None else telemetry_csv_paths(root)
    if not selected_paths:
        raise TelemetryRedactionError(f"{root}: no supported telemetry CSV files found")
    for path in selected_paths:
        try:
            source_bytes = path.read_bytes()
            source = source_bytes.decode("utf-8-sig")
        except UnicodeError as error:
            raise TelemetryRedactionError(f"{path}: file is not valid UTF-8") from error
        if path.name == RAW_FILE_NAME:
            result = redact_raw_csv(source, path)
            kind = "raw"
        else:
            result = redact_deep_read_csv(source, path)
            kind = "deep_read"
        # Do not normalize legacy files that require no privacy change.
        if result.modified_rows:
            plans.append(
                RedactionPlan(
                    path,
                    kind,
                    source,
                    hashlib.sha256(source_bytes).hexdigest(),
                    result.public_text,
                    result.modified_rows,
                    result.exact_payload_rows,
                )
            )
    return tuple(plans)


def _atomic_write_text(path: Path, text: str) -> None:
    if path.is_symlink():
        raise TelemetryRedactionError(f"{path}: symbolic-link telemetry files are not allowed")
    mode = stat.S_IMODE(path.stat().st_mode)
    descriptor, temporary_name = tempfile.mkstemp(prefix=f".{path.name}.", dir=path.parent)
    temporary = Path(temporary_name)
    try:
        with os.fdopen(descriptor, "w", encoding="utf-8", newline="") as handle:
            handle.write(text)
            handle.flush()
            os.fsync(handle.fileno())
        os.chmod(temporary, mode)
        os.replace(temporary, path)
    finally:
        if temporary.exists():
            temporary.unlink()


def apply_redaction_plans(plans: Sequence[RedactionPlan]) -> None:
    for plan in plans:
        if plan.path.is_symlink():
            raise TelemetryRedactionError(
                f"{plan.path}: symbolic-link telemetry files are not allowed"
            )
        try:
            current_sha256 = hashlib.sha256(plan.path.read_bytes()).hexdigest()
        except OSError as error:
            raise TelemetryRedactionError(
                f"{plan.path}: source could not be revalidated before write"
            ) from error
        if current_sha256 != plan.source_sha256:
            raise TelemetryRedactionError(
                f"{plan.path}: source changed after validation; refusing to overwrite"
            )
        _atomic_write_text(plan.path, plan.public_text)


def sanitize_tree(root: Path, *, write: bool = False) -> RedactionSummary:
    paths = telemetry_csv_paths(root)
    if not paths:
        raise TelemetryRedactionError(f"{root}: no supported telemetry CSV files found")
    # Build every plan before the first write so one malformed file cannot cause
    # a partially sanitized branch.
    plans = build_redaction_plans(root, paths)
    if write:
        if telemetry_csv_paths(root) != paths:
            raise TelemetryRedactionError(
                f"{root}: telemetry file set changed after validation; refusing to write"
            )
        apply_redaction_plans(plans)
    return RedactionSummary(
        scanned_files=len(paths),
        affected_files=len(plans),
        modified_rows=sum(plan.modified_rows for plan in plans),
        exact_payload_rows=sum(plan.exact_payload_rows for plan in plans),
        plans=plans,
    )


def _display_path(path: Path, root: Path) -> str:
    if root.is_file():
        return path.name
    try:
        return path.relative_to(root).as_posix()
    except ValueError:
        return path.as_posix()


def _print_summary(summary: RedactionSummary, root: Path, *, wrote: bool) -> None:
    mode = "write" if wrote else "scan"
    print(
        f"mode={mode} scanned_files={summary.scanned_files} "
        f"affected_files={summary.affected_files} modified_rows={summary.modified_rows} "
        f"exact_payload_rows={summary.exact_payload_rows}"
    )
    for plan in summary.plans:
        print(
            f"{plan.file_kind}\tmodified={plan.modified_rows}\t"
            f"exact={plan.exact_payload_rows}\t{_display_path(plan.path, root)}"
        )


def build_argument_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "root",
        nargs="?",
        type=Path,
        default=Path("."),
        help="Repository root, data directory, or one supported CSV file",
    )
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument(
        "--check",
        action="store_true",
        help="read-only check; exit 1 while public privacy changes remain",
    )
    mode.add_argument(
        "--write",
        action="store_true",
        help="atomically redact the current files in place",
    )
    return parser


def main(argv: Iterable[str] | None = None) -> int:
    args = build_argument_parser().parse_args(argv)
    try:
        summary = sanitize_tree(args.root, write=args.write)
    except (OSError, TelemetryRedactionError) as error:
        print(f"redaction_error={error}")
        return 2
    _print_summary(summary, args.root, wrote=args.write)
    if args.check and summary.modified_rows:
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

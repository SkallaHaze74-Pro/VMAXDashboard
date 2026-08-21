#!/usr/bin/env python3
"""Aggregate quarantined BT638 GATT/advertisement diagnostics.

Only explicit GATT callbacks count as responses. Local transport/timeout events and
BLE observations remain visible but cannot become live telemetry or semantic proof.
V1 short UUIDs and V2/V3 full UUIDs are canonicalized for longitudinal comparison.
"""

from __future__ import annotations

import argparse
import csv
import hashlib
import json
from collections import Counter, defaultdict
from collections.abc import Iterable
from pathlib import Path
from typing import Any

KNOWN_READ_TARGETS = {
    "1514": "Error",
    "1516": "SerialNumbers",
    "1517": "ErrorString",
    "1518": "DebugLog",
    "150C": "BatteryCellUpdate candidate",
    "1502": "Battery/static candidate",
    "1509": "Battery live layout",
    "150A": "Motor live layout",
    "160C": "Motor tuning readback",
    "1E03": "WirelessRemote",
    "1E04": "WirelessRemoteAction",
}
SENTINEL_BYTES = {0x00, 0x80, 0xFF}
SENSITIVE_IDENTITY_TARGETS = {"1516", "1517", "1518", "2A00", "2A25"}
GATT_RECORD_KINDS = {"GATT_READ_CALLBACK", "GATT_READ_EVENT"}
SENSITIVE_MEANING_TERMS = ("serial", "debug", "errorstring", "device name", "identity", "identität")


def parse_hex(text: str) -> bytes:
    parts = [part.strip() for part in text.replace(":", "-").split("-") if part.strip()]
    if not parts:
        return b""
    try:
        if not all(len(part) == 2 for part in parts):
            return b""
        return bytes(int(part, 16) for part in parts)
    except ValueError:
        return b""


def variable_indexes(payloads: list[bytes]) -> list[int]:
    if len(payloads) < 2:
        return []
    width = max(map(len, payloads))
    changed: list[int] = []
    for index in range(width):
        values = {payload[index] if index < len(payload) else None for payload in payloads}
        if len(values) > 1:
            changed.append(index)
    return changed


def looks_like_free_form_text(payload: bytes) -> bool:
    if len(payload) < 4:
        return False
    printable = sum(byte in (0x09, 0x0A, 0x0D) or 0x20 <= byte <= 0x7E for byte in payload)
    return printable / len(payload) >= 0.85


def short_uuid(value: str) -> str:
    normalized = value.strip().upper()
    if len(normalized) == 4:
        return normalized
    if len(normalized) == 8 and all(char in "0123456789ABCDEF" for char in normalized):
        return normalized[-4:]
    return normalized[4:8] if len(normalized) >= 8 else normalized


def normalized_roots(root: Path | Iterable[Path]) -> list[Path]:
    return [root] if isinstance(root, Path) else list(root)


def _bool_cell(value: object) -> bool:
    return str(value or "").strip().lower() in {"1", "true", "yes"}


def _status_number(row: dict[str, str]) -> int | None:
    try:
        return int(str(row.get("status") or "").strip())
    except ValueError:
        return None


def _record_kind(row: dict[str, str]) -> str:
    explicit = str(row.get("record_kind") or "").strip().upper()
    if explicit:
        return explicit
    # V1/V2 compatibility: non-negative status values came from callbacks;
    # negative synthetic values represented local attempt events.
    status = _status_number(row)
    return "GATT_READ_CALLBACK" if status is not None and status >= 0 else "GATT_READ_EVENT"


def _callback_received(row: dict[str, str]) -> bool:
    explicit = str(row.get("callback_received") or "").strip()
    if explicit:
        return _bool_cell(explicit)
    status = _status_number(row)
    return status is not None and status >= 0


def _payload_valid(row: dict[str, str]) -> bool:
    explicit = str(row.get("payload_valid") or "").strip()
    if explicit:
        return _bool_cell(explicit)
    return _callback_received(row) and _status_number(row) == 0


def _is_gatt_attempt(row: dict[str, str]) -> bool:
    return _record_kind(row) in GATT_RECORD_KINDS


def _canonical_identity(row: dict[str, str]) -> tuple[str, str]:
    service_uuid = str(row.get("service_uuid") or "").strip()
    characteristic_uuid = str(row.get("characteristic_uuid") or row.get("short_id") or "").strip()
    service = short_uuid(service_uuid)
    characteristic = short_uuid(str(row.get("short_id") or characteristic_uuid))
    return service, characteristic


def _record_signature(row: dict[str, str]) -> tuple[str, ...]:
    """Identity shared by standalone and ride-linked copies of one event."""
    service, characteristic = _canonical_identity(row)
    return (
        str(row.get("scan_id") or "").strip(),
        str(row.get("timestamp_ms") or "").strip(),
        _record_kind(row),
        str(row.get("outcome") or "").strip().upper(),
        str(_callback_received(row)).lower(),
        service,
        characteristic,
        str(row.get("properties") or "").strip(),
        str(row.get("properties_raw") or "").strip(),
        str(row.get("status") or "").strip(),
        str(row.get("length") or "").strip(),
        str(row.get("hex") or "").strip().upper(),
        str(row.get("payload_sha256") or "").strip().lower(),
        str(row.get("payload_valid") or "").strip().lower(),
        str(row.get("connection_epoch") or "").strip(),
        str(row.get("rssi") or "").strip(),
        str(row.get("evidence") or "").strip(),
        str(row.get("meaning") or "").strip(),
    )


def _legacy_bundle_fingerprint(rows: list[dict[str, str]]) -> str:
    key = json.dumps(
        sorted(_record_signature(row) for row in rows),
        ensure_ascii=False,
        separators=(",", ":"),
    )
    return "legacy-" + hashlib.sha256(key.encode("utf-8")).hexdigest()


def load_rows(root: Path | Iterable[Path]) -> tuple[list[dict[str, str]], set[str], int]:
    rows_by_signature: dict[tuple[str, ...], dict[str, str]] = {}
    logical_bundles: set[str] = set()
    source_bundle_copies = 0
    seen_paths: set[Path] = set()
    for search_root in normalized_roots(root):
        paths = sorted(search_root.rglob("Gatt_READ_Diagnose.csv")) if search_root.is_dir() else []
        for path in paths:
            resolved = path.resolve()
            if resolved in seen_paths:
                continue
            seen_paths.add(resolved)
            try:
                bundle_rows: list[dict[str, str]] = []
                with path.open("r", encoding="utf-8-sig", newline="") as handle:
                    for row in csv.DictReader(handle, delimiter=";"):
                        if isinstance(row, dict):
                            row["__source"] = path.parent.as_posix()
                            bundle_rows.append(row)
            except OSError:
                continue
            if not bundle_rows:
                continue

            scan_ids = {
                str(row.get("scan_id") or "").strip()
                for row in bundle_rows
                if str(row.get("scan_id") or "").strip()
            }
            if scan_ids:
                logical_bundles.update(scan_ids)
                source_bundle_copies += len(scan_ids)
            else:
                legacy_id = _legacy_bundle_fingerprint(bundle_rows)
                logical_bundles.add(legacy_id)
                source_bundle_copies += 1

            for row in bundle_rows:
                signature = _record_signature(row)
                existing = rows_by_signature.get(signature)
                if existing is None:
                    rows_by_signature[signature] = row
                    continue
                # Prefer the ride-linked copy because it contains the measurement
                # epoch; never count the same callback/event twice.
                if not str(existing.get("measurement_connection_epoch") or "").strip():
                    linked_epoch = str(row.get("measurement_connection_epoch") or "").strip()
                    if linked_epoch:
                        existing["measurement_connection_epoch"] = linked_epoch
    return list(rows_by_signature.values()), logical_bundles, source_bundle_copies


def _preferred_uuid(forms: set[str], short: str) -> str:
    full = sorted(value for value in forms if len(value) > 8)
    return full[0] if full else (sorted(forms)[0] if forms else short.lower())


def build_report(root: Path | Iterable[Path]) -> dict[str, Any]:
    rows, bundles, source_bundle_count = load_rows(root)
    gatt_rows = [row for row in rows if _is_gatt_attempt(row)]
    callback_rows = [row for row in gatt_rows if _callback_received(row)]
    observation_rows = [row for row in rows if not _is_gatt_attempt(row)]

    grouped: dict[tuple[str, str], list[dict[str, str]]] = defaultdict(list)
    for row in gatt_rows:
        service, characteristic = _canonical_identity(row)
        if characteristic:
            grouped[(service, characteristic)].append(row)

    fields: list[dict[str, Any]] = []
    for (service, characteristic), items in sorted(grouped.items()):
        statuses = Counter(str(item.get("status") or "") for item in items)
        callbacks = [item for item in items if _callback_received(item)]
        successful_callbacks = [item for item in callbacks if _status_number(item) == 0]
        valid_callbacks = [item for item in callbacks if _payload_valid(item)]
        payload_callbacks = [
            item for item in callbacks
            if str(item.get("hex") or item.get("payload_sha256") or "").strip()
        ]
        valid_payload_callbacks = [
            item for item in valid_callbacks
            if str(item.get("hex") or item.get("payload_sha256") or "").strip()
        ]
        payload_texts = [str(item.get("hex") or "").strip().upper() for item in valid_payload_callbacks]
        all_payload_texts = [str(item.get("hex") or "").strip().upper() for item in payload_callbacks]
        non_empty_texts = [text for text in payload_texts if text]
        public_hashes = [
            str(item.get("payload_sha256") or "").strip().lower()
            for item in valid_payload_callbacks
            if str(item.get("payload_sha256") or "").strip()
        ]
        invalid_payload_hashes = []
        for item in payload_callbacks:
            if _payload_valid(item):
                continue
            stored_hash = str(item.get("payload_sha256") or "").strip().lower()
            payload = parse_hex(str(item.get("hex") or ""))
            if stored_hash:
                invalid_payload_hashes.append(stored_hash)
            elif payload:
                invalid_payload_hashes.append(hashlib.sha256(payload).hexdigest())
        invalid_payload_hashes = sorted(set(invalid_payload_hashes))
        unique_texts = sorted(set(non_empty_texts))
        unique_hashes = sorted(set(public_hashes))
        payload_identities = set(unique_texts) | {f"sha256:{value}" for value in unique_hashes}
        payload_bytes = [parse_hex(text) for text in unique_texts]
        payload_bytes = [payload for payload in payload_bytes if payload]
        lengths = Counter(len(parse_hex(text)) for text in all_payload_texts if parse_hex(text))
        valid_lengths = Counter(len(parse_hex(text)) for text in non_empty_texts if parse_hex(text))
        for item in payload_callbacks:
            if str(item.get("hex") or "").strip():
                continue
            stored_length = str(item.get("length") or "").strip()
            if stored_length.isdigit() and int(stored_length) > 0:
                lengths[int(stored_length)] += 1
                if _payload_valid(item):
                    valid_lengths[int(stored_length)] += 1
        sentinel_like = bool(payload_bytes) and all(
            all(byte in SENTINEL_BYTES for byte in payload) for payload in payload_bytes
        )
        evidence_values = sorted({str(item.get("evidence") or "").strip() for item in items if str(item.get("evidence") or "").strip()})
        raw_meanings = sorted({str(item.get("meaning") or "").strip() for item in items if str(item.get("meaning") or "").strip()})
        properties = sorted({str(item.get("properties") or "").strip() for item in items if str(item.get("properties") or "").strip()})
        properties_raw = sorted({str(item.get("properties_raw") or "").strip() for item in items if str(item.get("properties_raw") or "").strip()})
        connection_epochs = sorted({str(item.get("connection_epoch") or "").strip() for item in items if str(item.get("connection_epoch") or "").strip()})
        measurement_connection_epochs = sorted({
            str(item.get("measurement_connection_epoch") or "").strip()
            for item in items
            if str(item.get("measurement_connection_epoch") or "").strip()
        })
        service_forms = {str(item.get("service_uuid") or "").strip().lower() for item in items if str(item.get("service_uuid") or "").strip()}
        characteristic_forms = {
            str(item.get("characteristic_uuid") or "").strip().lower()
            for item in items
            if str(item.get("characteristic_uuid") or "").strip()
        }
        sensitive_identity = characteristic in SENSITIVE_IDENTITY_TARGETS or any(
            str(item.get("public_redaction") or "").strip() for item in items
        ) or any(looks_like_free_form_text(payload) for payload in payload_bytes) or any(
            any(term in meaning.lower() for term in SENSITIVE_MEANING_TERMS)
            for meaning in raw_meanings
        )
        meanings = ["REDACTED_IDENTITY_OR_FREE_FORM"] if sensitive_identity and raw_meanings else raw_meanings
        computed_hashes = [hashlib.sha256(payload).hexdigest() for payload in payload_bytes[:8]]
        sample_hashes = sorted(set(unique_hashes + computed_hashes))[:8]
        fields.append(
            {
                "service": service,
                "characteristic": characteristic,
                "serviceUuid": _preferred_uuid(service_forms, service),
                "characteristicUuid": _preferred_uuid(characteristic_forms, characteristic),
                "serviceUuidForms": sorted(service_forms),
                "characteristicUuidForms": sorted(characteristic_forms),
                "knownTarget": KNOWN_READ_TARGETS.get(characteristic),
                "attempts": len(items),
                "callbacks": len(callbacks),
                "successes": len(successful_callbacks),
                "validCallbacks": len(valid_callbacks),
                "failures": len(items) - len(successful_callbacks),
                "nonCallbackEvents": len(items) - len(callbacks),
                "statusCounts": dict(sorted(statuses.items())),
                "outcomeCounts": dict(sorted(Counter(
                    str(item.get("outcome") or "LEGACY_UNKNOWN").strip().upper()
                    for item in items
                ).items())),
                "payloadCount": len(payload_callbacks),
                "validPayloadCount": len(valid_payload_callbacks),
                "invalidPayloadCount": len(payload_callbacks) - len(valid_payload_callbacks),
                "uniquePayloadCount": len(payload_identities),
                "lengthCounts": {str(k): v for k, v in sorted(lengths.items())},
                "validLengthCounts": {str(k): v for k, v in sorted(valid_lengths.items())},
                "dynamicAcrossScans": len(payload_identities) > 1,
                "variableByteIndexes": [] if sensitive_identity else variable_indexes(payload_bytes),
                "sentinelLikeOnly": sentinel_like if not sensitive_identity else False,
                "samplePayloads": [] if sensitive_identity else unique_texts[:8],
                "samplePayloadHashes": sample_hashes,
                "invalidPayloadHashes": invalid_payload_hashes[:8],
                "sensitivePayloadRedacted": sensitive_identity,
                "evidenceLabels": evidence_values,
                "meanings": meanings,
                "properties": properties,
                "propertiesRaw": properties_raw,
                "connectionEpochs": connection_epochs,
                "measurementConnectionEpochs": measurement_connection_epochs,
                "independentSemanticConfirmation": False,
            }
        )

    read_successes = sum(1 for row in callback_rows if _status_number(row) == 0)
    payload_callbacks = sum(
        1 for row in callback_rows
        if str(row.get("hex") or row.get("payload_sha256") or "").strip()
    )
    valid_payload_callbacks = sum(
        1 for row in callback_rows
        if _payload_valid(row) and str(row.get("hex") or row.get("payload_sha256") or "").strip()
    )
    return {
        "schema": "vmax-bt638-gatt-read-aggregate-v2",
        "readOnly": True,
        "diagnosticBundles": len(bundles),
        "sourceBundleCopies": source_bundle_count,
        "duplicateBundleCopies": max(0, source_bundle_count - len(bundles)),
        "records": len(rows),
        "readAttempts": len(gatt_rows),
        "readCallbacks": len(callback_rows),
        "readSuccesses": read_successes,
        "payloadCallbacks": payload_callbacks,
        "validPayloadCallbacks": valid_payload_callbacks,
        "observations": len(observation_rows),
        "characteristics": len(fields),
        "successfulCharacteristics": sum(1 for field in fields if field["successes"] > 0),
        "payloadCharacteristics": sum(1 for field in fields if field["payloadCount"] > 0),
        "dynamicCharacteristics": sum(1 for field in fields if field["dynamicAcrossScans"]),
        "fields": fields,
        "evidencePolicy": (
            "Only an explicit GATT callback is a READ response. A successful callback proves only that this "
            "BT638/GATT characteristic answered with the recorded bytes. Local timeout/disconnect events, BLE "
            "observations and model agreement prove no byte semantics, BMS origin, authentication or write capability."
        ),
    }


def render_markdown(payload: dict[str, Any]) -> str:
    lines = [
        "# BT638 GATT Deep-READ-Abgleich",
        "",
        f"Logische Scans: **{payload['diagnosticBundles']}** • Quellkopien: **{payload['sourceBundleCopies']}** • "
        f"READ-Versuche: **{payload['readAttempts']}** • echte Callbacks: **{payload['readCallbacks']}** • "
        f"Callback-Payloads: **{payload['payloadCallbacks']}** (valide: **{payload['validPayloadCallbacks']}**) • "
        f"Beobachtungen: **{payload['observations']}**",
        "",
        "> STRICT READ-ONLY. Nur ein echter GATT-Callback zählt als Antwort; auch er beweist noch keine Byte-Semantik. READ-Daten und BLE-Beobachtungen bleiben von Live-Telemetrie und Decoder-Lernen getrennt.",
        "",
        "| Service | Characteristic | SDK/Inventar-Ziel | Callback-Erfolg | Versuche | Payloads | Varianten | variable Bytes | Sentinel-only |",
        "|---|---|---|---:|---:|---:|---:|---|---|",
    ]
    for field in payload["fields"]:
        variable = ",".join(map(str, field["variableByteIndexes"])) or "–"
        target = field.get("knownTarget") or "–"
        lines.append(
            f"| {field['service'] or '–'} | {field['characteristic']} | {target} | "
            f"{field['successes']}/{field['callbacks']} | {field['attempts']} | {field['payloadCount']} | "
            f"{field['uniquePayloadCount']} | {variable} | {'ja' if field['sentinelLikeOnly'] else 'nein'} |"
        )

    lines += ["", "## Priorität für die nächste Prüfung", ""]
    candidates = [
        field for field in payload["fields"]
        if field["successes"] > 0 and (field.get("knownTarget") or field["dynamicAcrossScans"])
    ]
    if candidates:
        for field in candidates[:12]:
            reason = []
            if field.get("knownTarget"):
                reason.append(str(field["knownTarget"]))
            if field["dynamicAcrossScans"]:
                reason.append("ändert sich zwischen Scans")
            if field["sentinelLikeOnly"]:
                reason.append("bisher nur Sentinel-/Platzhalterbytes")
            if field["sensitivePayloadRedacted"]:
                reason.append("öffentliche Payload SHA-256-redigiert")
            lines.append(f"- **{field['characteristic']}** — {'; '.join(reason)}")
    else:
        lines.append("- Noch keine erfolgreich beantworteten Deep-READ-Callbacks vorhanden.")

    lines += [
        "",
        "## Evidenzgrenze",
        "",
        "- Timeout, Verbindungsende und Advertisement sind Beobachtungen, keine READ-Antworten.",
        "- Kein READ-Wert wird automatisch als SOC, SOH, Zellspannung, Seriennummer oder Controllerparameter bezeichnet.",
        "- Kein KI-Konsens kann diese Grenze ersetzen.",
        "- Es werden keine Schreibframes, Schlüssel oder Authentifizierungswerte aus READ-Daten erzeugt.",
    ]
    return "\n".join(lines) + "\n"


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", action="append", dest="roots")
    parser.add_argument("--output", default="decoder-ai/gatt_read_comparison.json")
    parser.add_argument("--report", default="decoder-ai/gatt_read_comparison.md")
    args = parser.parse_args()

    roots = [Path(value) for value in (args.roots or ["diagnostics"])]
    payload = build_report(roots)
    output = Path(args.output)
    report = Path(args.report)
    output.parent.mkdir(parents=True, exist_ok=True)
    report.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    report.write_text(render_markdown(payload), encoding="utf-8")
    print(json.dumps({key: payload[key] for key in ("diagnosticBundles", "records", "readCallbacks", "characteristics")}, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

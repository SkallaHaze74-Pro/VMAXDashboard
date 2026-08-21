#!/usr/bin/env python3
"""Aggregate BT638 GATT READ diagnostics without treating them as live telemetry.

The Android scanner reads only characteristics that advertise PROPERTY_READ. This
analyzer inventories successful/failed reads, payload lengths, variability and
sentinel-like payloads. It deliberately does not infer byte semantics from values.
"""

from __future__ import annotations

import argparse
import csv
import json
from collections import Counter, defaultdict
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


def parse_hex(text: str) -> bytes:
    parts = [part.strip() for part in text.replace(":", "-").split("-") if part.strip()]
    if not parts:
        return b""
    try:
        return bytes(int(part, 16) for part in parts if len(part) == 2)
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


def load_rows(root: Path) -> tuple[list[dict[str, str]], set[str]]:
    rows: list[dict[str, str]] = []
    bundles: set[str] = set()
    for path in sorted(root.rglob("Gatt_READ_Diagnose.csv")) if root.is_dir() else []:
        bundles.add(path.parent.as_posix())
        try:
            with path.open("r", encoding="utf-8-sig", newline="") as handle:
                for row in csv.DictReader(handle, delimiter=";"):
                    if isinstance(row, dict):
                        row["__source"] = path.parent.as_posix()
                        rows.append(row)
        except OSError:
            continue
    return rows, bundles


def build_report(root: Path) -> dict[str, Any]:
    rows, bundles = load_rows(root)
    grouped: dict[tuple[str, str], list[dict[str, str]]] = defaultdict(list)
    for row in rows:
        service = str(row.get("service_uuid") or "").strip().upper()
        characteristic = str(row.get("characteristic_uuid") or row.get("short_id") or "").strip().upper()
        if characteristic:
            grouped[(service, characteristic)].append(row)

    fields: list[dict[str, Any]] = []
    for (service, characteristic), items in sorted(grouped.items()):
        statuses = Counter(str(item.get("status") or "") for item in items)
        payload_texts = [str(item.get("hex") or "").strip().upper() for item in items]
        non_empty_texts = [text for text in payload_texts if text]
        unique_texts = sorted(set(non_empty_texts))
        payload_bytes = [parse_hex(text) for text in unique_texts]
        payload_bytes = [payload for payload in payload_bytes if payload]
        lengths = Counter(len(parse_hex(text)) for text in non_empty_texts if parse_hex(text))
        sentinel_like = bool(payload_bytes) and all(
            all(byte in SENTINEL_BYTES for byte in payload) for payload in payload_bytes
        )
        evidence_values = sorted({str(item.get("evidence") or "").strip() for item in items if str(item.get("evidence") or "").strip()})
        meanings = sorted({str(item.get("meaning") or "").strip() for item in items if str(item.get("meaning") or "").strip()})
        fields.append(
            {
                "service": service,
                "characteristic": characteristic,
                "knownTarget": KNOWN_READ_TARGETS.get(characteristic),
                "attempts": len(items),
                "successes": statuses.get("0", 0),
                "failures": len(items) - statuses.get("0", 0),
                "statusCounts": dict(sorted(statuses.items())),
                "payloadCount": len(non_empty_texts),
                "uniquePayloadCount": len(unique_texts),
                "lengthCounts": {str(k): v for k, v in sorted(lengths.items())},
                "dynamicAcrossScans": len(unique_texts) > 1,
                "variableByteIndexes": variable_indexes(payload_bytes),
                "sentinelLikeOnly": sentinel_like,
                "samplePayloads": unique_texts[:8],
                "evidenceLabels": evidence_values,
                "meanings": meanings,
                "independentSemanticConfirmation": False,
            }
        )

    return {
        "schema": "vmax-bt638-gatt-read-aggregate-v1",
        "readOnly": True,
        "diagnosticBundles": len(bundles),
        "records": len(rows),
        "characteristics": len(fields),
        "successfulCharacteristics": sum(1 for field in fields if field["successes"] > 0),
        "payloadCharacteristics": sum(1 for field in fields if field["payloadCount"] > 0),
        "dynamicCharacteristics": sum(1 for field in fields if field["dynamicAcrossScans"]),
        "fields": fields,
        "evidencePolicy": (
            "A READ response proves only that this BT638/GATT characteristic answered with the recorded bytes. "
            "It does not by itself prove byte semantics, BMS origin, authentication state or a write capability."
        ),
    }


def render_markdown(payload: dict[str, Any]) -> str:
    lines = [
        "# BT638 GATT Deep-READ-Abgleich",
        "",
        f"Deep-READ-Dumps: **{payload['diagnosticBundles']}** • READ-Zeilen: **{payload['records']}** • Characteristics: **{payload['characteristics']}**",
        "",
        "> STRICT READ-ONLY. READ-Antworten bleiben von Live-Telemetrie und Decoder-Lernen getrennt. Ein antwortendes Feld beweist noch keine Byte-Semantik oder BMS-Herkunft.",
        "",
        "| Service | Characteristic | SDK/Inventar-Ziel | Erfolg | Payloads | Varianten | variable Bytes | Sentinel-only |",
        "|---|---|---|---:|---:|---:|---|---|",
    ]
    for field in payload["fields"]:
        variable = ",".join(map(str, field["variableByteIndexes"])) or "–"
        target = field.get("knownTarget") or "–"
        lines.append(
            f"| {field['service'] or '–'} | {field['characteristic']} | {target} | "
            f"{field['successes']}/{field['attempts']} | {field['payloadCount']} | "
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
            lines.append(f"- **{field['characteristic']}** — {'; '.join(reason)}")
    else:
        lines.append("- Noch keine beantworteten Deep-READ-Dumps vorhanden.")

    lines += [
        "",
        "## Evidenzgrenze",
        "",
        "- Kein READ-Wert wird automatisch als SOC, SOH, Zellspannung, Seriennummer oder Controllerparameter bezeichnet.",
        "- Kein KI-Konsens kann diese Grenze ersetzen.",
        "- Es werden keine Schreibframes, Schlüssel oder Authentifizierungswerte aus READ-Daten erzeugt.",
    ]
    return "\n".join(lines) + "\n"


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", default="diagnostics")
    parser.add_argument("--output", default="decoder-ai/gatt_read_comparison.json")
    parser.add_argument("--report", default="decoder-ai/gatt_read_comparison.md")
    args = parser.parse_args()

    payload = build_report(Path(args.root))
    output = Path(args.output)
    report = Path(args.report)
    output.parent.mkdir(parents=True, exist_ok=True)
    report.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    report.write_text(render_markdown(payload), encoding="utf-8")
    print(json.dumps({key: payload[key] for key in ("diagnosticBundles", "records", "characteristics", "successfulCharacteristics")}, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

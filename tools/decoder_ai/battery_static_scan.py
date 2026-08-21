#!/usr/bin/env python3
from __future__ import annotations

import argparse
import csv
import json
from collections import Counter, defaultdict
from pathlib import Path

from raw_origin_guard import is_accepted_live_notification

SENTINELS = {0xFFFF, 0x8000}


def parse_hex(text: str) -> bytes:
    try:
        return bytes(int(part, 16) for part in text.replace(":", "-").replace(" ", "-").split("-") if part)
    except ValueError:
        return b""


def ride_dirs(root: Path) -> list[Path]:
    if not root.exists():
        return []
    return sorted(p for p in root.rglob("Messfahrt_*") if p.is_dir() and (p / "BLE_Rohdaten.csv").is_file())


def raw_rows(path: Path):
    if not path.is_file():
        return []
    with path.open("r", encoding="utf-8-sig", newline="") as handle:
        return list(csv.DictReader(handle, delimiter=";"))


def word_summary(samples: list[bytes], offset: int) -> dict:
    values = []
    sentinels = Counter()
    for data in samples:
        if offset + 2 > len(data):
            continue
        raw = int.from_bytes(data[offset:offset + 2], "big", signed=False)
        if raw == 0xFFFF:
            sentinels["ffff"] += 1
        elif raw == 0x8000:
            sentinels["8000"] += 1
        else:
            values.append(raw)
    counts = Counter(values)
    top = [{"value": value, "count": count} for value, count in counts.most_common(8)]
    return {
        "offset": offset,
        "samples": len(samples),
        "valid": len(values),
        "distinct": len(counts),
        "min": min(values) if values else None,
        "max": max(values) if values else None,
        "topValues": top,
        "sentinels": dict(sentinels),
    }


def byte_summary(samples: list[bytes], offset: int) -> dict:
    vals = [data[offset] for data in samples if offset < len(data)]
    counts = Counter(vals)
    return {
        "offset": offset,
        "samples": len(vals),
        "distinct": len(counts),
        "topValuesHex": [{"value": f"{value:02X}", "count": count} for value, count in counts.most_common(8)],
    }


def analyze(root: Path) -> dict:
    samples = defaultdict(list)
    per_ride = []
    for ride in ride_dirs(root):
        local = defaultdict(list)
        for row in raw_rows(ride / "BLE_Rohdaten.csv"):
            if not is_accepted_live_notification(row):
                continue
            channel = str(row.get("channel") or "").strip().upper()
            if channel not in {"1502", "150C"}:
                continue
            data = parse_hex(str(row.get("hex") or ""))
            if not data:
                continue
            samples[channel].append(data)
            local[channel].append(data)
        per_ride.append({
            "ride": ride.name,
            "1502Packets": len(local["1502"]),
            "1502First": local["1502"][0].hex("-").upper() if local["1502"] else None,
            "1502Last": local["1502"][-1].hex("-").upper() if local["1502"] else None,
            "150CPackets": len(local["150C"]),
            "150CPatterns": [
                {"hex": bytes.fromhex(key).hex("-").upper(), "count": count}
                for key, count in Counter(data.hex() for data in local["150C"]).most_common(5)
            ],
        })

    s1502 = samples["1502"]
    s150c = samples["150C"]
    words1502 = [word_summary(s1502, offset) for offset in range(0, 16, 2)]

    equality = []
    for left in range(0, 16, 2):
        for right in range(left + 2, 16, 2):
            pairs = 0
            equal = 0
            for data in s1502:
                if right + 2 > len(data):
                    continue
                a = int.from_bytes(data[left:left + 2], "big")
                b = int.from_bytes(data[right:right + 2], "big")
                if a in SENTINELS or b in SENTINELS:
                    continue
                pairs += 1
                equal += int(a == b)
            if pairs:
                pct = round(100.0 * equal / pairs, 3)
                if pct >= 95.0:
                    equality.append({"offsetA": left, "offsetB": right, "pairs": pairs, "equalPercent": pct})

    patterns1502 = Counter(data.hex() for data in s1502)
    patterns150c = Counter(data.hex() for data in s150c)
    return {
        "schema": "vmax-battery-static-scan-v1",
        "rideCount": len(ride_dirs(root)),
        "channel1502": {
            "packets": len(s1502),
            "lengths": dict(Counter(len(data) for data in s1502)),
            "distinctPackets": len(patterns1502),
            "topPacketPatterns": [
                {"hex": bytes.fromhex(key).hex("-").upper(), "count": count}
                for key, count in patterns1502.most_common(10)
            ],
            "alignedU16be": words1502,
            "highEqualityWordPairs": equality,
            "interpretation": "Static battery metadata candidate only. No capacity/SOH meaning is assigned without independent evidence.",
        },
        "channel150C": {
            "packets": len(s150c),
            "lengths": dict(Counter(len(data) for data in s150c)),
            "distinctPackets": len(patterns150c),
            "topPacketPatterns": [
                {"hex": bytes.fromhex(key).hex("-").upper(), "count": count}
                for key, count in patterns150c.most_common(10)
            ],
            "bytes": [byte_summary(s150c, offset) for offset in range(max((len(x) for x in s150c), default=0))],
            "interpretation": "BatteryCellUpdate presence only; exact cellVoltage/cellTemp/cellIndex offsets remain unassigned.",
        },
        "openCallbacks": ["batteryCapacityMwh", "chargingRemainSeconds", "charging", "stateOfHealthPercent", "stateOfHealthMwh"],
        "perRide": per_ride,
    }


def render_report(result: dict) -> str:
    c2 = result["channel1502"]
    cc = result["channel150C"]
    lines = [
        "# Batterie-Zielanalyse 1502 / 150C", "",
        f"Fahrten: **{result['rideCount']}**", "",
        "## 1502 – statische Akkuinformationen", "",
        f"- Pakete: **{c2['packets']}**; verschiedene Pakete: **{c2['distinctPackets']}**",
        f"- Längen: `{json.dumps(c2['lengths'])}`",
        "- Häufigste Pakete:",
    ]
    for item in c2["topPacketPatterns"][:5]:
        lines.append(f"  - `{item['hex']}` × {item['count']}")
    lines += ["", "### Ausgerichtete u16be-Wörter", "", "| Offset | gültig | verschieden | min | max | häufigste Werte |", "|---:|---:|---:|---:|---:|---|"]
    for item in c2["alignedU16be"]:
        top = ", ".join(f"{v['value']}×{v['count']}" for v in item["topValues"][:4]) or "–"
        lines.append(f"| {item['offset']} | {item['valid']} | {item['distinct']} | {item['min']} | {item['max']} | {top} |")
    lines += ["", "### Auffällig gleiche Wortpaare", ""]
    if c2["highEqualityWordPairs"]:
        for item in c2["highEqualityWordPairs"]:
            lines.append(f"- Offset {item['offsetA']} ↔ {item['offsetB']}: {item['equalPercent']}% gleich ({item['pairs']} Paare)")
    else:
        lines.append("- Keine ≥95%-Gleichheit bei gültigen Wortpaaren.")
    lines += [
        "", "## 150C – BatteryCellUpdate", "",
        f"- Pakete: **{cc['packets']}**; verschiedene Pakete: **{cc['distinctPackets']}**",
        f"- Längen: `{json.dumps(cc['lengths'])}`",
    ]
    for item in cc["topPacketPatterns"][:5]:
        lines.append(f"- `{item['hex']}` × {item['count']}")
    lines += [
        "", "## Noch offen", "",
        "- batteryCapacityMwh", "- chargingRemainSeconds", "- charging", "- stateOfHealthPercent", "- stateOfHealthMwh", "",
        "> Keine Zuordnung wird aus einem plausiblen Zahlenwert allein bestätigt. 1502/150C bleiben bis zu unabhängiger Evidenz Kandidaten-/Diagnosequellen.", "",
    ]
    return "\n".join(lines)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", default="fahrdaten")
    parser.add_argument("--output", default="decoder-ai/battery_static_scan.json")
    parser.add_argument("--report", default="decoder-ai/battery_static_scan.md")
    args = parser.parse_args()
    result = analyze(Path(args.root))
    output = Path(args.output)
    report = Path(args.report)
    output.parent.mkdir(parents=True, exist_ok=True)
    report.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    report.write_text(render_report(result), encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

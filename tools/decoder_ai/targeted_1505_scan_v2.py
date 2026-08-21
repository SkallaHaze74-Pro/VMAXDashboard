#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
from pathlib import Path

import targeted_1505_scan as base


def suspicious_1505_hybrid(data: bytes) -> bool:
    """Reject the known firmware-ID/read contamination seen on channel 1505.

    Genuine BT638 BikePerformance packets use the 1505 layout. Two historical
    exports contain an 18-byte ASCII-like payload (e.g. IKE_01234567895...) on
    the same characteristic; interpreting its ASCII digits as RPM/range creates
    false values such as 13108/13622. Keep this identical to the read-only
    evidence filters used by the other decoder analyzers.
    """
    if len(data) < 18:
        return False
    printable_tail = sum(0x20 <= value <= 0x7E for value in data[8:])
    return printable_tail >= 6


def analyze(root: Path) -> dict:
    original_read_rows = base.read_rows
    filtered = 0

    def filtered_read_rows(path: Path) -> list[dict]:
        nonlocal filtered
        rows = original_read_rows(path)
        out = []
        for row in rows:
            channel = str(row.get("channel") or "").strip().upper()
            data = base.parse_hex(str(row.get("hex") or ""))
            if channel == "1505" and suspicious_1505_hybrid(data):
                filtered += 1
                continue
            out.append(row)
        return out

    base.read_rows = filtered_read_rows
    try:
        result = base.analyze(root)
    finally:
        base.read_rows = original_read_rows

    result["schema"] = "vmax-targeted-1505-scan-v2"
    result["filtered1505HybridPackets"] = filtered
    return result


def render_report(result: dict) -> str:
    report = base.render_report(result)
    marker = f"1505-NOTIFY-Pakete: **{result['packets1505']}**"
    replacement = marker + f"\nVerworfene 1505 ASCII-/READ-Hybridframes: **{result.get('filtered1505HybridPackets', 0)}**"
    return report.replace(marker, replacement, 1)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", default="fahrdaten")
    parser.add_argument("--output", default="decoder-ai/targeted_1505_scan.json")
    parser.add_argument("--report", default="decoder-ai/targeted_1505_scan.md")
    args = parser.parse_args()
    result = analyze(Path(args.root))
    output = Path(args.output)
    report = Path(args.report)
    output.parent.mkdir(parents=True, exist_ok=True)
    report.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    report.write_text(render_report(result), encoding="utf-8")
    print(
        f"Targeted scan v2: {result['rideCount']} rides, {result['packets1505']} 1505 packets, "
        f"{result['filtered1505HybridPackets']} hybrids filtered"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

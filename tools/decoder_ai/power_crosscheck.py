#!/usr/bin/env python3
"""Cross-field validation for the uncertain 1509/9 power candidate.

This deliberately avoids comparing 1509/9 against `power_w`, because that export can
itself be sourced from 1509/9. Instead it compares the raw direct-power candidate with
an independently derived electrical magnitude |voltage * current| from the other
1509 bytes. This is still not external semantic proof, but it is a genuinely different
measurement path and therefore useful evidence for/against the candidate.
"""

from __future__ import annotations

import argparse
import csv
import json
import math
import statistics
from pathlib import Path
from typing import Any

SCHEMA = "vmax-power-crosscheck-v1"
MAX_DIRECT_POWER_W = 30_000.0
MAX_ELECTRICAL_POWER_W = 30_000.0
MIN_COMPARISONS_FOR_SUMMARY = 20


def _finite(value: object) -> float | None:
    try:
        if value is None or str(value).strip() == "":
            return None
        out = float(value)
    except (TypeError, ValueError):
        return None
    return out if math.isfinite(out) else None


def _read_rows(path: Path) -> list[dict[str, str]]:
    if not path.is_file():
        return []
    with path.open("r", encoding="utf-8-sig", newline="") as handle:
        return list(csv.DictReader(handle, delimiter=";"))


def _ride_dirs(root: Path) -> list[Path]:
    if not root.exists():
        return []
    return sorted(
        path for path in root.rglob("Messfahrt_*")
        if path.is_dir() and (path / "Live_Telemetrie.csv").is_file()
    )


def _pearson(xs: list[float], ys: list[float]) -> float | None:
    if len(xs) < 2 or len(xs) != len(ys):
        return None
    mx = statistics.fmean(xs)
    my = statistics.fmean(ys)
    dx = [x - mx for x in xs]
    dy = [y - my for y in ys]
    varx = sum(value * value for value in dx)
    vary = sum(value * value for value in dy)
    if varx <= 1e-12 or vary <= 1e-12:
        return None
    return sum(a * b for a, b in zip(dx, dy)) / math.sqrt(varx * vary)


def analyze(root: Path) -> dict[str, Any]:
    rides = _ride_dirs(root)
    direct: list[float] = []
    electrical: list[float] = []
    errors: list[float] = []
    close = 0
    per_ride: list[dict[str, Any]] = []

    for ride in rides:
        ride_direct: list[float] = []
        ride_electrical: list[float] = []
        ride_errors: list[float] = []
        ride_close = 0
        for row in _read_rows(ride / "Live_Telemetrie.csv"):
            if str(row.get("source_channel") or "").strip().upper() != "1509":
                continue
            raw_direct = _finite(row.get("motor_load_raw_be"))
            electrical_raw = _finite(row.get("electrical_power_w"))
            if raw_direct is None or electrical_raw is None:
                continue
            electrical_mag = abs(electrical_raw)
            if not (0.0 <= raw_direct <= MAX_DIRECT_POWER_W):
                continue
            if not (0.0 <= electrical_mag <= MAX_ELECTRICAL_POWER_W):
                continue
            error = abs(raw_direct - electrical_mag)
            tolerance = max(25.0, electrical_mag * 0.10)
            if error <= tolerance:
                close += 1
                ride_close += 1
            direct.append(raw_direct)
            electrical.append(electrical_mag)
            errors.append(error)
            ride_direct.append(raw_direct)
            ride_electrical.append(electrical_mag)
            ride_errors.append(error)

        if ride_errors:
            per_ride.append({
                "ride": ride.name,
                "comparisons": len(ride_errors),
                "maeW": round(statistics.fmean(ride_errors), 6),
                "medianAbsErrorW": round(statistics.median(ride_errors), 6),
                "closePercent": round(ride_close * 100.0 / len(ride_errors), 2),
                "correlation": None if _pearson(ride_direct, ride_electrical) is None else round(_pearson(ride_direct, ride_electrical), 6),
            })

    count = len(errors)
    correlation = _pearson(direct, electrical)
    return {
        "schema": SCHEMA,
        "candidate": "1509/9 u16be",
        "reference": "abs(voltage_v * current_a) via electrical_power_w",
        "evidenceType": "cross-field-physical-consistency",
        "independentExternalConfirmation": False,
        "rideCount": len(rides),
        "ridesWithComparisons": len(per_ride),
        "comparisons": count,
        "maeW": round(statistics.fmean(errors), 6) if errors else None,
        "medianAbsErrorW": round(statistics.median(errors), 6) if errors else None,
        "closePercent": round(close * 100.0 / count, 2) if count else None,
        "correlation": round(correlation, 6) if correlation is not None else None,
        "status": (
            "CROSS_FIELD_EVIDENCE_AVAILABLE" if count >= MIN_COMPARISONS_FOR_SUMMARY
            else "NEEDS_MORE_COMPARISONS"
        ),
        "note": (
            "Cross-field consistency is useful evidence but is not external semantic proof. "
            "It must never activate the decoder rule by itself."
        ),
        "rides": per_ride,
    }


def render_markdown(result: dict[str, Any]) -> str:
    def show(value: object, suffix: str = "") -> str:
        return "–" if value is None else f"{value}{suffix}"

    lines = [
        "# 1509/9 Power Cross-Check",
        "",
        "Vergleich: **1509/9 RAW-Kandidat** gegen **|Spannung × Strom|** aus anderen 1509-Feldern.",
        "Dieser Test vermeidet die frühere Selbstbestätigung über `power_w`.",
        "",
        f"- Fahrten mit Vergleich: **{result.get('ridesWithComparisons', 0)}** / {result.get('rideCount', 0)}",
        f"- Vergleiche: **{result.get('comparisons', 0)}**",
        f"- MAE: **{show(result.get('maeW'), ' W')}**",
        f"- Median |Fehler|: **{show(result.get('medianAbsErrorW'), ' W')}**",
        f"- Physikalisch nah (±max(25 W, 10%)): **{show(result.get('closePercent'), '%')}**",
        f"- Korrelation: **{show(result.get('correlation'))}**",
        f"- Status: `{result.get('status', 'UNKNOWN')}`",
        "",
        "> Wichtig: Das ist Cross-Field-Evidenz, keine externe Ground Truth. Eine Decoder-Regel wird dadurch nicht automatisch bestätigt.",
        "",
    ]
    if result.get("rides"):
        lines += [
            "## Pro Fahrt",
            "",
            "| Fahrt | Vergleiche | MAE W | Nähe | Korrelation |",
            "|---|---:|---:|---:|---:|",
        ]
        for ride in result["rides"]:
            lines.append(
                f"| {ride.get('ride', '')} | {ride.get('comparisons', 0)} | "
                f"{show(ride.get('maeW'))} | {show(ride.get('closePercent'), '%')} | "
                f"{show(ride.get('correlation'))} |"
            )
        lines.append("")
    return "\n".join(lines)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", default="fahrdaten")
    parser.add_argument("--output", default="decoder-ai/power_crosscheck.json")
    parser.add_argument("--report", default="decoder-ai/power_crosscheck.md")
    args = parser.parse_args()

    result = analyze(Path(args.root))
    output = Path(args.output)
    report = Path(args.report)
    output.parent.mkdir(parents=True, exist_ok=True)
    report.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    report.write_text(render_markdown(result), encoding="utf-8")
    print(
        "Power cross-check: "
        f"{result['comparisons']} comparisons, MAE={result['maeW']}, "
        f"close={result['closePercent']}%, corr={result['correlation']}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

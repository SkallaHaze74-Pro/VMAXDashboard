#!/usr/bin/env python3
from __future__ import annotations

import argparse
import csv
import json
import math
import statistics
from bisect import bisect_left
from collections import Counter
from pathlib import Path
from typing import Optional

from raw_origin_guard import is_accepted_live_notification

SENTINELS = {0xFFFF, 0x8000}


def parse_hex(text: str) -> bytes:
    parts = [p for p in text.replace(":", "-").replace(" ", "-").split("-") if p]
    try:
        return bytes(int(p, 16) for p in parts)
    except ValueError:
        return b""


def read_rows(path: Path) -> list[dict]:
    if not path.is_file():
        return []
    with path.open("r", encoding="utf-8-sig", newline="") as handle:
        return list(csv.DictReader(handle, delimiter=";"))


def timestamp_ms(row: dict) -> Optional[int]:
    try:
        return int(row.get("timestamp_ms") or 0)
    except (TypeError, ValueError):
        return None


def word_u16be(data: bytes, offset: int) -> Optional[int]:
    if offset < 0 or offset + 2 > len(data):
        return None
    return int.from_bytes(data[offset:offset + 2], "big", signed=False)


def word_s16be(data: bytes, offset: int) -> Optional[int]:
    if offset < 0 or offset + 2 > len(data):
        return None
    return int.from_bytes(data[offset:offset + 2], "big", signed=True)


def valid_word(raw: Optional[int]) -> Optional[int]:
    if raw is None or raw in SENTINELS:
        return None
    return raw


def pearson(xs: list[float], ys: list[float]) -> Optional[float]:
    if len(xs) < 3 or len(xs) != len(ys):
        return None
    mx = statistics.fmean(xs)
    my = statistics.fmean(ys)
    dx = [x - mx for x in xs]
    dy = [y - my for y in ys]
    sx = sum(v * v for v in dx)
    sy = sum(v * v for v in dy)
    if sx <= 1e-12 or sy <= 1e-12:
        return None
    return sum(a * b for a, b in zip(dx, dy)) / math.sqrt(sx * sy)


def summarize(values: list[float]) -> dict:
    if not values:
        return {"samples": 0}
    return {
        "samples": len(values),
        "min": min(values),
        "max": max(values),
        "mean": round(statistics.fmean(values), 6),
        "distinct": len(set(values)),
    }


def classify_raw(raw: Optional[int]) -> str:
    if raw is None:
        return "missing"
    if raw == 0xFFFF:
        return "ffff"
    if raw == 0x8000:
        return "8000"
    return "valid"


def nearest_battery(rows1509: list[tuple[int, bytes]], ts: int, max_delta_ms: int = 300):
    if not rows1509:
        return None
    times = [item[0] for item in rows1509]
    pos = bisect_left(times, ts)
    candidates = []
    if pos < len(rows1509):
        candidates.append(rows1509[pos])
    if pos > 0:
        candidates.append(rows1509[pos - 1])
    if not candidates:
        return None
    best = min(candidates, key=lambda item: abs(item[0] - ts))
    if abs(best[0] - ts) > max_delta_ms:
        return None
    data = best[1]
    current_raw = word_s16be(data, 0)
    voltage_raw = word_u16be(data, 5)
    soc_raw = data[4] if len(data) > 4 else None
    if current_raw is None or voltage_raw is None or voltage_raw in SENTINELS:
        return None
    current_a = current_raw / 1000.0
    voltage_v = voltage_raw / 1000.0
    if not (-300 <= current_a <= 300 and 0 < voltage_v <= 100):
        return None
    return {
        "power_w": abs(current_a * voltage_v),
        "soc": float(soc_raw) if soc_raw is not None and 0 <= soc_raw <= 100 else None,
    }


def compare_power(values: list[tuple[float, float]]) -> dict:
    if not values:
        return {"comparisons": 0}
    candidate = [v[0] for v in values]
    reference = [v[1] for v in values]
    errors = [abs(a - b) for a, b in values]
    close = [e <= max(25.0, 0.10 * max(b, 1.0)) for e, b in zip(errors, reference)]
    corr = pearson(candidate, reference)
    return {
        "comparisons": len(values),
        "maeW": round(statistics.fmean(errors), 6),
        "medianAbsErrorW": round(statistics.median(errors), 6),
        "closePercent": round(100.0 * sum(close) / len(close), 2),
        "correlation": round(corr, 6) if corr is not None else None,
    }


def ride_dirs(root: Path) -> list[Path]:
    if not root.exists():
        return []
    return sorted(p for p in root.rglob("Messfahrt_*") if p.is_dir() and (p / "BLE_Rohdaten.csv").is_file())


def analyze(root: Path) -> dict:
    total_1505 = 0
    a_values: list[float] = []
    b_values: list[float] = []
    rpm_values: list[float] = []
    range_values: list[float] = []
    speed_for_rpm: list[float] = []
    rpm_for_speed: list[float] = []
    range_for_soc: list[float] = []
    soc_for_range: list[float] = []
    a_vs_power: list[tuple[float, float]] = []
    b_vs_power: list[tuple[float, float]] = []
    ab_valid = 0
    ab_equal = 0
    ab_abs_diff: list[float] = []
    rpm_raw_states = Counter()
    range_raw_states = Counter()
    channel_counts = Counter()
    channel_lengths: dict[str, Counter] = {}
    per_ride = []

    for ride in ride_dirs(root):
        raw_rows = read_rows(ride / "BLE_Rohdaten.csv")
        rows1509: list[tuple[int, bytes]] = []
        rows1505: list[tuple[int, bytes]] = []
        local_a = local_b = local_equal = 0
        local_rpm_valid = local_range_valid = 0

        for row in raw_rows:
            if not is_accepted_live_notification(row):
                continue
            channel = str(row.get("channel") or "").strip().upper()
            data = parse_hex(str(row.get("hex") or ""))
            ts = timestamp_ms(row)
            if not channel or not data or ts is None:
                continue
            channel_counts[channel] += 1
            channel_lengths.setdefault(channel, Counter())[len(data)] += 1
            if channel == "1509":
                rows1509.append((ts, data))
            elif channel == "1505":
                rows1505.append((ts, data))

        rows1509.sort(key=lambda item: item[0])
        for ts, data in rows1505:
            total_1505 += 1
            a_raw = valid_word(word_u16be(data, 0))
            b_raw = valid_word(word_u16be(data, 2))
            speed_raw = valid_word(word_u16be(data, 6))
            rpm_raw_any = word_u16be(data, 8)
            range_raw_any = word_u16be(data, 10)
            rpm_raw_states[classify_raw(rpm_raw_any)] += 1
            range_raw_states[classify_raw(range_raw_any)] += 1
            rpm_raw = valid_word(rpm_raw_any)
            range_raw = valid_word(range_raw_any)

            a = a_raw / 10.0 if a_raw is not None else None
            b = b_raw / 10.0 if b_raw is not None else None
            speed = speed_raw / 10.0 if speed_raw is not None else None
            rpm = float(rpm_raw) if rpm_raw is not None else None
            remain = float(range_raw) if range_raw is not None else None

            if a is not None:
                a_values.append(a)
                local_a += 1
            if b is not None:
                b_values.append(b)
                local_b += 1
            if a is not None and b is not None:
                ab_valid += 1
                diff = abs(a - b)
                ab_abs_diff.append(diff)
                if diff <= 1e-9:
                    ab_equal += 1
                    local_equal += 1
            if rpm is not None:
                rpm_values.append(rpm)
                local_rpm_valid += 1
                if speed is not None:
                    rpm_for_speed.append(rpm)
                    speed_for_rpm.append(speed)
            if remain is not None:
                range_values.append(remain)
                local_range_valid += 1

            battery = nearest_battery(rows1509, ts)
            if battery is not None:
                p = battery["power_w"]
                if a is not None:
                    a_vs_power.append((a, p))
                if b is not None:
                    b_vs_power.append((b, p))
                if remain is not None and battery.get("soc") is not None:
                    range_for_soc.append(remain)
                    soc_for_range.append(float(battery["soc"]))

        per_ride.append({
            "ride": ride.name,
            "packets1505": len(rows1505),
            "powerAValid": local_a,
            "powerBValid": local_b,
            "powerABEqual": local_equal,
            "rpmValid": local_rpm_valid,
            "remainingRangeValid": local_range_valid,
        })

    rpm_corr = pearson(rpm_for_speed, speed_for_rpm)
    range_soc_corr = pearson(range_for_soc, soc_for_range)
    ab_equal_pct = round(100.0 * ab_equal / ab_valid, 3) if ab_valid else None
    max_diff = max(ab_abs_diff) if ab_abs_diff else None

    interesting_channels = {}
    for channel in ("1502", "1508", "1509", "150A", "150C", "150D"):
        lengths = channel_lengths.get(channel, Counter())
        interesting_channels[channel] = {
            "packets": channel_counts.get(channel, 0),
            "lengths": {str(k): v for k, v in sorted(lengths.items())},
        }

    if ab_valid and ab_equal_pct is not None and ab_equal_pct >= 99.9:
        ab_status = "DUPLICATED_ON_CURRENT_BT638_DATA"
    elif ab_valid:
        ab_status = "DIFFERENCES_OBSERVED"
    else:
        ab_status = "NO_VALID_DATA"

    if rpm_raw_states["valid"] == 0:
        rpm_status = "NO_VALID_RPM_SAMPLES_CURRENT_DATA"
    else:
        rpm_status = "RPM_VALUES_OBSERVED_NEEDS_VALIDATION"

    if range_raw_states["valid"] == 0:
        range_status = "NO_VALID_RANGE_SAMPLES_CURRENT_DATA"
    else:
        range_status = "RANGE_VALUES_OBSERVED_NEEDS_VALIDATION"

    return {
        "schema": "vmax-targeted-1505-scan-v1",
        "rideCount": len(ride_dirs(root)),
        "packets1505": total_1505,
        "powerAB": {
            "status": ab_status,
            "validPairs": ab_valid,
            "equalPairs": ab_equal,
            "equalPercent": ab_equal_pct,
            "maxAbsDifferenceW": round(max_diff, 6) if max_diff is not None else None,
            "powerA": summarize(a_values),
            "powerB": summarize(b_values),
            "powerA_vs_absVI": compare_power(a_vs_power),
            "powerB_vs_absVI": compare_power(b_vs_power),
            "note": "A/B equality or correlation is observational evidence only; it does not assign motor/treadle semantics.",
        },
        "rpm1505_8": {
            "status": rpm_status,
            "rawStates": dict(rpm_raw_states),
            "values": summarize(rpm_values),
            "speedCorrelation": round(rpm_corr, 6) if rpm_corr is not None else None,
        },
        "remainingDistance1505_10": {
            "status": range_status,
            "rawStates": dict(range_raw_states),
            "values": summarize(range_values),
            "socCorrelation": round(range_soc_corr, 6) if range_soc_corr is not None else None,
        },
        "batteryTargetReadiness": {
            "knownCallbacksStillUnmapped": [
                "batteryCapacityMwh",
                "chargingRemainSeconds",
                "charging",
                "stateOfHealthPercent",
                "stateOfHealthMwh",
            ],
            "interestingChannelPresence": interesting_channels,
            "note": "No semantic byte mapping is invented from presence alone. A long ride helps dynamic fields; charging/SOH may require charging-state or diagnostic READ evidence.",
        },
        "rides": per_ride,
    }


def render_report(result: dict) -> str:
    p = result["powerAB"]
    rpm = result["rpm1505_8"]
    remain = result["remainingDistance1505_10"]
    lines = [
        "# Zielanalyse 1505 + Batterie/SOH",
        "",
        f"Ausgewertete Fahrten: **{result['rideCount']}**",
        f"1505-NOTIFY-Pakete: **{result['packets1505']}**",
        "",
        "## Power A/B — 1505/0 und 1505/2",
        "",
        f"- Status: `{p['status']}`",
        f"- Gültige A/B-Paare: **{p['validPairs']}**",
        f"- Exakt gleich: **{p['equalPairs']}** ({p['equalPercent']}%)",
        f"- Max. A/B-Abweichung: **{p['maxAbsDifferenceW']} W**",
        f"- A vs |V×I|: `{json.dumps(p['powerA_vs_absVI'], ensure_ascii=False)}`",
        f"- B vs |V×I|: `{json.dumps(p['powerB_vs_absVI'], ensure_ascii=False)}`",
        "",
        "## RPM — 1505/8",
        "",
        f"- Status: `{rpm['status']}`",
        f"- RAW-Zustände: `{json.dumps(rpm['rawStates'], ensure_ascii=False)}`",
        f"- Werte: `{json.dumps(rpm['values'], ensure_ascii=False)}`",
        f"- Korrelation zu Speed: `{rpm['speedCorrelation']}`",
        "",
        "## Restreichweite — 1505/10",
        "",
        f"- Status: `{remain['status']}`",
        f"- RAW-Zustände: `{json.dumps(remain['rawStates'], ensure_ascii=False)}`",
        f"- Werte: `{json.dumps(remain['values'], ensure_ascii=False)}`",
        f"- Korrelation zu SOC: `{remain['socCorrelation']}`",
        "",
        "## Batterie/SOH",
        "",
        "Noch ohne erfundene Zuordnung: batteryCapacityMwh, chargingRemainSeconds, charging, stateOfHealthPercent, stateOfHealthMwh.",
        "Die Kanäle werden nur auf Präsenz/Längen inventarisiert. Für dynamische Werte hilft eine Langfahrt; Charging/SOH kann zusätzliche Lade- oder READ-Evidenz brauchen.",
        "",
        "## Sicherheits-/Evidenzregel",
        "",
        "Diese Analyse ist rein lesend. Sie erzeugt keine BLE-Schreibbefehle und bestätigt keine Semantik allein aus Korrelation.",
        "",
    ]
    return "\n".join(lines)


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
    print(f"Targeted scan: {result['rideCount']} rides, {result['packets1505']} 1505 packets")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

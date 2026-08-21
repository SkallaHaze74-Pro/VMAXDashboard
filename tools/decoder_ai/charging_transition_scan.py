#!/usr/bin/env python3
from __future__ import annotations

import argparse
import csv
import json
from bisect import bisect_left
from dataclasses import dataclass
from pathlib import Path
from typing import Optional

MIN_GAP_MS = 10_000
CHARGE_MARKER_TERMS = ("ladegerät", "laden", "charging", "ble beim laden")


@dataclass(frozen=True)
class BatterySample:
    timestamp_ms: int
    relative_ms: int
    connection_epoch: Optional[int]
    soc_percent: Optional[int]
    voltage_v: Optional[float]
    current_a: Optional[float]
    battery_temp_c: Optional[float]
    raw_hex: str


def read_csv(path: Path) -> list[dict]:
    if not path.is_file():
        return []
    with path.open("r", encoding="utf-8-sig", newline="") as handle:
        return list(csv.DictReader(handle, delimiter=";"))


def parse_hex(text: str) -> bytes:
    try:
        parts = [p for p in text.replace(":", "-").replace(" ", "-").split("-") if p]
        return bytes(int(part, 16) for part in parts)
    except ValueError:
        return b""


def as_int(value: object) -> Optional[int]:
    try:
        text = str(value or "").strip()
        return int(text) if text else None
    except (TypeError, ValueError):
        return None


def decode_1509(row: dict) -> Optional[BatterySample]:
    channel = str(row.get("channel") or "").strip().upper()
    origin = str(row.get("origin") or "NOTIFICATION").strip().upper()
    if channel != "1509" or origin == "READ":
        return None
    data = parse_hex(str(row.get("hex") or ""))
    if len(data) < 11:
        return None
    timestamp = as_int(row.get("timestamp_ms"))
    relative = as_int(row.get("relative_ms"))
    if timestamp is None or relative is None:
        return None

    current_raw = int.from_bytes(data[0:2], "big", signed=True)
    temp_raw = int.from_bytes(data[2:4], "big", signed=True)
    soc_raw = data[4]
    voltage_raw = int.from_bytes(data[5:7], "big", signed=False)

    current = None if data[0:2] in (b"\xff\xff", b"\x80\x00") else current_raw / 1000.0
    battery_temp = None if data[2:4] in (b"\xff\xff", b"\x80\x00") else temp_raw / 10.0
    soc = soc_raw if 0 <= soc_raw <= 100 else None
    voltage = None if data[5:7] in (b"\xff\xff", b"\x80\x00") else voltage_raw / 1000.0
    epoch = as_int(row.get("connection_epoch"))

    return BatterySample(
        timestamp_ms=timestamp,
        relative_ms=relative,
        connection_epoch=epoch,
        soc_percent=soc,
        voltage_v=voltage,
        current_a=current,
        battery_temp_c=battery_temp,
        raw_hex=str(row.get("hex") or ""),
    )


def marker_rows(path: Path) -> list[dict]:
    out = []
    for row in read_csv(path):
        timestamp = as_int(row.get("timestamp_ms"))
        marker = str(row.get("marker") or "").strip()
        if timestamp is not None and marker:
            out.append({"timestamp_ms": timestamp, "marker": marker})
    return sorted(out, key=lambda item: item["timestamp_ms"])


def markers_near_gap(markers: list[dict], before_ms: int, after_ms: int) -> list[str]:
    low = before_ms - 30_000
    high = after_ms + 30_000
    return [item["marker"] for item in markers if low <= item["timestamp_ms"] <= high]


def charge_marker_present(markers: list[str]) -> bool:
    joined = " | ".join(markers).lower()
    return any(term in joined for term in CHARGE_MARKER_TERMS)


def nearest_raw(rows: list[tuple[int, str]], target_ms: int, max_delta_ms: int = 5 * 60_000) -> Optional[str]:
    if not rows:
        return None
    times = [item[0] for item in rows]
    pos = bisect_left(times, target_ms)
    candidates = []
    if pos < len(rows):
        candidates.append(rows[pos])
    if pos > 0:
        candidates.append(rows[pos - 1])
    if not candidates:
        return None
    best = min(candidates, key=lambda item: abs(item[0] - target_ms))
    return best[1] if abs(best[0] - target_ms) <= max_delta_ms else None


def finite_delta(after: Optional[float], before: Optional[float], digits: int = 3) -> Optional[float]:
    if after is None or before is None:
        return None
    return round(after - before, digits)


def classify_gap(before: BatterySample, after: BatterySample, markers: list[str]) -> tuple[str, list[str]]:
    reasons = []
    explicit = charge_marker_present(markers)
    soc_delta = None if before.soc_percent is None or after.soc_percent is None else after.soc_percent - before.soc_percent
    voltage_delta = finite_delta(after.voltage_v, before.voltage_v)
    if explicit:
        reasons.append("expliziter Lade-/BLE-Marker im Übergangsfenster")
    if soc_delta is not None and soc_delta >= 1:
        reasons.append(f"SOC stieg um {soc_delta} Prozentpunkt(e)")
    if voltage_delta is not None and voltage_delta >= 0.30:
        reasons.append(f"Spannung stieg um {voltage_delta:.3f} V")

    if explicit and (soc_delta is None or soc_delta >= 0):
        return "EXPLICIT_CHARGE_GAP_OBSERVED", reasons
    if (soc_delta is not None and soc_delta >= 1) or (voltage_delta is not None and voltage_delta >= 0.30):
        return "BATTERY_RISE_DURING_BLE_GAP", reasons
    return "BLE_GAP_NO_CLEAR_CHARGE_EVIDENCE", reasons


def analyze_ride(ride: Path) -> dict:
    raw_rows = read_csv(ride / "BLE_Rohdaten.csv")
    markers = marker_rows(ride / "Ereignisse.csv")
    samples = [sample for row in raw_rows if (sample := decode_1509(row)) is not None]
    samples.sort(key=lambda item: item.timestamp_ms)

    raw_by_channel: dict[str, list[tuple[int, str]]] = {"1502": [], "150C": []}
    for row in raw_rows:
        channel = str(row.get("channel") or "").strip().upper()
        if channel not in raw_by_channel:
            continue
        if str(row.get("origin") or "NOTIFICATION").strip().upper() == "READ":
            continue
        ts = as_int(row.get("timestamp_ms"))
        raw = str(row.get("hex") or "").strip()
        if ts is not None and raw:
            raw_by_channel[channel].append((ts, raw))
    for channel in raw_by_channel:
        raw_by_channel[channel].sort(key=lambda item: item[0])

    transitions = []
    for before, after in zip(samples, samples[1:]):
        gap_ms = after.timestamp_ms - before.timestamp_ms
        epoch_changed = (
            before.connection_epoch is not None
            and after.connection_epoch is not None
            and before.connection_epoch != after.connection_epoch
        )
        if gap_ms < MIN_GAP_MS and not epoch_changed:
            continue

        local_markers = markers_near_gap(markers, before.timestamp_ms, after.timestamp_ms)
        status, reasons = classify_gap(before, after, local_markers)
        transitions.append({
            "beforeTimestampMs": before.timestamp_ms,
            "afterTimestampMs": after.timestamp_ms,
            "gapDurationMs": gap_ms,
            "connectionEpochBefore": before.connection_epoch,
            "connectionEpochAfter": after.connection_epoch,
            "connectionEpochChanged": epoch_changed,
            "status": status,
            "reasons": reasons,
            "markers": local_markers,
            "before": {
                "socPercent": before.soc_percent,
                "voltageV": before.voltage_v,
                "currentA": before.current_a,
                "batteryTempC": before.battery_temp_c,
                "raw1509": before.raw_hex,
                "raw1502": nearest_raw(raw_by_channel["1502"], before.timestamp_ms),
                "raw150C": nearest_raw(raw_by_channel["150C"], before.timestamp_ms),
            },
            "after": {
                "socPercent": after.soc_percent,
                "voltageV": after.voltage_v,
                "currentA": after.current_a,
                "batteryTempC": after.battery_temp_c,
                "raw1509": after.raw_hex,
                "raw1502": nearest_raw(raw_by_channel["1502"], after.timestamp_ms),
                "raw150C": nearest_raw(raw_by_channel["150C"], after.timestamp_ms),
            },
            "delta": {
                "socPercent": None if before.soc_percent is None or after.soc_percent is None else after.soc_percent - before.soc_percent,
                "voltageV": finite_delta(after.voltage_v, before.voltage_v),
                "batteryTempC": finite_delta(after.battery_temp_c, before.battery_temp_c),
            },
            "note": "Der Zustand während der BLE-Lücke wurde nicht direkt beobachtet. Marker und Vorher/Nachher-Werte sind Evidenz, kein direkter Ladesensor.",
        })

    return {"ride": ride.name, "battery1509Samples": len(samples), "transitions": transitions}


def ride_dirs(root: Path) -> list[Path]:
    if not root.exists():
        return []
    return sorted(path for path in root.rglob("Messfahrt_*") if path.is_dir() and (path / "BLE_Rohdaten.csv").is_file())


def analyze(root: Path) -> dict:
    rides = [analyze_ride(ride) for ride in ride_dirs(root)]
    transitions = [{"ride": ride["ride"], **transition} for ride in rides for transition in ride["transitions"]]
    explicit = sum(1 for item in transitions if item["status"] == "EXPLICIT_CHARGE_GAP_OBSERVED")
    rises = sum(1 for item in transitions if item["status"] == "BATTERY_RISE_DURING_BLE_GAP")
    return {
        "schema": "vmax-charging-transition-scan-v1",
        "rideCount": len(rides),
        "transitionCount": len(transitions),
        "explicitChargeGapCount": explicit,
        "batteryRiseGapCount": rises,
        "transitions": transitions,
        "rideSummaries": rides,
        "evidenceRule": "BLE-Lücken werden nur als Vorher/Nachher-Übergang ausgewertet. Eine Lücke allein beweist keinen Ladevorgang.",
    }


def render_report(result: dict) -> str:
    lines = [
        "# Lade-/BLE-Übergangsanalyse",
        "",
        f"Fahrten: **{result['rideCount']}**",
        f"BLE-/1509-Übergänge: **{result['transitionCount']}**",
        f"Explizite Lade-Übergänge: **{result['explicitChargeGapCount']}**",
        f"Batterieanstieg ohne expliziten Marker: **{result['batteryRiseGapCount']}**",
        "",
        "> Während einer BLE-Lücke werden keine Werte erfunden. Es werden nur der letzte echte 1509-Zustand davor und der erste echte Zustand danach verglichen.",
        "",
    ]
    if not result["transitions"]:
        lines += [
            "Noch kein auswertbarer Übergang vorhanden. Ein Ladetest kann BLE komplett verlieren; das ist okay, solange die Messfahrt/Marker weiterlaufen und der Scooter danach wieder verbunden wird.",
            "",
        ]
        return "\n".join(lines)

    lines += ["| Fahrt | Gap | Status | SOC Δ | Volt Δ | Marker |", "|---|---:|---|---:|---:|---|"]
    for item in result["transitions"]:
        gap_s = round(item["gapDurationMs"] / 1000.0, 1)
        soc = item["delta"].get("socPercent")
        volt = item["delta"].get("voltageV")
        markers = " / ".join(item.get("markers") or []) or "–"
        lines.append(
            f"| {item['ride']} | {gap_s}s | {item['status']} | {soc if soc is not None else '–'} | "
            f"{volt if volt is not None else '–'} | {markers} |"
        )
    lines += ["", "## Evidenzregel", "", result["evidenceRule"], ""]
    return "\n".join(lines)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", default="fahrdaten")
    parser.add_argument("--output", default="decoder-ai/charging_transition_scan.json")
    parser.add_argument("--report", default="decoder-ai/charging_transition_scan.md")
    args = parser.parse_args()
    result = analyze(Path(args.root))
    output = Path(args.output)
    report = Path(args.report)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    report.write_text(render_report(result) + "\n", encoding="utf-8")
    print(
        f"Charging transition scan: {result['rideCount']} rides, "
        f"{result['transitionCount']} transitions, {result['explicitChargeGapCount']} explicit charge gap(s)"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

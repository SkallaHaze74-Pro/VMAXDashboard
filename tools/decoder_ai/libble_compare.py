#!/usr/bin/env python3
import argparse
import csv
import json
import math
import statistics
from collections import defaultdict
from pathlib import Path

from raw_origin_guard import is_accepted_live_notification

SDK_SOURCE = "libble-sdk-native-lib.so"

FIELD_LAYOUTS = {
    "1505": [
        ("powerA_W", 0, 2, "u16be", 0.1, None),
        ("powerB_W", 2, 2, "u16be", 0.1, None),
        ("torque_Nm", 4, 2, "u16be", 0.01, None),
        ("speed_kmh", 6, 2, "u16be", 0.1, "speed_kmh_candidate"),
        ("rpm", 8, 2, "u16be", 1.0, None),
        ("remaining_range_km", 10, 2, "u16be", 1.0, None),
    ],
    "1509": [
        ("current_A", 0, 2, "s16be", 0.001, "current_a"),
        ("battery_temp_C", 2, 2, "s16be", 0.1, "battery_temp_c"),
        ("soc_percent", 4, 1, "u8", 1.0, "battery_percent"),
        ("voltage_V", 5, 2, "u16be", 0.001, "voltage_v"),
        ("secondary_current_A", 7, 2, "s16be", 0.001, None),
        ("direct_power_W", 9, 2, "u16be", 1.0, "power_w"),
    ],
    "150A": [
        ("motor_current_A", 0, 2, "s16be", 0.001, None),
        ("motor_voltage_V", 2, 2, "u16be", 0.001, None),
        ("motor_rpm", 4, 2, "u16be", 1.0, None),
        ("motor_torque_Nm", 6, 2, "s16be", 0.01, None),
        ("motor_temp_C", 8, 2, "s16be", 0.1, "motor_temp_c"),
    ],
}

PLAUSIBLE = {
    "powerA_W": (-20000, 20000),
    "powerB_W": (-20000, 20000),
    "torque_Nm": (-500, 500),
    "speed_kmh": (0, 200),
    "rpm": (0, 50000),
    # Defensive UI/analysis ceiling. The native layout is unsigned 16-bit, but
    # a four-digit scooter range is not plausible and indicates a corrupt frame.
    "remaining_range_km": (0, 1000),
    "current_A": (-300, 300),
    "battery_temp_C": (-50, 150),
    "soc_percent": (0, 100),
    "voltage_V": (0, 100),
    "secondary_current_A": (-300, 300),
    "direct_power_W": (-30000, 30000),
    "motor_current_A": (-300, 300),
    "motor_voltage_V": (0, 100),
    "motor_rpm": (0, 50000),
    "motor_torque_Nm": (-500, 500),
    "motor_temp_C": (-50, 200),
}

TOLERANCE = {
    "speed_kmh": 0.11,
    "current_A": 0.02,
    "battery_temp_C": 0.11,
    "soc_percent": 0.1,
    "voltage_V": 0.002,
    "direct_power_W": 1.1,
    "motor_temp_C": 0.11,
}


def parse_hex(text: str) -> bytes:
    parts = [p.strip() for p in text.replace(":", "-").replace(" ", "-").split("-") if p.strip()]
    try:
        return bytes(int(p, 16) for p in parts)
    except ValueError:
        return b""


def suspicious_read_payload(channel: str, data: bytes) -> bool:
    if channel.upper() != "1505" or len(data) < 18:
        return False
    printable_tail = sum(0x20 <= value <= 0x7E for value in data[8:])
    return printable_tail >= 6


def raw_value(data: bytes, offset: int, width: int, encoding: str):
    if offset < 0 or offset + width > len(data):
        return None
    chunk = data[offset:offset + width]
    if not chunk or all(b == 0xFF for b in chunk):
        return None
    if width == 2 and chunk in (b"\xff\xff", b"\x80\x00"):
        return None
    if encoding == "u8":
        return chunk[0]
    if encoding == "u16be":
        return int.from_bytes(chunk, "big", signed=False)
    if encoding == "s16be":
        return int.from_bytes(chunk, "big", signed=True)
    raise ValueError(encoding)


def is_plausible(name: str, value: float) -> bool:
    lo, hi = PLAUSIBLE.get(name, (-math.inf, math.inf))
    return math.isfinite(value) and lo <= value <= hi


def read_csv(path: Path):
    if not path.is_file():
        return []
    with path.open("r", encoding="utf-8-sig", newline="") as handle:
        return list(csv.DictReader(handle, delimiter=";"))


def csv_fieldnames(path: Path):
    if not path.is_file():
        return set()
    with path.open("r", encoding="utf-8-sig", newline="") as handle:
        return set(csv.DictReader(handle, delimiter=";").fieldnames or [])


def read_summary_quality(path: Path):
    """Read recorder-side counters without inferring them from accepted RAW rows."""
    keys = {
        "BLE_Empfangen": "received_notification_packets",
        "BLE_Akzeptiert": "accepted_notification_packets",
        "READ_Verworfen": "rejected_read_packets",
        "Hybrid_Verworfen": "rejected_hybrid_packets",
    }
    values = {target: None for target in keys.values()}
    if not path.is_file():
        return {"source": "unknown", **values}

    try:
        lines = path.read_text(encoding="utf-8-sig").splitlines()
    except OSError:
        return {"source": "unknown", **values}

    observed = False
    for line in lines:
        key, separator, raw = line.partition(":")
        target = keys.get(key.strip())
        if not separator or target is None:
            continue
        try:
            values[target] = int(raw.strip())
            observed = True
        except ValueError:
            continue
    return {"source": "Zusammenfassung.txt" if observed else "unknown", **values}


def nearest_live(live_rows, timestamp_ms: int, source_channel: str, max_delta_ms: int = 200):
    if not live_rows:
        return None
    best = None
    best_delta = max_delta_ms + 1
    for row in live_rows:
        if str(row.get("source_channel") or "").upper() != source_channel.upper():
            continue
        try:
            ts = int(row.get("timestamp_ms") or 0)
        except ValueError:
            continue
        delta = abs(ts - timestamp_ms)
        if delta < best_delta:
            best = row
            best_delta = delta
    return best if best_delta <= max_delta_ms else None


def fnum(value):
    try:
        if value is None or str(value).strip() == "":
            return None
        return float(value)
    except (TypeError, ValueError):
        return None


def summarize(values):
    if not values:
        return {"samples": 0}
    return {
        "samples": len(values),
        "min": min(values),
        "max": max(values),
        "mean": statistics.fmean(values),
    }


def analyze_ride(ride: Path):
    raw_rows = read_csv(ride / "BLE_Rohdaten.csv")
    raw_columns = csv_fieldnames(ride / "BLE_Rohdaten.csv")
    live_rows = read_csv(ride / "Live_Telemetrie.csv")
    quality_counters = read_summary_quality(ride / "Zusammenfassung.txt")
    by_field = defaultdict(lambda: {"values": [], "matches": 0, "comparisons": 0, "abs_errors": []})
    channel_packets = defaultdict(int)
    channel_nonplaceholder = defaultdict(int)
    observed_exported_read_rows = 0
    observed_exported_hybrid_rows = 0
    observed_exported_quarantined_rows = 0

    for row in raw_rows:
        channel = str(row.get("channel") or "").upper()
        data = parse_hex(str(row.get("hex") or ""))
        origin = str(row.get("origin") or "").strip().upper()
        if not channel or not data:
            continue
        if not is_accepted_live_notification(row):
            if origin == "READ":
                observed_exported_read_rows += 1
            else:
                observed_exported_quarantined_rows += 1
            continue
        if suspicious_read_payload(channel, data):
            observed_exported_hybrid_rows += 1
            continue
        channel_packets[channel] += 1
        if not all(b == 0xFF for b in data):
            channel_nonplaceholder[channel] += 1
        layouts = FIELD_LAYOUTS.get(channel, [])
        try:
            timestamp_ms = int(row.get("timestamp_ms") or 0)
        except ValueError:
            timestamp_ms = 0
        live = nearest_live(live_rows, timestamp_ms, channel)
        for name, offset, width, encoding, scale, live_column in layouts:
            raw = raw_value(data, offset, width, encoding)
            if raw is None:
                continue
            value = raw * scale
            if not is_plausible(name, value):
                continue
            stat = by_field[f"{channel}.{name}"]
            stat["values"].append(value)
            if live_column and live:
                reference = fnum(live.get(live_column))
                if reference is not None and math.isfinite(reference):
                    error = abs(value - reference)
                    stat["comparisons"] += 1
                    stat["abs_errors"].append(error)
                    if error <= TOLERANCE.get(name, 0.01):
                        stat["matches"] += 1

    fields = {}
    for key, stat in sorted(by_field.items()):
        summary = summarize(stat["values"])
        comparisons = stat["comparisons"]
        layout_consistent = comparisons >= 10 and stat["matches"] / comparisons >= 0.95
        summary.update({
            "comparisons_to_app_live": comparisons,
            "match_percent": round(stat["matches"] * 100.0 / comparisons, 2) if comparisons else None,
            "mae_to_app_live": round(statistics.fmean(stat["abs_errors"]), 6) if stat["abs_errors"] else None,
            "evidence_type": "same_raw_app_export_layout_consistency" if comparisons else "sdk_layout_observation",
            "independent_semantic_confirmation": False,
            "sdk_status": "app_export_consistent_with_sdk_layout" if layout_consistent else (
                "observed" if summary.get("samples", 0) else "not_observed"
            ),
        })
        fields[key] = summary

    channels = {}
    for channel in sorted(channel_packets):
        total = channel_packets[channel]
        nonplaceholder = channel_nonplaceholder[channel]
        channels[channel] = {
            "packets": total,
            "non_placeholder_packets": nonplaceholder,
            "non_placeholder_percent": round(nonplaceholder * 100.0 / total, 2) if total else 0.0,
            "sdk_decoder": channel in FIELD_LAYOUTS,
            "sdk_name": {
                "1505": "BikePerformance",
                "1509": "BatteryChange/BatteryUpdate",
                "150A": "MotorUpdate",
                "150C": "BatteryCellUpdate",
            }.get(channel),
        }

    # 150C is deliberately presence-only until its exact per-build byte offsets are recovered again.
    if "150C" in channels:
        channels["150C"]["note"] = "libble names BatteryCellUpdate/cellIndex/cellVoltage/cellTemp/cellNum are known; exact BT638 byte offsets must be verified before auto-decoding."

    return {
        "ride": ride.name,
        "raw_export_rows": len(raw_rows),
        "accepted_export_rows": sum(channel_packets.values()),
        "export_observations": {
            "scope": "BLE_Rohdaten.csv rows only; these are not recorder rejection counters",
            "origin_column_available": "origin" in raw_columns,
            "observed_exported_read_rows": observed_exported_read_rows if "origin" in raw_columns else None,
            "observed_exported_quarantined_rows": observed_exported_quarantined_rows,
            "observed_exported_hybrid_rows": observed_exported_hybrid_rows,
        },
        "quality_counters": quality_counters,
        "live_rows": len(live_rows),
        "channels": channels,
        "fields": fields,
    }


def ride_dirs(root: Path):
    if not root.exists():
        return []
    return sorted(p for p in root.rglob("Messfahrt_*") if p.is_dir() and (p / "BLE_Rohdaten.csv").is_file())


def aggregate(rides):
    aggregate_fields = defaultdict(lambda: {"samples": 0, "comparisons": 0, "matches_weighted": 0.0, "mae_weighted": 0.0, "mae_weight": 0})
    for ride in rides:
        for key, field in ride["fields"].items():
            agg = aggregate_fields[key]
            agg["samples"] += int(field.get("samples") or 0)
            comps = int(field.get("comparisons_to_app_live") or 0)
            agg["comparisons"] += comps
            mp = field.get("match_percent")
            if comps and mp is not None:
                agg["matches_weighted"] += (mp / 100.0) * comps
            mae = field.get("mae_to_app_live")
            if comps and mae is not None:
                agg["mae_weighted"] += mae * comps
                agg["mae_weight"] += comps
    out = {}
    for key, agg in sorted(aggregate_fields.items()):
        comps = agg["comparisons"]
        match = agg["matches_weighted"] / comps if comps else None
        out[key] = {
            "samples": agg["samples"],
            "comparisons_to_app_live": comps,
            "match_percent": round(match * 100.0, 2) if match is not None else None,
            "mae_to_app_live": round(agg["mae_weighted"] / agg["mae_weight"], 6) if agg["mae_weight"] else None,
            "evidence_type": (
                "same_raw_app_export_layout_consistency" if comps else "sdk_layout_observation"
            ),
            "independent_semantic_confirmation": False,
            "verdict": "APP_EXPORT_CONSISTENT_WITH_SDK_LAYOUT" if comps >= 20 and match is not None and match >= 0.95 else (
                "OBSERVED_NEEDS_MORE_PROOF" if agg["samples"] else "NOT_OBSERVED"
            ),
        }
    return out


def write_report(payload, path: Path):
    path.parent.mkdir(parents=True, exist_ok=True)
    lines = [
        "# VMAX libble Ground-Truth Vergleich",
        "",
        f"Quelle der Semantik: `{SDK_SOURCE}`",
        f"Ausgewertete Messfahrten: **{len(payload['rides'])}**",
        "",
        "## SDK-Felder gegen echten BT638-Livestand",
        "",
        "| Feld | Samples | Vergleiche | Treffer | MAE | Urteil |",
        "|---|---:|---:|---:|---:|---|",
    ]
    for key, item in payload["aggregate_fields"].items():
        hit = "–" if item["match_percent"] is None else f"{item['match_percent']:.2f}%"
        mae = "–" if item["mae_to_app_live"] is None else f"{item['mae_to_app_live']:.6f}"
        lines.append(f"| {key} | {item['samples']} | {item['comparisons_to_app_live']} | {hit} | {mae} | {item['verdict']} |")
    lines += [
        "",
        "## Datenqualität je Export",
        "",
        "| Messfahrt | RAW-Exportzeilen | Akzeptierte Exportzeilen | READ im Export | Hybrid im Export | READ laut Zusammenfassung | Hybrid laut Zusammenfassung |",
        "|---|---:|---:|---:|---:|---:|---:|",
    ]
    for ride in payload["rides"]:
        exported = ride["export_observations"]
        quality = ride["quality_counters"]
        exported_read = exported["observed_exported_read_rows"]
        summary_read = quality["rejected_read_packets"]
        summary_hybrid = quality["rejected_hybrid_packets"]
        lines.append(
            f"| {ride['ride']} | {ride['raw_export_rows']} | {ride['accepted_export_rows']} | "
            f"{'?' if exported_read is None else exported_read} | {exported['observed_exported_hybrid_rows']} | "
            f"{'?' if summary_read is None else summary_read} | {'?' if summary_hybrid is None else summary_hybrid} |"
        )
    lines += [
        "",
        "## Schutzregel",
        "",
        "SDK-bekannte Layouts dürfen nicht als Licht/Bremse/Blinker-Kandidaten umgedeutet werden.",
        "Der Vergleich prüft die App-Extraktion gegen dasselbe RAW-Paket; er ist kein unabhängiger semantischer Sensornachweis.",
        "READ-/Hybrid-Verwerfungen stammen nur aus `Zusammenfassung.txt`. Fehlt dort der jeweilige Zähler, bleibt er unbekannt; eine Null wird nicht aus den bereits akzeptierten RAW-Exportzeilen abgeleitet.",
        "Unbekannte Felder werden erst danach statistisch gelernt. 150C wird bis zur erneuten Bestätigung der exakten Byte-Offets nur als BatteryCellUpdate-Präsenz ausgewertet.",
    ]
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", default="fahrdaten")
    parser.add_argument("--output", default="decoder-ai/libble_comparison.json")
    parser.add_argument("--report", default="decoder-ai/libble_comparison.md")
    args = parser.parse_args()
    rides = [analyze_ride(p) for p in ride_dirs(Path(args.root))]
    payload = {
        "schema": "vmax-libble-layout-consistency-v2",
        "sdk_source": SDK_SOURCE,
        "rides": rides,
        "aggregate_fields": aggregate(rides),
    }
    out = Path(args.output)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    write_report(payload, Path(args.report))
    print(json.dumps({"rides": len(rides), "fields": len(payload["aggregate_fields"])}, ensure_ascii=False))


if __name__ == "__main__":
    main()

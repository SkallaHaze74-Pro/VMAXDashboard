#!/usr/bin/env python3
from __future__ import annotations

import argparse
import csv
import hashlib
import json
import math
import time
from collections import defaultdict
from dataclasses import dataclass
from pathlib import Path
from typing import Optional

PROFILE_SCHEMA = "vmax-adaptive-decoder-v1"

TARGETS = {
    "speedKmh": {"column": "speed_kmh_candidate", "min_span": 3.0, "max_mae": 0.6, "range": (0.0, 100.0)},
    "batteryPercent": {"column": "battery_percent", "min_span": 5.0, "max_mae": 1.5, "range": (0.0, 100.0)},
    "voltageV": {"column": "voltage_v", "min_span": 0.25, "max_mae": 0.25, "range": (0.0, 100.0)},
    "currentA": {"column": "current_a", "min_span": 0.5, "max_mae": 0.3, "range": (-200.0, 200.0)},
    "powerW": {"column": "power_w", "min_span": 40.0, "max_mae": 25.0, "range": (-30000.0, 30000.0)},
    "motorTemperatureC": {"column": "motor_temp_c", "min_span": 2.0, "max_mae": 1.5, "range": (-50.0, 220.0)},
    "batteryTemperatureC": {"column": "battery_temp_c", "min_span": 2.0, "max_mae": 1.5, "range": (-50.0, 150.0)},
    "tripDistanceKm": {"column": "trip_km", "min_span": 0.15, "max_mae": 0.12, "range": (0.0, 10000000.0)},
    "odometerKm": {"column": "odometer_km", "min_span": 0.3, "max_mae": 0.2, "range": (0.0, 10000000.0)},
}

ENCODINGS = (("u8", 1), ("u16be", 2), ("u16le", 2), ("s16be", 2), ("s16le", 2), ("u32be", 4), ("u32le", 4))
ENCODING_WIDTH = dict(ENCODINGS)

# Ground truth recovered from Original APK + libble-sdk-native-lib.so and verified on BT638.
# Statistical learning may validate these mappings, but may not silently replace their
# signedness, width or offset with an equivalent-looking shorter representation.
SDK_CANONICAL = {
    ("speedKmh", "1505"): (6, "u16be"),
    ("batteryPercent", "1509"): (4, "u8"),
    ("voltageV", "1509"): (5, "u16be"),
    ("currentA", "1509"): (0, "s16be"),
    ("powerW", "1509"): (9, "u16be"),
    ("odometerKm", "1506"): (0, "u32be"),
}

# First real ride disproved the old interpretation of 150D/0 as a second live speed.
# It behaves like a statistic/limit value and is therefore excluded from speed learning.
FORBIDDEN_NUMERIC = {("speedKmh", "150D")}
BLOCKED_DISCRETE_CHANNELS = {
    "1505", "1506", "1509", "150A", "150C", "150D",
    "2A00", "2A01", "2A02", "2A04", "2A05", "2A28",
}

REFERENCE_SOURCE_CHANNEL = {
    "speedKmh": "1505",
    "batteryPercent": "1509",
    "voltageV": "1509",
    "currentA": "1509",
    "powerW": "1509",
    "motorTemperatureC": "150A",
    "batteryTemperatureC": "1509",
    "odometerKm": "1506",
}


@dataclass(frozen=True)
class NumericEvidence:
    ride: str
    signal: str
    channel: str
    offset: int
    encoding: str
    width: int
    samples: int
    corr: float
    slope: float
    bias: float
    mae: float
    confidence: int


def load_json(path: Path) -> dict:
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return {}


def as_float(value: object) -> Optional[float]:
    if value is None or value == "":
        return None
    try:
        out = float(value)
    except (TypeError, ValueError):
        return None
    return out if math.isfinite(out) else None


def hex_bytes(text: str) -> bytes:
    out = []
    for part in text.replace(":", "-").replace(" ", "-").split("-"):
        part = part.strip()
        if len(part) != 2:
            continue
        try:
            out.append(int(part, 16))
        except ValueError:
            return b""
    return bytes(out)


def suspicious_read_payload(channel: str, data: bytes) -> bool:
    if channel.upper() != "1505" or len(data) < 18:
        return False
    printable_tail = sum(0x20 <= value <= 0x7E for value in data[8:])
    return printable_tail >= 6


def candidate_allowed(signal: str, channel: str, offset: int, encoding: str) -> bool:
    channel = channel.upper()
    if (signal, channel) in FORBIDDEN_NUMERIC:
        return False
    canonical_channel = next((known_channel for known_signal, known_channel in SDK_CANONICAL if known_signal == signal), None)
    if canonical_channel is not None and channel != canonical_channel:
        return False
    canonical = SDK_CANONICAL.get((signal, channel))
    if canonical is not None:
        return canonical == (offset, encoding)
    return True


def discrete_candidate_allowed(signal: str, channel: str, offset: int) -> bool:
    channel = channel.upper()
    if offset < 0 or channel in BLOCKED_DISCRETE_CHANNELS:
        return False
    if channel == "1508":
        return signal == "lightOn" and offset == 0
    return True


def encoding_preference(signal: str, channel: str, offset: int, encoding: str) -> int:
    canonical = SDK_CANONICAL.get((signal, channel.upper()))
    if canonical == (offset, encoding):
        return -100
    order = (
        ("u8", "u16be", "s16be", "u32be", "u16le", "s16le", "u32le")
        if signal == "batteryPercent"
        else ("u16be", "s16be", "u32be", "u16le", "s16le", "u32le", "u8")
    )
    try:
        return order.index(encoding)
    except ValueError:
        return 999


def read_raw_rows(path: Path) -> list[dict]:
    if not path.is_file():
        return []
    rows = []
    with path.open("r", encoding="utf-8-sig", newline="") as handle:
        for row in csv.DictReader(handle, delimiter=";"):
            channel = (row.get("channel") or "").strip().upper()
            raw = hex_bytes(row.get("hex") or "")
            origin = (row.get("origin") or "NOTIFICATION").strip().upper()
            try:
                rel_ms = int(row.get("relative_ms") or "")
            except ValueError:
                continue
            if channel and raw and origin != "READ" and not suspicious_read_payload(channel, raw):
                rows.append({"relative_ms": rel_ms, "channel": channel, "bytes": raw})
    return rows


def read_live_lookup(path: Path) -> dict[tuple[int, str], dict[str, float]]:
    if not path.is_file():
        return {}
    out = {}
    with path.open("r", encoding="utf-8-sig", newline="") as handle:
        for row in csv.DictReader(handle, delimiter=";"):
            try:
                rel_ms = int(row.get("relative_ms") or "")
            except ValueError:
                continue
            channel = (row.get("source_channel") or "").strip().upper()
            if not channel:
                continue
            values = {}
            for signal, cfg in TARGETS.items():
                expected_channel = REFERENCE_SOURCE_CHANNEL.get(signal)
                # Signals without a confirmed source (currently tripDistanceKm)
                # are not safe references in a carry-forward snapshot CSV.
                if expected_channel is None or channel != expected_channel:
                    continue
                val = as_float(row.get(cfg["column"]))
                if val is None:
                    continue
                low, high = cfg["range"]
                if low <= val <= high:
                    values[signal] = val
            if values:
                out[(rel_ms, channel)] = values
    return out


def decode_raw(data: bytes, offset: int, encoding: str) -> Optional[float]:
    width = ENCODING_WIDTH[encoding]
    if offset < 0 or offset + width > len(data):
        return None
    part = data[offset:offset + width]
    if all(b == 0xFF for b in part):
        return None
    if width == 2:
        sentinel = int.from_bytes(part, "big" if encoding.endswith("be") else "little", signed=False)
        if sentinel in (0x8000, 0xFFFF):
            return None
    if encoding == "u8":
        return float(part[0])
    byteorder = "big" if encoding.endswith("be") else "little"
    signed = encoding.startswith("s")
    return float(int.from_bytes(part, byteorder, signed=signed))


def fit_linear(xs: list[float], ys: list[float]) -> Optional[tuple[float, float, float, float]]:
    if len(xs) < 2 or len(xs) != len(ys):
        return None
    mx, my = sum(xs) / len(xs), sum(ys) / len(ys)
    dx, dy = [x - mx for x in xs], [y - my for y in ys]
    varx, vary = sum(v * v for v in dx), sum(v * v for v in dy)
    if varx <= 1e-12 or vary <= 1e-12:
        return None
    cov = sum(a * b for a, b in zip(dx, dy))
    slope = cov / varx
    bias = my - slope * mx
    corr = cov / math.sqrt(varx * vary)
    mae = sum(abs((slope * x + bias) - y) for x, y in zip(xs, ys)) / len(xs)
    return corr, slope, bias, mae


def numeric_confidence(corr: float, mae: float, max_mae: float, samples: int) -> int:
    corr_score = max(0.0, min(1.0, (abs(corr) - 0.965) / 0.035))
    err_score = max(0.0, 1.0 - (mae / max(max_mae, 1e-9)))
    sample_score = min(1.0, math.log10(max(samples, 10)) / 3.0)
    return int(round(72 + 16 * corr_score + 7 * err_score + 4 * sample_score))


def discover_numeric_evidence(ride_dir: Path, max_rows_per_channel: int = 6000) -> list[NumericEvidence]:
    raw_rows = read_raw_rows(ride_dir / "BLE_Rohdaten.csv")
    live = read_live_lookup(ride_dir / "Live_Telemetrie.csv")
    if not raw_rows or not live:
        return []
    by_channel = defaultdict(list)
    for row in raw_rows:
        values = live.get((row["relative_ms"], row["channel"]))
        if values:
            by_channel[row["channel"]].append((row["bytes"], values))

    evidence = []
    for channel, pairs in by_channel.items():
        if len(pairs) > max_rows_per_channel:
            step = max(1, len(pairs) // max_rows_per_channel)
            pairs = pairs[::step][:max_rows_per_channel]
        max_len = max((len(raw) for raw, _ in pairs), default=0)
        for encoding, width in ENCODINGS:
            if width > max_len:
                continue
            for offset in range(max_len - width + 1):
                for signal, cfg in TARGETS.items():
                    if not candidate_allowed(signal, channel, offset, encoding):
                        continue
                    xs, ys = [], []
                    for raw, refs in pairs:
                        y = refs.get(signal)
                        if y is None:
                            continue
                        x = decode_raw(raw, offset, encoding)
                        if x is None:
                            continue
                        xs.append(x)
                        ys.append(y)
                    if len(xs) < 30 or len(set(xs)) < 7 or len(set(round(y, 4) for y in ys)) < 7:
                        continue
                    if max(ys) - min(ys) < cfg["min_span"]:
                        continue
                    fitted = fit_linear(xs, ys)
                    if fitted is None:
                        continue
                    corr, slope, bias, mae = fitted
                    if abs(corr) < 0.985 or mae > cfg["max_mae"] or abs(slope) < 1e-12:
                        continue
                    evidence.append(NumericEvidence(
                        ride_dir.name, signal, channel, offset, encoding, width, len(xs), corr, slope, bias, mae,
                        min(numeric_confidence(corr, mae, cfg["max_mae"], len(xs)), 99)
                    ))
    return evidence


def normalized_label(label: str) -> str:
    return " ".join(label.lower().replace("_", " ").split())


def discrete_signal_and_orientation(label: str, before: int, after: int) -> Optional[tuple[str, int, int]]:
    text = normalized_label(label)
    signal = None
    reverse = False
    if "blinker links" in text:
        signal = "leftIndicator"
    elif "blinker rechts" in text:
        signal = "rightIndicator"
    elif "bremse" in text:
        signal = "brakeActive"
    elif "licht" in text:
        signal = "lightOn"
        reverse = "aus" in text and "an" not in text
    elif "ladegerät" in text or "laden" in text or "charging" in text:
        signal = "charging"
        reverse = "abziehen" in text or "aus" in text
    elif "sperr" in text or "lock" in text:
        signal = "lockActive"
        reverse = "aus" in text or "entsperr" in text or "unlock" in text
    if signal is None:
        return None
    active = before if reverse else after
    inactive = after if reverse else before
    return None if active == inactive else (signal, active, inactive)


def collect_discrete_rules(ride_dirs: list[Path]) -> list[dict]:
    snapshots = []
    for ride in ride_dirs:
        root = load_json(ride / "Lernprofil.json")
        updated = int(root.get("updatedAt") or 0)
        candidates = root.get("candidates") if isinstance(root.get("candidates"), list) else []
        snapshots.append((updated, ride.name, [c for c in candidates if isinstance(c, dict)]))
    snapshots.sort(key=lambda item: (item[0], item[1]))

    previous_obs = {}
    grouped = defaultdict(list)
    for updated, ride_name, candidates in snapshots:
        for item in candidates:
            key = str(item.get("key") or f"{item.get('label')}|{item.get('channel')}|{item.get('byteIndex')}")
            obs = int(item.get("observations") or 0)
            increment = max(0, obs - previous_obs.get(key, 0))
            previous_obs[key] = max(previous_obs.get(key, 0), obs)
            if increment <= 0:
                continue
            try:
                channel = str(item.get("channel") or "").upper()
                offset = int(item.get("byteIndex"))
                before = int(item.get("lastBefore"))
                after = int(item.get("lastAfter"))
                confidence = int(item.get("confidence") or 0)
            except (TypeError, ValueError):
                continue
            label = str(item.get("label") or "")
            orientation = discrete_signal_and_orientation(label, before, after)
            if not channel or offset < 0 or orientation is None:
                continue
            signal, active, inactive = orientation
            if not discrete_candidate_allowed(signal, channel, offset):
                continue
            grouped[(signal, channel, offset)].append({
                "ride": ride_name, "increment": increment, "confidence": confidence,
                "active": active, "inactive": inactive, "label": label,
            })

    rules = []
    for (signal, channel, offset), items in grouped.items():
        pair_weight = defaultdict(int)
        total_weight = weighted_conf = 0
        rides, labels = set(), set()
        for item in items:
            weight = max(1, int(item["increment"]))
            pair_weight[(item["active"], item["inactive"])] += weight
            total_weight += weight
            weighted_conf += item["confidence"] * weight
            rides.add(item["ride"])
            labels.add(item["label"])
        if total_weight <= 0:
            continue
        (active, inactive), winning = max(pair_weight.items(), key=lambda kv: kv[1])
        consistency = winning / total_weight
        avg_conf = weighted_conf / total_weight
        confidence = int(round(min(99, avg_conf * 0.67 + consistency * 24 + min(8, len(rides) * 3))))
        status = "confirmed" if winning >= 2 and len(rides) >= 2 and confidence >= 92 and consistency >= 0.80 else "candidate"
        rules.append({
            "id": f"{signal}:{channel}:{offset}:u8", "signal": signal, "label": " / ".join(sorted(labels)),
            "channel": channel, "offset": offset, "width": 1, "encoding": "u8", "activeValue": active,
            "inactiveValue": inactive, "confidence": confidence, "observations": total_weight, "rides": len(rides),
            "consistency": round(consistency, 4), "source": "marker-consensus", "status": status,
        })
    return rules


def aggregate_numeric(evidence: list[NumericEvidence]) -> list[dict]:
    grouped = defaultdict(list)
    for item in evidence:
        grouped[(item.signal, item.channel, item.offset, item.encoding)].append(item)

    provisional = []
    for (signal, channel, offset, encoding), items in grouped.items():
        rides = {i.ride for i in items}
        weights = [i.samples for i in items]
        total = sum(weights)
        if total < 30:
            continue
        slope = sum(i.slope * w for i, w in zip(items, weights)) / total
        bias = sum(i.bias * w for i, w in zip(items, weights)) / total
        corr = sum(abs(i.corr) * w for i, w in zip(items, weights)) / total
        mae = sum(i.mae * w for i, w in zip(items, weights)) / total
        avg_conf = sum(i.confidence * w for i, w in zip(items, weights)) / total
        slope_spread = max(abs(i.slope - slope) for i in items) / max(abs(slope), 1e-9)
        confidence = int(round(min(99, avg_conf + min(4, max(0, len(rides) - 1) * 2) - min(12, slope_spread * 40))))
        status = "confirmed" if len(rides) >= 2 and confidence >= 94 and slope_spread <= 0.08 else "candidate"
        provisional.append({
            "id": f"{signal}:{channel}:{offset}:{encoding}", "signal": signal, "channel": channel,
            "offset": offset, "width": ENCODING_WIDTH[encoding], "encoding": encoding,
            "scale": round(slope, 12), "bias": round(bias, 8), "confidence": confidence,
            "observations": total, "rides": len(rides), "correlation": round(corr, 6), "mae": round(mae, 6),
            "source": "original-sdk-layout+app-extraction-check" if (signal, channel) in SDK_CANONICAL else "numeric-correlation",
            "status": status,
        })

    best = {}
    for rule in provisional:
        key = (rule["signal"], rule["channel"])
        score = (
            1 if rule["status"] == "confirmed" else 0,
            rule["confidence"], rule["rides"], rule["observations"], -rule["mae"],
            -encoding_preference(rule["signal"], rule["channel"], rule["offset"], rule["encoding"]),
        )
        current = best.get(key)
        if current is None:
            best[key] = rule
            continue
        current_score = (
            1 if current["status"] == "confirmed" else 0,
            current["confidence"], current["rides"], current["observations"], -current["mae"],
            -encoding_preference(current["signal"], current["channel"], current["offset"], current["encoding"]),
        )
        if score > current_score:
            best[key] = rule
    return list(best.values())


def ride_dirs(root: Path) -> list[Path]:
    if not root.exists():
        return []
    return sorted(path for path in root.rglob("Messfahrt_*") if path.is_dir() and (path / "manifest.json").is_file())


def analyze(root: Path) -> dict:
    rides = ride_dirs(root)
    numeric_evidence = []
    for ride in rides:
        numeric_evidence.extend(discover_numeric_evidence(ride))
    rules = collect_discrete_rules(rides) + aggregate_numeric(numeric_evidence)
    rules.sort(key=lambda r: (0 if r["status"] == "confirmed" else 1, -int(r["confidence"]), r["signal"], r["channel"], int(r["offset"])))
    canonical = json.dumps(rules, sort_keys=True, separators=(",", ":")).encode("utf-8")
    revision = hashlib.sha256(canonical).hexdigest()[:16]
    confirmed = sum(1 for r in rules if r["status"] == "confirmed")
    generated_at = 0
    for ride in rides:
        manifest = load_json(ride / "manifest.json")
        generated_at = max(generated_at, int(manifest.get("created_at_ms") or manifest.get("end_ms") or 0))
    if generated_at <= 0 and rides:
        generated_at = int(time.time() * 1000)
    return {
        "schema": PROFILE_SCHEMA, "revision": revision, "generatedAtMs": generated_at,
        "rideCount": len(rides), "ruleCount": len(rules), "confirmedRuleCount": confirmed, "rules": rules,
    }


def render_report(profile: dict) -> str:
    lines = [
        "# VMAX Decoder AI – Konsensbericht", "",
        f"- Fahrten ausgewertet: **{profile['rideCount']}**",
        f"- Regeln gesamt: **{profile['ruleCount']}**",
        f"- Davon bestätigt: **{profile['confirmedRuleCount']}**",
        f"- Profil-Revision: `{profile['revision']}`", "", "## Regeln", "",
    ]
    if not profile["rules"]:
        lines.append("Noch keine belastbaren Regeln. Nach der ersten vollständig hochgeladenen Messfahrt startet die Auswertung automatisch.")
    else:
        lines += ["| Status | Signal | Kanal | Feld | Konfidenz | Evidenz | Quelle |", "|---|---|---:|---|---:|---:|---|"]
        for rule in profile["rules"]:
            evidence = f"{rule.get('rides', 0)} Fahrt(en), {rule.get('observations', 0)} Samples"
            lines.append(
                f"| {rule['status']} | {rule['signal']} | {rule['channel']} | {rule['encoding']}@{rule['offset']} | "
                f"{rule['confidence']}% | {evidence} | {rule['source']} |"
            )
    lines += [
        "", "## Ground-Truth-Regeln", "",
        "1505/6 u16be = Geschwindigkeit; 1509/0 s16be = Strom; 1509/4 u8 = SOC; 1509/5 u16be = Spannung; 1509/9 u16be = direkte Leistung; 1506/0 u32be = Kilometerstand.",
        "Die Prozentwerte prüfen die konsistente App-Extraktion derselben RAW-Pakete; sie sind kein unabhängiger semantischer Sensorvergleich.",
        "150D wird nach der ersten echten Fahrt nicht mehr als Live-Geschwindigkeit gelernt.",
        "", "## Sicherheitsregel", "",
        "Dieses Profil enthält ausschließlich **Lese-/Decoderregeln**. Es erzeugt keine BLE-Schreibbefehle und verändert keine Motorparameter.", "",
    ]
    return "\n".join(lines)


def main(argv: Optional[list[str]] = None) -> int:
    parser = argparse.ArgumentParser(description="Build a cross-ride VMAX adaptive decoder profile.")
    parser.add_argument("--root", default="fahrdaten")
    parser.add_argument("--output", default="decoder-ai/decoder_profile.json")
    parser.add_argument("--report", default="decoder-ai/analysis_report.md")
    args = parser.parse_args(argv)
    profile = analyze(Path(args.root))
    output, report = Path(args.output), Path(args.report)
    output.parent.mkdir(parents=True, exist_ok=True)
    report.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(profile, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    report.write_text(render_report(profile) + "\n", encoding="utf-8")
    print(f"Decoder AI: {profile['rideCount']} rides, {profile['ruleCount']} rules, {profile['confirmedRuleCount']} confirmed, revision {profile['revision']}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

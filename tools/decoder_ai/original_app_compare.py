#!/usr/bin/env python3
import argparse
import json
from pathlib import Path

FIELD_KEY_BY_MAPPING = {
    ("1505", 6): "1505.speed_kmh",
    ("1505", 8): "1505.rpm",
    ("1509", 0): "1509.current_A",
    ("1509", 4): "1509.soc_percent",
    ("1509", 5): "1509.voltage_V",
}


def load(path: Path):
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return {}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--semantics", default="tools/decoder_ai/original_app_semantics.json")
    parser.add_argument("--libble", default="decoder-ai/libble_comparison.json")
    parser.add_argument("--output", default="decoder-ai/original_app_comparison.json")
    parser.add_argument("--report", default="decoder-ai/original_app_comparison.md")
    args = parser.parse_args()

    semantics = load(Path(args.semantics))
    libble = load(Path(args.libble))
    evidence = libble.get("aggregate_fields") if isinstance(libble.get("aggregate_fields"), dict) else {}

    rows = []
    unresolved = []
    for signal in semantics.get("signals", []):
        if not isinstance(signal, dict):
            continue
        mapping = signal.get("mapping") if isinstance(signal.get("mapping"), dict) else None
        candidates = signal.get("mappingCandidates") if isinstance(signal.get("mappingCandidates"), list) else []
        evidence_key = None
        evidence_row = None
        if mapping:
            channel = str(mapping.get("channel") or "").upper()
            try:
                offset = int(mapping.get("offset"))
            except (TypeError, ValueError):
                offset = -1
            evidence_key = FIELD_KEY_BY_MAPPING.get((channel, offset))
            if evidence_key:
                evidence_row = evidence.get(evidence_key)

        status = str(signal.get("status") or "")
        if mapping and isinstance(evidence_row, dict) and evidence_row.get("verdict") == "BT638_CONFIRMED":
            runtime_status = "BT638_CONFIRMED_BY_LIBBLE_AND_LIVE"
        elif mapping:
            runtime_status = "MAPPED_VERIFY_WITH_MORE_BT638_DATA"
        elif candidates:
            runtime_status = "ROLE_ASSIGNMENT_REQUIRED"
        else:
            runtime_status = "TARGETED_MAPPING_REQUIRED"
            unresolved.append({
                "signal": signal.get("signal"),
                "callback": signal.get("callback"),
                "originalCommand": signal.get("originalCommand"),
                "unit": signal.get("unit"),
            })

        rows.append({
            "signal": signal.get("signal"),
            "callback": signal.get("callback"),
            "originalCommand": signal.get("originalCommand"),
            "unit": signal.get("unit"),
            "declaredStatus": status,
            "runtimeStatus": runtime_status,
            "mapping": mapping,
            "mappingCandidates": candidates,
            "libbleEvidenceKey": evidence_key,
            "libbleEvidence": evidence_row,
        })

    payload = {
        "schema": "vmax-original-app-vs-bt638-v1",
        "source": semantics.get("source", {}),
        "signals": rows,
        "unresolvedTargets": unresolved,
        "summary": {
            "signals": len(rows),
            "confirmedByLiveEvidence": sum(1 for r in rows if r["runtimeStatus"] == "BT638_CONFIRMED_BY_LIBBLE_AND_LIVE"),
            "mappedNeedMoreData": sum(1 for r in rows if r["runtimeStatus"] == "MAPPED_VERIFY_WITH_MORE_BT638_DATA"),
            "roleAssignmentsOpen": sum(1 for r in rows if r["runtimeStatus"] == "ROLE_ASSIGNMENT_REQUIRED"),
            "targetedMappingsOpen": len(unresolved),
        },
    }

    out = Path(args.output)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    lines = [
        "# Original VMAX-App ↔ BT638 Echtzeit-Abgleich",
        "",
        "Die Original-App-Callbacks definieren die Soll-Semantik. libble definiert bekannte Parserfelder. BT638-Fahrdaten entscheiden, ob die Zuordnung auf diesem Modell wirklich gilt.",
        "",
        "| Original-Livewert | Callback | Mapping | Status |",
        "|---|---|---|---|",
    ]
    for row in rows:
        mapping = row.get("mapping")
        if mapping:
            mapping_text = f"{mapping.get('channel')} @ {mapping.get('offset')} {mapping.get('encoding')}"
        elif row.get("mappingCandidates"):
            mapping_text = "A/B-Rolle noch offen"
        else:
            mapping_text = "gezielt suchen"
        lines.append(f"| {row.get('signal')} | {row.get('callback')} | {mapping_text} | {row.get('runtimeStatus')} |")

    lines += ["", "## Noch gezielt zuzuordnen", ""]
    if unresolved:
        for item in unresolved:
            lines.append(f"- **{item.get('signal')}** — {item.get('callback')} ({item.get('unit')})")
    else:
        lines.append("- Keine offenen Original-App-Zielwerte.")
    Path(args.report).write_text("\n".join(lines) + "\n", encoding="utf-8")

    print(json.dumps(payload["summary"], ensure_ascii=False))


if __name__ == "__main__":
    main()

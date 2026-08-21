#!/usr/bin/env python3
import argparse
import json
from pathlib import Path

FIELD_KEY_BY_MAPPING = {
    ("1505", 6): "1505.speed_kmh",
    ("1505", 8): "1505.rpm",
    ("1505", 10): "1505.remaining_range_km",
    ("1509", 0): "1509.current_A",
    ("1509", 4): "1509.soc_percent",
    ("1509", 5): "1509.voltage_V",
}

LAYOUT_CONSISTENCY_VERDICT = "APP_EXPORT_CONSISTENT_WITH_SDK_LAYOUT"
INDEPENDENT_CONFIRMATION_VERDICT = "BT638_CONFIRMED"


def load(path: Path):
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return {}


def normalized_source(semantics):
    legacy = semantics.get("source") if isinstance(semantics.get("source"), dict) else {}
    return {
        "hardwareModel": "VMAX New VX2 Gear",
        "hardwareControllerBranding": "VMAX V-Core Gear (official product naming)",
        "liveDevice": "BT638",
        "liveProtocolEvidence": "BT638/GPST-DA1A GATT observations and native GPSTProtocolHandler parser",
        "bundledHyenaSdk": "io.hylink.hbp / io.hylink.hap and HyenaSDKManager are present in base.apk, but use by this BT638 is not proven",
        "otherBundledVendorSdks": "Brose, Hobbywing and additional multi-vendor code are also present in base.apk",
        "legacyMetadata": legacy,
        "policy": "Do not label DA1A/15xx or the VX2 Gear hardware as Hyena without direct runtime/model evidence.",
    }


def build_comparison(semantics, libble):
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
        evidence_verdict = evidence_row.get("verdict") if isinstance(evidence_row, dict) else None
        independent_confirmation = bool(
            isinstance(evidence_row, dict)
            and evidence_row.get("independent_semantic_confirmation") is True
            and evidence_verdict == INDEPENDENT_CONFIRMATION_VERDICT
        )
        if mapping and independent_confirmation:
            runtime_status = "BT638_CONFIRMED_BY_LIBBLE_AND_LIVE"
        elif mapping and evidence_verdict == LAYOUT_CONSISTENCY_VERDICT:
            runtime_status = "APP_EXPORT_LAYOUT_CONSISTENT_NON_INDEPENDENT"
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
            "declaredEvidence": signal.get("evidence"),
            "runtimeStatus": runtime_status,
            "mapping": mapping,
            "mappingCandidates": candidates,
            "libbleEvidenceKey": evidence_key,
            "libbleEvidence": evidence_row,
            "evidenceAssessment": {
                "verdict": evidence_verdict,
                "type": evidence_row.get("evidence_type") if isinstance(evidence_row, dict) else None,
                "independentSemanticConfirmation": independent_confirmation,
            },
        })

    return {
        "schema": "vmax-original-app-vs-bt638-v3",
        "source": normalized_source(semantics),
        "signals": rows,
        "unresolvedTargets": unresolved,
        "summary": {
            "signals": len(rows),
            "confirmedByLiveEvidence": sum(1 for r in rows if r["runtimeStatus"] == "BT638_CONFIRMED_BY_LIBBLE_AND_LIVE"),
            "layoutConsistentWithAppExportOnly": sum(1 for r in rows if r["runtimeStatus"] == "APP_EXPORT_LAYOUT_CONSISTENT_NON_INDEPENDENT"),
            "mappedNeedMoreData": sum(1 for r in rows if r["runtimeStatus"] == "MAPPED_VERIFY_WITH_MORE_BT638_DATA"),
            "roleAssignmentsOpen": sum(1 for r in rows if r["runtimeStatus"] == "ROLE_ASSIGNMENT_REQUIRED"),
            "targetedMappingsOpen": len(unresolved),
        },
    }


def write_report(payload, path: Path):
    rows = payload["signals"]
    unresolved = payload["unresolvedTargets"]
    lines = [
        "# Original VMAX-App ↔ BT638 Echtzeit-Abgleich",
        "",
        "Quellen werden getrennt: VMAX/V-Core beschreibt die konkrete VX2-Gear-Hardware; BT638/GPST-DA1A beschreibt die live beobachtete BLE-/Native-Evidenz; Hyena/Hylink, Brose und Hobbywing sind zunächst nur in der Multi-Vendor-APK gebündelte SDK-Pfade.",
        "Ein SDK-/Klassenfund ist kein BT638-Nachweis. Ein Treffer `APP_EXPORT_CONSISTENT_WITH_SDK_LAYOUT` vergleicht zwei Extraktionen desselben RAW-Pakets und ist ebenfalls kein unabhängiger Live-Sensornachweis.",
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
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--semantics", default="tools/decoder_ai/original_app_semantics.json")
    parser.add_argument("--libble", default="decoder-ai/libble_comparison.json")
    parser.add_argument("--output", default="decoder-ai/original_app_comparison.json")
    parser.add_argument("--report", default="decoder-ai/original_app_comparison.md")
    args = parser.parse_args()

    payload = build_comparison(load(Path(args.semantics)), load(Path(args.libble)))
    out = Path(args.output)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    write_report(payload, Path(args.report))

    print(json.dumps(payload["summary"], ensure_ascii=False))


if __name__ == "__main__":
    main()

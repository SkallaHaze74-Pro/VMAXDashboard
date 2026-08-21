#!/usr/bin/env python3
"""Gemini + GLM second-opinion review of the original VMAX app deep scan.

Advisory/read-only only. The models are not allowed to turn app strings or SDK symbols
into BT638 facts, decoder rules, BLE writes, tuning values, firmware actions or guessed
authentication keys.
"""

from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
from typing import Any

import provider_review
import provider_retry
import review_output_guard


def read(path: Path, limit: int = 14000) -> str:
    if not path.is_file():
        return f"<fehlt: {path}>"
    text = path.read_text(encoding="utf-8", errors="replace")
    return text if len(text) <= limit else text[:limit] + "\n<gekürzt>"


def build_original_app_prompt(
    deep_scan: Path,
    old_findings: Path,
    original_semantics: Path,
    platform_separation: Path,
    handshake_evidence: Path,
) -> str:
    return "\n".join([
        "Aufgabe: Prüfe den Original-App/Base/libble-Deep-Scan als externe READ-ONLY-Zweitmeinung.",
        "WICHTIG: Der konkrete New VX2 Gear ist laut VMAX-Hardwarebezeichnung V-Core Gear/V-Torque. Hyena/Hylink ist in der APK gebündelt, aber nicht als BT638-Hardware nachgewiesen.",
        "Trenne zwingend: (A) VMAX/V-Core-Gerätefakten, (B) BT638/GPST-DA1A-Live-/Native-Evidenz, (C) Hyena/Hylink-App-SDK, (D) andere Vendor-SDKs wie Sachs/Brose/Hobbywing.",
        "DA1A/15xx darf nicht pauschal Hyena genannt werden, solange keine direkte Runtime-Verknüpfung belegt ist.",
        "Ein String, Klassenname oder nativer Symbolname beweist nur Code-/SDK-Vorhandensein, nicht BT638-Unterstützung.",
        "AUTH-REGEL: Sachs-spezifische Routinen wie authSachsBike/changeSachsConnectionKey sind KEIN BT638-Handshake. SetApiKey ist ohne gerätespezifische Kette ebenfalls kein Controller-Unlock-Beweis.",
        "Erfinde oder rate niemals Secret Keys, Challenge-Antworten oder Auth-Frames. Prüfe nur, ob BT638-spezifische Auth-Evidenz tatsächlich vorliegt.",
        "Suche besonders nach übersehenen READ-ONLY-Funktionen, falschen Zuordnungen und nützlichen Diagnosezielen.",
        "Bevorzuge sichere Lese-/Erkennungstests. Keine BLE-Schreibframes, keine Tuningwerte, keine Firmware-Patches oder Bypass-Ideen.",
        "Wenn Gemini und GLM zufällig dasselbe vermuten, ist das weiterhin keine zusätzliche Evidenz.",
        "Antworte insgesamt höchstens 700 Wörter, pro Abschnitt maximal 4 kurze Punkte und beende zwingend mit dem geforderten Freigabe-Satz.",
        "",
        "===== Plattform-/Vendor-Trennung =====",
        read(platform_separation, 9000),
        "",
        "===== BT638 Handshake/Auth-Evidenz =====",
        read(handshake_evidence, 10000),
        "",
        "===== Neuer Deep Scan =====",
        read(deep_scan, 14000),
        "",
        "===== Alte Deep Findings zum Vergleich =====",
        read(old_findings, 7000),
        "",
        "===== Deterministisch gepflegte Original-App-Semantik =====",
        read(original_semantics, 9000),
    ])


def render(result: dict[str, Any]) -> str:
    lines = [
        "# Gemini + GLM – Original-App Deep Review",
        "",
        "> Advisory only / READ-ONLY. KI-Aussagen sind keine Ground Truth und aktivieren nichts automatisch.",
        "> VMAX/V-Core, BT638/GPST-DA1A, Hyena/Hylink und andere Vendor-SDKs werden getrennt bewertet.",
        "> Fremde Vendor-Authentifizierung oder API-Key-Symbole gelten nicht als BT638-Secret-Key-Beweis.",
        "",
    ]
    for key, title in (("gemini", "Gemini"), ("glm", "GLM / Z.ai")):
        item = (result.get("providers") or {}).get(key) or {}
        lines += [f"## {title}", "", f"Status: `{item.get('status', 'unknown')}`"]
        if item.get("model"):
            lines.append(f"Modell: `{item['model']}`")
        if item.get("provider"):
            lines.append(f"Provider: `{item['provider']}`")
        if item.get("fallback"):
            lines.append("Fallback: `true`")
        if item.get("status") == "cached_ok":
            lines.append("Frische: `letzte vollständige Antwort zwischengespeichert`")
            last_attempt = item.get("lastAttempt") if isinstance(item.get("lastAttempt"), dict) else {}
            if last_attempt.get("error"):
                lines.append(f"Aktueller Versuch: `{last_attempt.get('model', '')}` nicht verfügbar – letzte vollständige Analyse bleibt erhalten.")
        elif item.get("fresh") is True:
            lines.append("Frische: `aktuell`")
        lines.append("")
        if item.get("text"):
            lines += [str(item["text"]), ""]
        elif item.get("error"):
            lines += [f"Fehler: {item['error']}", ""]
        else:
            lines += ["Keine nutzbare Antwort.", ""]
    return "\n".join(lines).rstrip() + "\n"


def _read_previous(path: Path) -> dict[str, Any] | None:
    if not path.is_file():
        return None
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
        return data if isinstance(data, dict) else None
    except Exception:
        return None


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--deep-scan", default="reverse-engineering/reports/ORIGINAL_APP_DEEP_SCAN_2026-08-21.md")
    parser.add_argument("--old-findings", default="reverse-engineering/reports/DEEP_FINDINGS_2026-08-05.md")
    parser.add_argument("--original-semantics", default="tools/decoder_ai/original_app_semantics.json")
    parser.add_argument("--platform-separation", default="reverse-engineering/reports/VMAX_VCORE_HYENA_SEPARATION_2026-08-21.md")
    parser.add_argument("--handshake-evidence", default="reverse-engineering/reports/BT638_HANDSHAKE_AUTH_EVIDENCE_2026-08-21.md")
    parser.add_argument("--output", default="reverse-engineering/reports/ORIGINAL_APP_AI_REVIEW_2026-08-21.json")
    parser.add_argument("--report", default="reverse-engineering/reports/ORIGINAL_APP_AI_REVIEW_2026-08-21.md")
    args = parser.parse_args()

    output = Path(args.output)
    previous = _read_previous(output)
    prompt = build_original_app_prompt(
        Path(args.deep_scan),
        Path(args.old_findings),
        Path(args.original_semantics),
        Path(args.platform_separation),
        Path(args.handshake_evidence),
    )
    gemini_key = os.environ.get("GEMINI_API_KEY", "").strip() or None
    glm_key = os.environ.get("ZHIPU_API_KEY", "").strip() or None

    result: dict[str, Any] = {
        "schema": "vmax-original-app-provider-review-v3",
        "advisoryOnly": True,
        "readOnlyReviewerContract": True,
        "automaticChangeAuthority": False,
        "platformEvidencePolicy": "vcore_bt638_gpst_vendor_auth_separated",
        "providers": {
            "gemini": provider_review.run_provider(
                "Gemini", provider_review.GEMINI_MODEL, gemini_key, provider_review.ask_gemini, prompt
            ),
            "glm": provider_review.run_provider(
                "GLM", provider_review.GLM_MODEL, glm_key, provider_review.ask_glm, prompt
            ),
        },
    }

    result = provider_retry.retry_failed_providers(result, prompt, gemini_key, glm_key)
    result = review_output_guard.validate(result)
    result = provider_retry.preserve_last_success(result, previous)

    report = Path(args.report)
    output.parent.mkdir(parents=True, exist_ok=True)
    report.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    report.write_text(render(result), encoding="utf-8")

    print(
        "Original-App AI review: "
        + ", ".join(
            f"{name}={item.get('status')}:{item.get('model', '')}"
            for name, item in (result.get("providers") or {}).items()
            if isinstance(item, dict)
        )
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

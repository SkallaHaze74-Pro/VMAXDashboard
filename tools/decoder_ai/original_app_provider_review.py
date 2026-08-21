#!/usr/bin/env python3
"""Gemini + GLM second-opinion review of the original VMAX app deep scan.

Advisory/read-only only. The models are not allowed to turn app strings or SDK symbols
into BT638 facts, decoder rules, BLE writes, tuning values, or firmware actions.
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


def read(path: Path, limit: int = 28000) -> str:
    if not path.is_file():
        return f"<fehlt: {path}>"
    text = path.read_text(encoding="utf-8", errors="replace")
    return text if len(text) <= limit else text[:limit] + "\n<gekürzt>"


def build_original_app_prompt(deep_scan: Path, old_findings: Path, original_semantics: Path) -> str:
    return "\n".join([
        "Aufgabe: Prüfe den neuen Original-App/Base/libble-Deep-Scan als externe READ-ONLY-Zweitmeinung.",
        "Trenne zwingend drei Evidenzklassen: (A) Hyena-spezifisch, (B) generischer Multi-Vendor-Code, (C) anderes Hersteller-SDK wie Brose.",
        "Ein String, Klassenname oder nativer Symbolname beweist nur Code-/SDK-Vorhandensein, nicht BT638-Unterstützung.",
        "Suche besonders nach bisher übersehenen READ-ONLY-Funktionen, falschen Zuordnungen und nützlichen Diagnosezielen.",
        "Bevorzuge sichere Lese-/Erkennungstests. Keine BLE-Schreibframes, keine Tuningwerte, keine Firmware-Patches oder Bypass-Ideen.",
        "Wenn Gemini und GLM zufällig dasselbe vermuten, ist das weiterhin keine zusätzliche Evidenz.",
        "",
        "===== Neuer Deep Scan =====",
        read(deep_scan),
        "",
        "===== Alte Deep Findings zum Vergleich =====",
        read(old_findings),
        "",
        "===== Bereits deterministisch gepflegte Original-App-Semantik =====",
        read(original_semantics),
    ])


def render(result: dict[str, Any]) -> str:
    lines = [
        "# Gemini + GLM – Original-App Deep Review",
        "",
        "> Advisory only / READ-ONLY. KI-Aussagen sind keine Ground Truth und aktivieren nichts automatisch.",
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
        lines.append("")
        if item.get("text"):
            lines += [str(item["text"]), ""]
        elif item.get("error"):
            lines += [f"Fehler: {item['error']}", ""]
        else:
            lines += ["Keine nutzbare Antwort.", ""]
    return "\n".join(lines).rstrip() + "\n"


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--deep-scan", default="reverse-engineering/reports/ORIGINAL_APP_DEEP_SCAN_2026-08-21.md")
    parser.add_argument("--old-findings", default="reverse-engineering/reports/DEEP_FINDINGS_2026-08-05.md")
    parser.add_argument("--original-semantics", default="tools/decoder_ai/original_app_semantics.json")
    parser.add_argument("--output", default="reverse-engineering/reports/ORIGINAL_APP_AI_REVIEW_2026-08-21.json")
    parser.add_argument("--report", default="reverse-engineering/reports/ORIGINAL_APP_AI_REVIEW_2026-08-21.md")
    args = parser.parse_args()

    prompt = build_original_app_prompt(Path(args.deep_scan), Path(args.old_findings), Path(args.original_semantics))
    gemini_key = os.environ.get("GEMINI_API_KEY", "").strip() or None
    glm_key = os.environ.get("ZHIPU_API_KEY", "").strip() or None

    result: dict[str, Any] = {
        "schema": "vmax-original-app-provider-review-v1",
        "advisoryOnly": True,
        "readOnlyReviewerContract": True,
        "automaticChangeAuthority": False,
        "providers": {
            "gemini": provider_review.run_provider(
                "Gemini", provider_review.GEMINI_MODEL, gemini_key, provider_review.ask_gemini, prompt
            ),
            "glm": provider_review.run_provider(
                "GLM", provider_review.GLM_MODEL, glm_key, provider_review.ask_glm, prompt
            ),
        },
    }

    # Reuse the project's transient fallback logic. This does not modify any decoder rule.
    result = provider_retry.retry_failed_providers(result, prompt, gemini_key, glm_key)
    result = review_output_guard.validate(result)

    output = Path(args.output)
    report = Path(args.report)
    output.parent.mkdir(parents=True, exist_ok=True)
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

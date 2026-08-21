#!/usr/bin/env python3
"""Validate external AI reviewer output before it is published as successful.

The provider prompt requires a fixed completion footer. A truncated model answer must
never be stored as `status=ok`, because that makes a partial response look complete.
This guard is advisory-only: it never changes decoder rules or scooter state.
"""

from __future__ import annotations

import argparse
import json
from pathlib import Path
from typing import Any

from provider_review import (
    MAX_PROVIDER_TEXT,
    MIN_REVIEW_CHARS,
    REQUIRED_FOOTER,
    REQUIRED_SECTIONS,
    is_complete_review_text,
    render_markdown,
)

def is_complete_review(text: str) -> bool:
    return is_complete_review_text(text)


def _guard_completed_item(item: dict[str, Any], label: str) -> None:
    if item.get("status") not in {"ok", "cached_ok"}:
        item["outputComplete"] = False
        return

    text = str(item.get("text") or "").strip()
    complete = is_complete_review(text)
    item["outputComplete"] = complete
    if complete:
        return

    preview = text[-240:] if text else ""
    item["status"] = "error"
    item["text"] = ""
    item["error"] = (
        f"Unvollständige {label}-Antwort verworfen: Pflichtabschnitt oder Abschlussmarker fehlt."
        + (f" Letzter Ausschnitt: {preview}" if preview else "")
    )[:600]


def validate(result: dict[str, Any]) -> dict[str, Any]:
    guarded = json.loads(json.dumps(result))
    providers = guarded.get("providers") if isinstance(guarded.get("providers"), dict) else {}

    for key, item in providers.items():
        if not isinstance(item, dict):
            continue
        _guard_completed_item(item, "Reviewer")

    synthesis = guarded.get("teamSynthesis")
    if isinstance(synthesis, dict):
        _guard_completed_item(synthesis, "OpenAI-Synthese")
        synthesis["role"] = "synthesis_only"
        synthesis["countsAsIndependentEvidence"] = False
        synthesis["automaticChangeAuthority"] = False

    guarded["independentReviewerCount"] = sum(
        1
        for item in providers.values()
        if isinstance(item, dict)
        and item.get("status") in {"ok", "cached_ok"}
        and item.get("outputComplete") is True
    )
    guarded["modelConsensusCountsAsEvidence"] = False
    guarded["outputContractValidated"] = True
    guarded["automaticChangeAuthority"] = False
    guarded["readOnlyReviewerContract"] = True
    return guarded


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", default="decoder-ai/provider_review.json")
    parser.add_argument("--output", default="decoder-ai/provider_review.json")
    parser.add_argument("--report", default="decoder-ai/provider_review.md")
    args = parser.parse_args()

    source = Path(args.input)
    result = json.loads(source.read_text(encoding="utf-8"))
    guarded = validate(result)

    output = Path(args.output)
    report = Path(args.report)
    output.parent.mkdir(parents=True, exist_ok=True)
    report.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(guarded, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    report.write_text(render_markdown(guarded), encoding="utf-8")

    statuses = ", ".join(
        f"{name}={item.get('status')}"
        for name, item in (guarded.get("providers") or {}).items()
        if isinstance(item, dict)
    )
    print(f"Reviewer output guard: {statuses}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

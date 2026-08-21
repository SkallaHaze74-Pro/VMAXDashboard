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

from provider_review import render_markdown

REQUIRED_FOOTER = "Freigabe: keine automatische Änderung."


def validate(result: dict[str, Any]) -> dict[str, Any]:
    guarded = json.loads(json.dumps(result))
    providers = guarded.get("providers") if isinstance(guarded.get("providers"), dict) else {}

    for key, item in providers.items():
        if not isinstance(item, dict):
            continue
        if item.get("status") != "ok":
            item["outputComplete"] = False
            continue

        text = str(item.get("text") or "").strip()
        complete = text.endswith(REQUIRED_FOOTER)
        item["outputComplete"] = complete
        if complete:
            continue

        # Preserve only a short diagnostic hint, not a partial analysis that could
        # later be mistaken for a complete technical conclusion.
        preview = text[-240:] if text else ""
        item["status"] = "error"
        item["text"] = ""
        item["error"] = (
            "Unvollständige Reviewer-Antwort verworfen: Abschlussmarker fehlt."
            + (f" Letzter Ausschnitt: {preview}" if preview else "")
        )[:600]

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

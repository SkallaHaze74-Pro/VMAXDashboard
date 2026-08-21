#!/usr/bin/env python3
"""Preserve the last complete advisory provider review across transient outages."""

from __future__ import annotations

import argparse
import json
from pathlib import Path

import provider_retry
import provider_review


def load(path: Path) -> dict:
    if not path.is_file():
        return {}
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
        return data if isinstance(data, dict) else {}
    except Exception:
        return {}


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--current", default="decoder-ai/provider_review.json")
    parser.add_argument("--previous", default="")
    parser.add_argument("--output", default="decoder-ai/provider_review.json")
    parser.add_argument("--report", default="decoder-ai/provider_review.md")
    args = parser.parse_args()

    current = load(Path(args.current))
    previous = load(Path(args.previous)) if args.previous else {}
    merged = provider_retry.preserve_last_success(current, previous)

    output = Path(args.output)
    report = Path(args.report)
    output.parent.mkdir(parents=True, exist_ok=True)
    report.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(merged, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    report.write_text(provider_review.render_markdown(merged), encoding="utf-8")

    print(
        f"Provider last-good: fresh={merged.get('freshProviderCount', 0)} "
        f"cached={merged.get('cachedProviderCount', 0)}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

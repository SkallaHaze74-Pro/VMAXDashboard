#!/usr/bin/env python3
"""Retry transient external-review failures with cheaper fallback models.

This module never changes decoder rules. It only improves availability of the
read-only Gemini/GLM second-opinion report when a primary model times out, is under
high demand, or returns no usable text.
"""

from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
from typing import Any

import provider_review

GEMINI_FALLBACK_MODEL = "gemini-3.6-flash"


def _gemini_fallback(api_key: str, prompt: str) -> dict[str, Any]:
    payload = {
        "model": GEMINI_FALLBACK_MODEL,
        "system_instruction": provider_review.SYSTEM_PROMPT,
        "input": prompt,
        "store": False,
        "generation_config": {
            "max_output_tokens": 4096,
            "thinking_level": "high",
        },
    }
    data = provider_review.post_json(
        provider_review.GEMINI_URL,
        {"x-goog-api-key": api_key},
        payload,
    )
    return {
        "status": "ok",
        "model": GEMINI_FALLBACK_MODEL,
        "provider": "Gemini",
        "fallback": True,
        "text": provider_review.extract_gemini_text(data),
    }


def _glm_free_fallback(api_key: str, prompt: str) -> dict[str, Any]:
    last_error: BaseException | None = None
    for model in (provider_review.GLM_FREE_MODEL, provider_review.GLM_FREE_BACKUP_MODEL):
        try:
            provider, text = provider_review.call_glm_model(
                api_key,
                prompt,
                model,
                allow_bigmodel_fallback=False,
            )
            return {
                "status": "ok",
                "model": model,
                "provider": provider,
                "fallback": True,
                "text": text,
            }
        except Exception as error:
            last_error = error
    raise last_error or RuntimeError("Kein kostenloser GLM-Fallback verfügbar")


def retry_failed_providers(
    result: dict[str, Any],
    prompt: str,
    gemini_key: str | None,
    glm_key: str | None,
) -> dict[str, Any]:
    retried = json.loads(json.dumps(result))
    providers = retried.setdefault("providers", {})

    gemini = providers.get("gemini") if isinstance(providers.get("gemini"), dict) else {}
    if gemini_key and gemini.get("status") != "ok":
        primary_error = str(gemini.get("error") or "")
        try:
            providers["gemini"] = _gemini_fallback(gemini_key, prompt)
            providers["gemini"]["primaryError"] = primary_error[:300]
        except Exception as error:
            gemini["fallbackAttempted"] = True
            gemini["fallbackError"] = str(error)[:300]
            providers["gemini"] = gemini

    glm = providers.get("glm") if isinstance(providers.get("glm"), dict) else {}
    if glm_key and glm.get("status") != "ok":
        primary_error = str(glm.get("error") or "")
        try:
            providers["glm"] = _glm_free_fallback(glm_key, prompt)
            providers["glm"]["primaryError"] = primary_error[:300]
        except Exception as error:
            glm["fallbackAttempted"] = True
            glm["fallbackError"] = str(error)[:300]
            providers["glm"] = glm

    retried["transientFallbackPass"] = True
    retried["advisoryOnly"] = True
    retried["readOnlyReviewerContract"] = True
    retried["automaticChangeAuthority"] = False
    return retried


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--analysis-report", default="decoder-ai/analysis_report.md")
    parser.add_argument("--decoder-profile", default="decoder-ai/decoder_profile.json")
    parser.add_argument("--libble", default="decoder-ai/libble_comparison.md")
    parser.add_argument("--original-app", default="decoder-ai/original_app_comparison.md")
    parser.add_argument("--input", default="decoder-ai/provider_review.json")
    parser.add_argument("--output", default="decoder-ai/provider_review.json")
    parser.add_argument("--report", default="decoder-ai/provider_review.md")
    args = parser.parse_args()

    input_path = Path(args.input)
    if not input_path.is_file():
        return 0

    prompt = provider_review.build_prompt(
        Path(args.analysis_report),
        Path(args.decoder_profile),
        Path(args.libble),
        Path(args.original_app),
    )
    result = json.loads(input_path.read_text(encoding="utf-8"))
    retried = retry_failed_providers(
        result,
        prompt,
        os.environ.get("GEMINI_API_KEY", "").strip() or None,
        os.environ.get("ZHIPU_API_KEY", "").strip() or None,
    )

    output = Path(args.output)
    report = Path(args.report)
    output.write_text(json.dumps(retried, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    report.write_text(provider_review.render_markdown(retried), encoding="utf-8")
    print(
        "Transient fallback: "
        + ", ".join(
            f"{name}={item.get('status')}:{item.get('model', '')}"
            for name, item in (retried.get("providers") or {}).items()
            if isinstance(item, dict)
        )
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

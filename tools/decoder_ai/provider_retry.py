#!/usr/bin/env python3
"""Resilient external-review fallback and last-good preservation.

This module never changes decoder rules. It only improves availability of the
read-only Gemini/GLM second-opinion report when a provider is rate-limited,
overloaded, times out, or returns no usable text.
"""

from __future__ import annotations

import argparse
import json
import os
import time
from pathlib import Path
from typing import Any, Callable

import provider_review

GEMINI_FALLBACK_MODELS = (
    ("gemini-3.6-flash", "medium"),
    ("gemini-3.5-flash-lite", "low"),
)
TRANSIENT_RETRY_DELAYS_S = (2.0, 6.0)
REQUIRED_FOOTER = "Freigabe: keine automatische Änderung."
COMPACT_REVIEW_SUFFIX = """

Antwortbudget für diesen Retry:
- maximal 700 Wörter,
- pro Abschnitt höchstens 4 kurze Punkte,
- keine Wiederholung der Eingabedaten,
- Abschlussmarker zwingend vollständig ausgeben.
""".strip()


def _compact_prompt(prompt: str) -> str:
    if COMPACT_REVIEW_SUFFIX in prompt:
        return prompt
    return f"{prompt.rstrip()}\n\n{COMPACT_REVIEW_SUFFIX}"


def _require_complete_text(text: str) -> str:
    clean = str(text or "").strip()
    if not clean.endswith(REQUIRED_FOOTER):
        raise RuntimeError("Unvollständige Reviewer-Antwort: Abschlussmarker fehlt")
    return clean


def _is_quota_error(error: BaseException) -> bool:
    if isinstance(error, provider_review.ProviderHttpError) and error.code == 429:
        return True
    message = str(error).lower()
    return any(marker in message for marker in ("quota", "rate-limit", "429", "daily limit"))


def _is_transient_error(error: BaseException) -> bool:
    if isinstance(error, provider_review.ProviderHttpError):
        return 500 <= error.code <= 599
    message = str(error).lower()
    return any(
        marker in message
        for marker in (
            "timeout",
            "timed out",
            "high demand",
            "temporarily",
            "temporary",
            "network",
            "connection reset",
            "service unavailable",
        )
    )


def _call_with_transient_retries(call: Callable[[], dict[str, Any]]) -> dict[str, Any]:
    last_error: BaseException | None = None
    for attempt in range(len(TRANSIENT_RETRY_DELAYS_S) + 1):
        try:
            return call()
        except Exception as error:
            last_error = error
            if not _is_transient_error(error) or attempt >= len(TRANSIENT_RETRY_DELAYS_S):
                raise
            time.sleep(TRANSIENT_RETRY_DELAYS_S[attempt])
    raise last_error or RuntimeError("Retry ohne Ergebnis")


def _gemini_model(
    api_key: str,
    prompt: str,
    model: str,
    thinking_level: str,
    *,
    fallback: bool,
) -> dict[str, Any]:
    payload = {
        "model": model,
        "system_instruction": provider_review.SYSTEM_PROMPT,
        "input": _compact_prompt(prompt),
        "store": False,
        "generation_config": {
            # A compact answer is more reliable than asking a review model to use
            # its entire output budget. The mandatory footer still gets validated.
            "max_output_tokens": 2200,
            "thinking_level": thinking_level,
        },
    }
    data = provider_review.post_json(
        provider_review.GEMINI_URL,
        {"x-goog-api-key": api_key},
        payload,
    )
    text = _require_complete_text(provider_review.extract_gemini_text(data))
    return {
        "status": "ok",
        "model": model,
        "provider": "Gemini",
        "fallback": fallback,
        "text": text,
        "outputComplete": True,
    }


def _gemini_resilient(api_key: str, prompt: str, primary_error: str) -> dict[str, Any]:
    attempts: list[str] = []

    # 5xx/high-demand/timeouts often clear within seconds. A 429 normally will not,
    # so do not burn the same model quota again when the primary attempt said quota.
    if not _is_quota_error(RuntimeError(primary_error)):
        attempts.append(provider_review.GEMINI_MODEL)
        try:
            item = _call_with_transient_retries(
                lambda: _gemini_model(
                    api_key,
                    prompt,
                    provider_review.GEMINI_MODEL,
                    "medium",
                    fallback=False,
                )
            )
            item["retryPath"] = attempts
            return item
        except Exception:
            pass

    last_error: BaseException | None = None
    for model, thinking_level in GEMINI_FALLBACK_MODELS:
        attempts.append(model)
        try:
            item = _call_with_transient_retries(
                lambda m=model, t=thinking_level: _gemini_model(
                    api_key,
                    prompt,
                    m,
                    t,
                    fallback=True,
                )
            )
            item["retryPath"] = attempts
            return item
        except Exception as error:
            last_error = error
            # Quota, truncation or a transient outage on one model may still leave
            # the next fallback usable, so continue through the full chain.
            continue

    raise last_error or RuntimeError("Kein Gemini-Modell konnte die Anfrage beantworten")


def _glm_free_fallback(api_key: str, prompt: str) -> dict[str, Any]:
    last_error: BaseException | None = None
    attempts: list[str] = []
    compact = _compact_prompt(prompt)
    for model in (provider_review.GLM_FREE_MODEL, provider_review.GLM_FREE_BACKUP_MODEL):
        attempts.append(model)
        try:
            def call() -> dict[str, Any]:
                provider, text = provider_review.call_glm_model(
                    api_key,
                    compact,
                    model,
                    allow_bigmodel_fallback=False,
                )
                return {
                    "status": "ok",
                    "model": model,
                    "provider": provider,
                    "fallback": True,
                    "text": _require_complete_text(text),
                    "outputComplete": True,
                }

            item = _call_with_transient_retries(call)
            item["retryPath"] = attempts
            return item
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
            providers["gemini"] = _gemini_resilient(gemini_key, prompt, primary_error)
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


def preserve_last_success(
    current: dict[str, Any],
    previous: dict[str, Any] | None,
) -> dict[str, Any]:
    """Keep a complete previous review when a fresh provider attempt is unavailable.

    Provider outages should be visible in metadata, but must not erase the last
    complete technical review. Cached text stays advisory-only and is explicitly
    marked as not fresh.
    """
    merged = json.loads(json.dumps(current))
    previous = previous if isinstance(previous, dict) else {}
    providers = merged.setdefault("providers", {})
    old_providers = previous.get("providers") if isinstance(previous.get("providers"), dict) else {}

    fresh_count = 0
    cached_count = 0
    for key in ("gemini", "glm"):
        now = providers.get(key) if isinstance(providers.get(key), dict) else {}
        if now.get("status") == "ok" and str(now.get("text") or "").strip():
            now["fresh"] = True
            providers[key] = now
            fresh_count += 1
            continue

        old = old_providers.get(key) if isinstance(old_providers.get(key), dict) else {}
        old_text = str(old.get("text") or "").strip()
        old_complete = bool(old.get("outputComplete", old_text.endswith(REQUIRED_FOOTER)))
        if old_text and old_complete and old.get("status") in {"ok", "cached_ok"}:
            cached = json.loads(json.dumps(old))
            cached["status"] = "cached_ok"
            cached["fresh"] = False
            cached["cachedFromPreviousRun"] = True
            cached["outputComplete"] = True
            cached["lastAttempt"] = {
                "status": now.get("status", "error"),
                "model": now.get("model", ""),
                "error": str(now.get("error") or now.get("fallbackError") or "")[:300],
            }
            providers[key] = cached
            cached_count += 1
        else:
            now["fresh"] = False
            providers[key] = now

    merged["freshProviderCount"] = fresh_count
    merged["cachedProviderCount"] = cached_count
    merged["advisoryOnly"] = True
    merged["readOnlyReviewerContract"] = True
    merged["automaticChangeAuthority"] = False
    return merged


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

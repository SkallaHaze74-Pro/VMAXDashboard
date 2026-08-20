#!/usr/bin/env python3
"""Advisory external-model review for VMAX decoder evidence.

This script deliberately does NOT modify decoder_profile.json. Gemini/GLM output is
stored as a second-opinion report only; deterministic consensus and Android safety
policies remain the authority for activatable decoder rules.
"""

from __future__ import annotations

import argparse
import json
import os
import time
import urllib.error
import urllib.request
from pathlib import Path
from typing import Any

GEMINI_MODEL = "gemini-3.7-flash"
GLM_MODEL = "glm-5.3"
GLM_FREE_MODEL = "glm-4.7-flash"
GLM_FREE_BACKUP_MODEL = "glm-4.5-flash"
GEMINI_URL = "https://generativelanguage.googleapis.com/v1/interactions"
ZAI_GLM_URL = "https://api.z.ai/api/paas/v4/chat/completions"
BIGMODEL_GLM_URL = "https://open.bigmodel.cn/api/paas/v4/chat/completions"
MAX_SOURCE_CHARS = 24_000
MAX_PROVIDER_TEXT = 16_000

SYSTEM_PROMPT = """
Du bist ein rein lesender technischer Zweitprüfer für VMAXDashboard.
Prüfe die bereitgestellte Decoder-Evidenz auf Widersprüche, Scheinkorrelationen,
zu kleine Stichproben und sinnvolle nächste Messfahrten. Trenne bestätigte Fakten,
starke Evidenz, Hypothesen und offene Fragen. Erfinde keine Byte-Bedeutungen.
Gib keine automatisch ausführbaren BLE-Schreibbefehle, keine Firmware-Patches und
keine Anweisungen zum Umgehen von Sicherheits- oder Geschwindigkeitsgrenzen aus.
Die deterministische lokale Konsensanalyse bleibt maßgeblich; deine Antwort ist nur
advisory. Antworte auf Deutsch und möglichst kompakt.
""".strip()


class ProviderHttpError(RuntimeError):
    def __init__(self, code: int, message: str):
        super().__init__(message)
        self.code = code


def read_limited(path: Path, limit: int = MAX_SOURCE_CHARS) -> str:
    if not path.is_file():
        return f"<fehlt: {path}>"
    text = path.read_text(encoding="utf-8", errors="replace")
    if len(text) <= limit:
        return text
    return text[:limit] + f"\n\n<gekürzt: {len(text) - limit} Zeichen ausgelassen>"


def build_prompt(
    analysis_report: Path,
    decoder_profile: Path,
    libble_comparison: Path,
    original_app_comparison: Path,
) -> str:
    sections = [
        ("Deterministischer Analysebericht", read_limited(analysis_report)),
        ("Aktuelles Decoder-Profil", read_limited(decoder_profile)),
        ("libble-Vergleich", read_limited(libble_comparison)),
        ("Original-App-Vergleich", read_limited(original_app_comparison)),
    ]
    out = [
        "Aufgabe: Führe eine unabhängige Qualitätsprüfung der aktuellen Decoder-Evidenz durch.",
        "Nenne zuerst belastbare Übereinstimmungen, danach Konflikte/Unsicherheiten und zum Schluss maximal fünf konkrete nächste Tests.",
        "Schlage keine Regel als bestätigt vor, wenn die gelieferten Daten das nicht tragen.",
    ]
    for title, text in sections:
        out.extend(["", f"===== {title} =====", text])
    return "\n".join(out)


def post_json(url: str, headers: dict[str, str], payload: dict[str, Any]) -> dict[str, Any]:
    body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    request = urllib.request.Request(
        url,
        data=body,
        method="POST",
        headers={
            "Content-Type": "application/json; charset=utf-8",
            "Accept": "application/json",
            "User-Agent": "VMAXDashboard-DecoderAI",
            **headers,
        },
    )
    try:
        with urllib.request.urlopen(request, timeout=75) as response:
            raw = response.read().decode("utf-8", errors="replace")
    except urllib.error.HTTPError as error:
        detail = error.read().decode("utf-8", errors="replace") if error.fp else ""
        raise ProviderHttpError(error.code, provider_http_error(error.code, detail)) from None
    except urllib.error.URLError as error:
        raise RuntimeError(f"Netzwerkfehler: {error.reason}") from None
    if not raw.strip():
        raise RuntimeError("Leere Provider-Antwort")
    return json.loads(raw)


def provider_http_error(code: int, detail: str = "") -> str:
    message = ""
    try:
        message = str((json.loads(detail).get("error") or {}).get("message") or "").strip()
    except Exception:
        message = ""
    if code in (401, 403):
        base = f"API-Key nicht akzeptiert ({code})"
    elif code == 429:
        base = "Gratis-/Ratenlimit erreicht (429)"
    elif 500 <= code <= 599:
        base = f"Provider vorübergehend nicht verfügbar ({code})"
    else:
        base = f"Provider-HTTP-Fehler {code}"
    return f"{base} • {message[:260]}" if message else base


def extract_gemini_text(data: dict[str, Any]) -> str:
    status = str(data.get("status") or "")
    if status in {"failed", "cancelled", "budget_exceeded"}:
        raise RuntimeError(f"Gemini-Interaktion beendet mit Status {status}")

    chunks: list[str] = []
    for step in data.get("steps") or []:
        if not isinstance(step, dict) or step.get("type") != "model_output":
            continue
        for block in step.get("content") or []:
            if not isinstance(block, dict) or block.get("type") != "text":
                continue
            text = str(block.get("text") or "").strip()
            if text:
                chunks.append(text)
    text = "\n".join(chunks).strip()
    if not text:
        raise RuntimeError("Gemini hat keinen Text geliefert")
    return text[:MAX_PROVIDER_TEXT]


def ask_gemini(api_key: str, prompt: str) -> str:
    payload = {
        "model": GEMINI_MODEL,
        "system_instruction": SYSTEM_PROMPT,
        "input": prompt,
        "store": False,
        "generation_config": {
            "max_output_tokens": 4096,
            "thinking_level": "high",
        },
    }
    data = post_json(GEMINI_URL, {"x-goog-api-key": api_key}, payload)
    return extract_gemini_text(data)


def glm_payload(model: str, prompt: str) -> dict[str, Any]:
    payload: dict[str, Any] = {
        "model": model,
        "stream": False,
        "thinking": {"type": "enabled"},
        "messages": [
            {"role": "system", "content": SYSTEM_PROMPT},
            {"role": "user", "content": prompt},
        ],
    }
    if model == GLM_MODEL:
        payload["reasoning_effort"] = "max"
    return payload


def extract_glm_text(data: dict[str, Any]) -> str:
    choices = data.get("choices") or []
    message = (choices[0].get("message") or {}) if choices else {}
    text = str(message.get("content") or "").strip()
    if not text:
        raise RuntimeError("GLM hat keinen Text geliefert")
    return text[:MAX_PROVIDER_TEXT]


def call_glm_model(
    api_key: str,
    prompt: str,
    model: str,
    *,
    allow_bigmodel_fallback: bool,
) -> tuple[str, str]:
    headers = {
        "Authorization": f"Bearer {api_key}",
        "Accept-Language": "de-DE,de;q=0.9,en;q=0.8",
    }
    payload = glm_payload(model, prompt)
    try:
        data = post_json(ZAI_GLM_URL, headers, payload)
        provider = "Z.ai"
    except ProviderHttpError as error:
        if not allow_bigmodel_fallback or error.code not in (401, 403, 404):
            raise
        data = post_json(BIGMODEL_GLM_URL, headers, payload)
        provider = "BigModel"
    return provider, extract_glm_text(data)


def is_quota_or_balance_error(error: BaseException) -> bool:
    if isinstance(error, ProviderHttpError) and error.code == 429:
        return True
    message = str(error).lower()
    return any(
        marker in message
        for marker in ("quota", "rate-limit", "balance", "resource", "余额", "不足", "充值")
    )


def ask_glm_with_meta(api_key: str, prompt: str) -> dict[str, Any]:
    try:
        provider, text = call_glm_model(
            api_key,
            prompt,
            GLM_MODEL,
            allow_bigmodel_fallback=True,
        )
        return {
            "text": text,
            "model": GLM_MODEL,
            "provider": provider,
            "fallback": False,
        }
    except Exception as primary_error:
        if not is_quota_or_balance_error(primary_error):
            raise

    last_error: BaseException | None = None
    for model in (GLM_FREE_MODEL, GLM_FREE_BACKUP_MODEL):
        try:
            provider, text = call_glm_model(
                api_key,
                prompt,
                model,
                allow_bigmodel_fallback=False,
            )
            return {
                "text": text,
                "model": model,
                "provider": provider,
                "fallback": True,
            }
        except Exception as error:
            last_error = error

    raise last_error or RuntimeError("Kein GLM-Modell konnte die Anfrage beantworten")


def ask_glm(api_key: str, prompt: str) -> str:
    return str(ask_glm_with_meta(api_key, prompt)["text"])


def run_provider(name: str, model: str, key: str | None, ask, prompt: str) -> dict[str, Any]:
    if not key:
        return {"status": "not_configured", "model": model, "text": ""}
    try:
        if name == "GLM":
            item = ask_glm_with_meta(key, prompt)
            return {
                "status": "ok",
                "model": item["model"],
                "provider": item["provider"],
                "fallback": item["fallback"],
                "text": item["text"],
            }
        return {"status": "ok", "model": model, "text": ask(key, prompt)}
    except Exception as error:
        return {
            "status": "error",
            "model": model,
            "text": "",
            "error": str(error)[:300],
            "provider": name,
        }


def render_markdown(result: dict[str, Any]) -> str:
    lines = [
        "# Gemini + GLM Decoder-Zweitprüfung",
        "",
        "> Advisory only: Diese Modellantworten aktivieren keine Decoder-Regel und erzeugen keine BLE-Schreibbefehle.",
        "",
    ]
    for key, title in (("gemini", "Gemini 3.7 Flash"), ("glm", "GLM")):
        item = result["providers"][key]
        lines.extend([f"## {title}", "", f"Status: `{item['status']}`", ""])
        if item.get("model"):
            lines.extend([f"Modell: `{item['model']}`", ""])
        if item.get("fallback"):
            lines.extend(["Kostenloser GLM-Fallback aktiv.", ""])
        if item.get("text"):
            lines.extend([item["text"], ""])
        elif item.get("error"):
            lines.extend([f"Fehler: {item['error']}", ""])
        else:
            lines.extend(["Nicht konfiguriert.", ""])
    return "\n".join(lines).rstrip() + "\n"


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--analysis-report", default="decoder-ai/analysis_report.md")
    parser.add_argument("--decoder-profile", default="decoder-ai/decoder_profile.json")
    parser.add_argument("--libble", default="decoder-ai/libble_comparison.json")
    parser.add_argument("--original-app", default="decoder-ai/original_app_comparison.json")
    parser.add_argument("--output", default="decoder-ai/provider_review.json")
    parser.add_argument("--report", default="decoder-ai/provider_review.md")
    args = parser.parse_args()

    prompt = build_prompt(
        Path(args.analysis_report),
        Path(args.decoder_profile),
        Path(args.libble),
        Path(args.original_app),
    )
    result = {
        "schema": "vmax-provider-review-v1",
        "generatedAtMs": int(time.time() * 1000),
        "advisoryOnly": True,
        "providers": {
            "gemini": run_provider(
                "Gemini",
                GEMINI_MODEL,
                os.environ.get("GEMINI_API_KEY", "").strip() or None,
                ask_gemini,
                prompt,
            ),
            "glm": run_provider(
                "GLM",
                GLM_MODEL,
                os.environ.get("ZHIPU_API_KEY", "").strip() or None,
                ask_glm,
                prompt,
            ),
        },
    }

    output = Path(args.output)
    report = Path(args.report)
    output.parent.mkdir(parents=True, exist_ok=True)
    report.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    report.write_text(render_markdown(result), encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

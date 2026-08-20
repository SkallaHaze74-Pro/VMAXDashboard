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
GEMINI_URL = "https://generativelanguage.googleapis.com/v1/interactions"
GLM_URL = "https://open.bigmodel.cn/api/paas/v4/chat/completions"
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
        raise RuntimeError(provider_http_error(error.code)) from None
    except urllib.error.URLError as error:
        raise RuntimeError(f"Netzwerkfehler: {error.reason}") from None
    if not raw.strip():
        raise RuntimeError("Leere Provider-Antwort")
    return json.loads(raw)


def provider_http_error(code: int) -> str:
    if code in (401, 403):
        return f"API-Key nicht akzeptiert ({code})"
    if code == 429:
        return "Gratis-/Ratenlimit erreicht (429)"
    if 500 <= code <= 599:
        return f"Provider vorübergehend nicht verfügbar ({code})"
    return f"Provider-HTTP-Fehler {code}"


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
        # No server-side history is needed for the automated decoder review.
        "store": False,
        "generation_config": {
            "max_output_tokens": 4096,
            "thinking_level": "high",
        },
    }
    data = post_json(GEMINI_URL, {"x-goog-api-key": api_key}, payload)
    return extract_gemini_text(data)


def ask_glm(api_key: str, prompt: str) -> str:
    payload = {
        "model": GLM_MODEL,
        "stream": False,
        "messages": [
            {"role": "system", "content": SYSTEM_PROMPT},
            {"role": "user", "content": prompt},
        ],
    }
    data = post_json(GLM_URL, {"Authorization": f"Bearer {api_key}"}, payload)
    choices = data.get("choices") or []
    message = (choices[0].get("message") or {}) if choices else {}
    text = str(message.get("content") or "").strip()
    if not text:
        raise RuntimeError("GLM hat keinen Text geliefert")
    return text[:MAX_PROVIDER_TEXT]


def run_provider(name: str, model: str, key: str | None, ask, prompt: str) -> dict[str, Any]:
    if not key:
        return {"status": "not_configured", "model": model, "text": ""}
    try:
        return {"status": "ok", "model": model, "text": ask(key, prompt)}
    except Exception as error:  # advisory path must never block deterministic analysis
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
    for key, title in (("gemini", "Gemini 3.7 Flash"), ("glm", "GLM-5.3")):
        item = result["providers"][key]
        lines.extend([f"## {title}", "", f"Status: `{item['status']}`", ""])
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

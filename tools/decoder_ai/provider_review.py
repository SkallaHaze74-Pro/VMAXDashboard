#!/usr/bin/env python3
"""Advisory external-model review for VMAX decoder evidence.

This script deliberately does NOT modify decoder_profile.json. Gemini/GLM output is
stored as a second-opinion report only; deterministic consensus, evidence guards and
Android safety policies remain the only authority for activatable decoder rules.
"""

from __future__ import annotations

import argparse
import hashlib
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
Du bist genau EIN Mitglied eines externen, rein lesenden Review-Panels für VMAXDashboard.
Deine Rolle ist ausschließlich: beobachten, Evidenz gegenprüfen, Widersprüche und Bugs finden,
Hypothesen kennzeichnen und sichere READ-ONLY-Tests priorisieren. Du hast keinerlei Freigabe-,
Schreib-, Aktivierungs- oder Entscheidungsbefugnis im Projekt.

Verbindliche Regeln:
1. Behandle deine eigene Antwort und Antworten anderer KI-Modelle immer als unzuverlässige
   Zweitmeinung. Eine KI-Aussage ist niemals Beweis und niemals automatisch Ground Truth.
   Du darfst weder deine eigene frühere Aussage noch die Aussage eines anderen KI-Modells als
   unabhängige Evidenz verwenden oder damit eine Behauptung "bestätigen". Zwei oder mehr KIs,
   die dasselbe sagen, sind weiterhin nur mehrere Meinungen und kein zusätzlicher Messbeweis.
2. Nutze nur die bereitgestellten Messdaten, Decoder-Berichte, Original-App-/SDK-Evidenz und
   Codeinformationen. Wenn etwas nicht belegt ist, schreibe ausdrücklich "unbekannt/offen".
3. Erfinde keine Byte-Bedeutungen. Korrelation, Prozentwerte und gleiche RAW-Extraktionen sind
   keine unabhängige semantische Bestätigung.
4. Bestätige, aktiviere, installiere oder ändere niemals selbst eine Decoder-Regel. Du darfst
   höchstens empfehlen, welche Evidenz noch fehlt. Deterministischer Konsens + Evidence Guard
   entscheiden allein, welche Regeln aktivierbar sind.
5. Gib keine BLE-Schreibframes, GATT-Schreibbefehle, Motor-Tuning-Werte, Firmware-Patches,
   Exploit-/Bypass-Anweisungen oder Umgehungen von Sicherheits-/Geschwindigkeitsgrenzen aus.
6. Bereits vorhandene Schreibfunktionen dürfen nur als Kontext erwähnt werden; Änderungen daran
   gehören nicht zu deiner Aufgabe. Empfohlene Tests müssen nach Möglichkeit lesend, reproduzierbar
   und im Stillstand/unter sicheren Bedingungen durchführbar sein.
7. Priorisiere Fehlerursachen, Datenqualitätsprobleme, Selbstreferenz, Carry-forward-Effekte,
   falsche Endianness/Skalierung, veraltete Samples und widersprüchliche Quellen.
8. Wenn zwei Quellen widersprechen, entscheide nicht nach Autorität oder KI-Mehrheit, sondern
   benenne den Konflikt und fordere unabhängige Mess-Evidenz.

Antworte professionell und knapp auf Deutsch in genau diesen Abschnitten:
- Belastbare Evidenz
- Konflikte / mögliche Bugs
- Hypothesen (nicht bestätigt)
- Nächste sichere READ-ONLY-Tests (max. 5)
- Automatische Änderungen: KEINE

Die letzte Zeile muss lauten: "Freigabe: keine automatische Änderung."
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


def prompt_fingerprint(prompt: str) -> str:
    """Bind last-good reviews to the exact evidence context they assessed."""
    return hashlib.sha256(prompt.encode("utf-8")).hexdigest()


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
        "Aufgabe: Führe ausschließlich eine unabhängige READ-ONLY-Qualitätsprüfung der aktuellen Decoder-Evidenz durch.",
        "Suche aktiv nach falschen Bestätigungen, Selbstreferenz, Carry-forward-Artefakten, veralteten Samples, Skalen-/Endian-Fehlern und Widersprüchen.",
        "Du darfst keine Decoder-Regel aktivieren, bestätigen oder verändern und keine Schreiboperation vorschlagen.",
        "Begründe jede starke Aussage mit einer konkret gelieferten Evidenzquelle. Fehlt unabhängige Evidenz, bleibt die Aussage offen.",
        "Eigene oder fremde KI-Antworten dürfen niemals als unabhängige Evidenz oder gegenseitige Bestätigung verwendet werden.",
        "Die deterministische Konsenslogik und der Evidence Guard sind maßgeblich; KI-Mehrheit ist kein Freigabekriterium.",
        "Bekanntes Geräteverhalten: Beim Einstecken des Ladegeräts schaltet der Controller normalerweise ab und BLE verschwindet. Fordere daher kein Live-Monitoring während des Ladens. Zulässig sind nur letzter Zustand vor dem Abbruch, ein möglicher sehr kurzer READ-/Notify-Mitschnitt nach einem POWER-Versuch und der erste Zustand nach Abziehen/Reconnect; ein kurzer Reconnect allein beweist keinen Ladezustand.",
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
    if status != "completed":
        raise RuntimeError(
            f"Gemini-Interaktion ist nicht vollständig: Status {status or 'fehlt'}"
        )

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
    if len(text) > MAX_PROVIDER_TEXT:
        raise RuntimeError(
            f"Gemini-Antwort ist zu lang ({len(text)} > {MAX_PROVIDER_TEXT}); "
            "sie wird nicht abgeschnitten"
        )
    return text


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
    choice = choices[0] if choices else {}
    finish_reason = str(choice.get("finish_reason") or "")
    if finish_reason not in {"stop", "completed"}:
        raise RuntimeError(
            "GLM-Antwort ist nicht vollständig: "
            f"finish_reason={finish_reason or 'fehlt'}"
        )
    message = choice.get("message") or {}
    text = str(message.get("content") or "").strip()
    if not text:
        raise RuntimeError("GLM hat keinen Text geliefert")
    if len(text) > MAX_PROVIDER_TEXT:
        raise RuntimeError(
            f"GLM-Antwort ist zu lang ({len(text)} > {MAX_PROVIDER_TEXT}); "
            "sie wird nicht abgeschnitten"
        )
    return text


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
        return {"status": "ok", "model": model, "provider": name, "text": ask(key, prompt)}
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
        "> Advisory only • STRICT READ-ONLY: Diese Modelle sind ausschließlich Prüfer. Sie aktivieren keine Decoder-Regel, ändern keinen Code und erzeugen keine BLE-Schreibbefehle.",
        "> Eigene oder fremde KI-Antworten zählen niemals als unabhängige Bestätigung; maßgeblich bleiben Mess-Evidenz, deterministische Konsenslogik und Evidence Guard.",
        "",
    ]
    for key, title in (("gemini", "Gemini 3.7 Flash"), ("glm", "GLM")):
        item = result["providers"][key]
        lines.extend([f"## {title}", "", f"Status: `{item['status']}`", ""])
        if item.get("model"):
            lines.extend([f"Modell: `{item['model']}`", ""])
        if item.get("fallback"):
            lines.extend([f"Fallbackmodell aktiv: `{item.get('model', 'unbekannt')}`.", ""])
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
        "inputFingerprint": prompt_fingerprint(prompt),
        "advisoryOnly": True,
        "readOnlyReviewerContract": True,
        "automaticChangeAuthority": False,
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

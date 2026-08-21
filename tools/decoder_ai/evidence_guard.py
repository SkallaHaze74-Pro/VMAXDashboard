#!/usr/bin/env python3
"""Cross-check consensus rules against independent evidence quality.

The consensus learner can intentionally compare a canonical SDK layout with values
exported from the same raw packet. That proves parser/layout consistency, but it must
not silently become independent semantic proof. This guard demotes such a rule when
our separate quality evidence still marks the semantic meaning as unproven.

For the uncertain 1509/9 power candidate we additionally consume a cross-field check
against |voltage * current|. That is a genuinely different measurement path than the
former self-reference through `power_w`, but it is still not external ground truth.
"""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
from typing import Any

DIRECT_POWER_RULE = ("powerW", "1509", 9, "u16be")
DIRECT_POWER_EVIDENCE = "1509.direct_power_W"


def _canonical_revision(rules: list[dict[str, Any]]) -> str:
    payload = json.dumps(rules, sort_keys=True, separators=(",", ":")).encode("utf-8")
    return hashlib.sha256(payload).hexdigest()[:16]


def _number(value: object) -> float | None:
    return float(value) if isinstance(value, (int, float)) else None


def _trusted_independent_power_confirmation(evidence: dict[str, Any]) -> bool:
    """Remain fail-closed until this process reads and hashes the actual artifact.

    Metadata supplied inside the comparison JSON is not a trust boundary. A declared
    SHA-256 value, evidence type or boolean confirmation can all be fabricated or
    copied without the referenced bytes. Re-enable only when the artifact path is an
    explicit input, its bytes are hashed locally, and provenance/measurement checks
    are enforced here.
    """
    del evidence
    return False


def apply_evidence_guard(
    profile: dict[str, Any],
    libble: dict[str, Any],
    power_crosscheck: dict[str, Any] | None = None,
) -> dict[str, Any]:
    guarded = json.loads(json.dumps(profile))
    aggregate = libble.get("aggregate_fields") if isinstance(libble.get("aggregate_fields"), dict) else {}
    direct = aggregate.get(DIRECT_POWER_EVIDENCE) if isinstance(aggregate, dict) else None
    direct = direct if isinstance(direct, dict) else {}
    cross = power_crosscheck if isinstance(power_crosscheck, dict) else {}

    independent = _trusted_independent_power_confirmation(direct)
    verdict = str(direct.get("verdict") or "")
    layout_match_percent = _number(direct.get("match_percent"))
    layout_mae = _number(direct.get("mae_to_app_live"))
    cross_count = int(cross.get("comparisons") or 0)
    cross_close = _number(cross.get("closePercent"))
    cross_mae = _number(cross.get("maeW"))
    cross_corr = _number(cross.get("correlation"))

    rules = guarded.get("rules") if isinstance(guarded.get("rules"), list) else []
    for rule in rules:
        if not isinstance(rule, dict):
            continue
        key = (
            str(rule.get("signal") or ""),
            str(rule.get("channel") or "").upper(),
            int(rule.get("offset") or 0),
            str(rule.get("encoding") or ""),
        )
        if key != DIRECT_POWER_RULE or independent:
            continue

        rule["status"] = "candidate"

        # Never let the former same-RAW self-consistency score re-activate this
        # candidate. If enough cross-field comparisons exist, use them only as a
        # conservative confidence cap; 93 stays below the activation threshold.
        current_confidence = int(rule.get("confidence") or 0)
        if cross_count >= 20 and cross_close is not None:
            cross_cap = max(0, min(93, int(round(cross_close))))
            rule["confidence"] = min(current_confidence, cross_cap)
            rule["source"] = "sdk-layout+cross-field-check-needs-external-proof"
        elif layout_match_percent is not None:
            rule["confidence"] = min(current_confidence, min(89, int(round(layout_match_percent))))
            rule["source"] = "sdk-layout-observed-needs-independent-proof"
        else:
            rule["confidence"] = min(current_confidence, 89)
            rule["source"] = "sdk-layout-observed-needs-independent-proof"

        rule["evidenceGuard"] = {
            "reason": "same-raw export consistency is not independent semantic proof",
            "libbleVerdict": verdict or "UNKNOWN",
            "layoutMatchPercent": layout_match_percent,
            "layoutMae": layout_mae,
            "crossFieldComparisons": cross_count,
            "crossFieldClosePercent": cross_close,
            "crossFieldMaeW": cross_mae,
            "crossFieldCorrelation": cross_corr,
            "crossFieldReference": cross.get("reference") if cross else None,
            "independentExternalConfirmation": False,
            "independentArtifactBinding": "disabled-until-artifact-is-locally-hashed",
        }

    guarded["confirmedRuleCount"] = sum(
        1 for rule in rules if isinstance(rule, dict) and rule.get("status") == "confirmed"
    )
    guarded["ruleCount"] = len([rule for rule in rules if isinstance(rule, dict)])
    guarded["revision"] = _canonical_revision([rule for rule in rules if isinstance(rule, dict)])
    return guarded


def render_report(profile: dict[str, Any]) -> str:
    rules = profile.get("rules") if isinstance(profile.get("rules"), list) else []
    lines = [
        "# VMAX Decoder AI – Konsensbericht",
        "",
        f"- Fahrten ausgewertet: **{int(profile.get('rideCount') or 0)}**",
        f"- Regeln gesamt: **{int(profile.get('ruleCount') or 0)}**",
        f"- Davon bestätigt: **{int(profile.get('confirmedRuleCount') or 0)}**",
        f"- Profil-Revision: `{profile.get('revision') or ''}`",
        "",
        "## Regeln",
        "",
        "| Status | Signal | Kanal | Feld | Konfidenz | Evidenz | Quelle |",
        "|---|---|---:|---|---:|---:|---|",
    ]
    direct_guard: dict[str, Any] | None = None
    for rule in rules:
        if not isinstance(rule, dict):
            continue
        evidence = f"{rule.get('rides', 0)} Fahrt(en), {rule.get('observations', 0)} Samples"
        lines.append(
            f"| {rule.get('status', 'candidate')} | {rule.get('signal', '')} | {rule.get('channel', '')} | "
            f"{rule.get('encoding', '')}@{rule.get('offset', '')} | {rule.get('confidence', 0)}% | "
            f"{evidence} | {rule.get('source', '')} |"
        )
        if (
            rule.get("signal") == "powerW"
            and str(rule.get("channel") or "").upper() == "1509"
            and int(rule.get("offset") or 0) == 9
            and isinstance(rule.get("evidenceGuard"), dict)
        ):
            direct_guard = rule["evidenceGuard"]

    lines += [
        "",
        "## Ground-Truth-Regeln",
        "",
        "1505/6 u16be = Geschwindigkeit; 1509/0 s16be = Strom; 1509/4 u8 = SOC; "
        "1509/5 u16be = Spannung; 1506/0 u32be = Kilometerstand.",
        "1509/9 u16be ist als SDK-Layout beobachtet, bleibt aber ein Leistungs-Kandidat, bis eine unabhängige "
        "physikalische/semantische Validierung vorliegt.",
        "Die Prozentwerte aus derselben RAW-Extraktion belegen Layoutkonsistenz, nicht automatisch die physikalische Bedeutung.",
        "150D wird nach der ersten echten Fahrt nicht mehr als Live-Geschwindigkeit gelernt.",
    ]

    if direct_guard:
        comparisons = int(direct_guard.get("crossFieldComparisons") or 0)
        close = direct_guard.get("crossFieldClosePercent")
        mae = direct_guard.get("crossFieldMaeW")
        corr = direct_guard.get("crossFieldCorrelation")
        lines += [
            "",
            "## Power-Cross-Check ohne Selbstbestätigung",
            "",
            "1509/9 wird gegen |Spannung × Strom| aus den anderen 1509-Feldern geprüft, nicht gegen den eigenen `power_w`-Export.",
            f"Vergleiche: **{comparisons}** • Nähe: **{close if close is not None else '–'}%** • "
            f"MAE: **{mae if mae is not None else '–'} W** • Korrelation: **{corr if corr is not None else '–'}**.",
            "Auch diese Cross-Field-Übereinstimmung ist noch keine externe Ground Truth und aktiviert keine Regel automatisch.",
        ]

    lines += [
        "",
        "## Sicherheitsregel",
        "",
        "Dieses Profil enthält ausschließlich **Lese-/Decoderregeln**. Kandidaten werden nicht als bestätigte Live-Regeln aktiviert.",
        "",
    ]
    return "\n".join(lines)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--profile", default="decoder-ai/decoder_profile.json")
    parser.add_argument("--libble", default="decoder-ai/libble_comparison.json")
    parser.add_argument("--power-crosscheck", default="decoder-ai/power_crosscheck.json")
    parser.add_argument("--report", default="decoder-ai/analysis_report.md")
    args = parser.parse_args()

    profile_path = Path(args.profile)
    libble_path = Path(args.libble)
    cross_path = Path(args.power_crosscheck)
    profile = json.loads(profile_path.read_text(encoding="utf-8"))
    libble = json.loads(libble_path.read_text(encoding="utf-8"))
    power_crosscheck = (
        json.loads(cross_path.read_text(encoding="utf-8"))
        if cross_path.is_file()
        else {}
    )
    guarded = apply_evidence_guard(profile, libble, power_crosscheck)
    profile_path.write_text(json.dumps(guarded, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    Path(args.report).write_text(render_report(guarded) + "\n", encoding="utf-8")
    print(
        "Evidence guard: "
        f"{guarded.get('confirmedRuleCount', 0)}/{guarded.get('ruleCount', 0)} confirmed, "
        f"revision {guarded.get('revision', '')}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

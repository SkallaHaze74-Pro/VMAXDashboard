import unittest
from unittest import mock

import provider_retry


FOOTER = "Freigabe: keine automatische Änderung."


def complete_review(label="review"):
    return "\n".join([
        "Belastbare Evidenz", f"- {label}: deterministische Messdaten bleiben maßgeblich und werden konkret geprüft.",
        "Konflikte / mögliche Bugs", "- Modell-Konsens ist keine unabhängige Evidenz und darf nichts bestätigen.",
        "Hypothesen (nicht bestätigt)", "- Offene Byte-Semantik bleibt offen und wird nicht aktiviert.",
        "Nächste sichere READ-ONLY-Tests", "- Reproduzierbaren Stillstandstest mit getrenntem Dump ausführen.",
        "Automatische Änderungen: KEINE", FOOTER,
    ])


class ProviderRetryStabilityTests(unittest.TestCase):
    def test_cached_complete_reviewers_are_not_called_again(self):
        result = {
            "providers": {
                "gemini": {"status": "cached_ok", "text": complete_review("g")},
                "glm": {"status": "cached_ok", "text": complete_review("l")},
            },
            "teamSynthesis": {"status": "not_configured", "text": ""},
        }

        with mock.patch.object(provider_retry, "_gemini_resilient") as gemini_call, \
                mock.patch.object(provider_retry, "_glm_free_fallback") as glm_call:
            provider_retry.retry_failed_providers(
                result,
                "prompt",
                "gemini-key",
                "glm-key",
                None,
            )

        gemini_call.assert_not_called()
        glm_call.assert_not_called()

    def test_quota_skips_primary_and_uses_next_gemini_model(self):
        calls = []

        def fake_model(api_key, prompt, model, thinking_level, *, fallback):
            calls.append(model)
            if model == "gemini-3.6-flash":
                return {
                    "status": "ok",
                    "model": model,
                    "provider": "Gemini",
                    "fallback": True,
                    "text": complete_review("fallback"),
                }
            raise RuntimeError("unexpected model")

        with mock.patch.object(provider_retry, "_gemini_model", side_effect=fake_model):
            item = provider_retry._gemini_resilient(
                "key",
                "prompt",
                "Gratis-/Ratenlimit erreicht (429)",
            )

        self.assertEqual("gemini-3.6-flash", item["model"])
        self.assertEqual(["gemini-3.6-flash"], calls)

    def test_previous_complete_review_is_kept_when_fresh_attempt_fails(self):
        current = {
            "inputFingerprint": "same-evidence",
            "providers": {
                "gemini": {"status": "error", "model": "gemini-3.7-flash", "error": "500 high demand", "text": ""},
                "glm": {"status": "error", "model": "glm-5.3", "error": "timeout", "text": ""},
            }
        }
        previous = {
            "inputFingerprint": "same-evidence",
            "providers": {
                "gemini": {"status": "ok", "model": "gemini-3.7-flash", "text": complete_review("old"), "outputComplete": True},
                "glm": {"status": "ok", "model": "glm-4.7-flash", "text": complete_review("old glm"), "outputComplete": True},
            }
        }

        merged = provider_retry.preserve_last_success(current, previous)
        self.assertEqual("cached_ok", merged["providers"]["gemini"]["status"])
        self.assertEqual("cached_ok", merged["providers"]["glm"]["status"])
        self.assertFalse(merged["providers"]["gemini"]["fresh"])
        self.assertEqual(2, merged["cachedProviderCount"])
        self.assertEqual(0, merged["freshProviderCount"])
        self.assertIn("500 high demand", merged["providers"]["gemini"]["lastAttempt"]["error"])

    def test_compact_retry_prompt_requires_short_complete_answer(self):
        compact = provider_retry._compact_prompt("base")
        self.assertIn("maximal 700 Wörter", compact)
        self.assertIn("Abschlussmarker", compact)

    def test_stale_output_complete_flag_cannot_preserve_structurally_incomplete_text(self):
        current = {"inputFingerprint": "same", "providers": {"gemini": {"status": "error", "error": "timeout", "text": ""}}}
        previous = {
            "inputFingerprint": "same",
            "providers": {
                "gemini": {
                    "status": "ok",
                    "text": "footer-only junk\n" + FOOTER,
                    "outputComplete": True,
                }
            }
        }
        merged = provider_retry.preserve_last_success(current, previous)
        self.assertEqual("error", merged["providers"]["gemini"]["status"])
        self.assertEqual(0, merged["cachedProviderCount"])

    def test_oversized_previous_review_is_not_reused_as_last_good(self):
        oversized = complete_review("old").replace(
            "Automatische Änderungen: KEINE",
            "x" * provider_retry.provider_review.MAX_PROVIDER_TEXT
            + "\nAutomatische Änderungen: KEINE",
        )
        current = {
            "inputFingerprint": "same",
            "providers": {"gemini": {"status": "error", "error": "timeout", "text": ""}},
        }
        previous = {
            "inputFingerprint": "same",
            "providers": {
                "gemini": {"status": "ok", "text": oversized, "outputComplete": True},
            },
        }

        merged = provider_retry.preserve_last_success(current, previous)

        self.assertEqual("error", merged["providers"]["gemini"]["status"])
        self.assertEqual(0, merged["cachedProviderCount"])

    def test_complete_previous_review_is_not_reused_for_different_evidence(self):
        current = {
            "inputFingerprint": "new-evidence",
            "providers": {"gemini": {"status": "error", "error": "timeout", "text": ""}},
        }
        previous = {
            "inputFingerprint": "old-evidence",
            "providers": {
                "gemini": {"status": "ok", "text": complete_review("old"), "outputComplete": True},
            },
        }

        merged = provider_retry.preserve_last_success(current, previous)

        self.assertEqual("error", merged["providers"]["gemini"]["status"])
        self.assertEqual(0, merged["cachedProviderCount"])

    def test_changed_prompt_invalidates_old_provider_text_before_retry(self):
        result = {
            "inputFingerprint": "old",
            "providers": {
                "gemini": {"status": "ok", "model": "gemini-old", "text": complete_review("old")},
                "glm": {"status": "ok", "model": "glm-old", "text": complete_review("old")},
            },
            "teamSynthesis": {
                "status": "ok",
                "role": "synthesis_only",
                "text": complete_review("old synthesis"),
            },
        }

        bound = provider_retry.bind_result_to_prompt(result, "new prompt")

        self.assertEqual("stale_input", bound["providers"]["gemini"]["status"])
        self.assertEqual("", bound["providers"]["gemini"]["text"])
        self.assertEqual("stale_input", bound["teamSynthesis"]["status"])
        self.assertEqual("", bound["teamSynthesis"]["text"])
        self.assertTrue(bound["inputChanged"])

    def test_complete_synthesis_is_preserved_only_for_same_evidence_and_drafts(self):
        providers = {
            "gemini": {"status": "ok", "model": "g", "text": complete_review("g")},
            "glm": {"status": "ok", "model": "l", "text": complete_review("l")},
        }
        synthesis_fingerprint = provider_retry.provider_review.team_synthesis_fingerprint(providers)
        current = {
            "inputFingerprint": "same-evidence",
            "providers": providers,
            "teamSynthesis": {
                "status": "error",
                "inputFingerprint": synthesis_fingerprint,
                "text": "",
                "error": "503",
            },
        }
        previous = {
            "inputFingerprint": "same-evidence",
            "providers": providers,
            "teamSynthesis": {
                "status": "ok",
                "role": "synthesis_only",
                "inputFingerprint": synthesis_fingerprint,
                "text": complete_review("old synthesis"),
                "outputComplete": True,
            },
        }

        merged = provider_retry.preserve_last_success(current, previous)

        self.assertEqual("cached_ok", merged["teamSynthesis"]["status"])
        self.assertEqual(complete_review("old synthesis"), merged["teamSynthesis"]["text"])
        self.assertFalse(merged["teamSynthesis"]["fresh"])
        self.assertFalse(merged["teamSynthesis"]["countsAsIndependentEvidence"])


if __name__ == "__main__":
    unittest.main()

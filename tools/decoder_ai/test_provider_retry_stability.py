import unittest
from unittest import mock

import provider_retry


FOOTER = "Freigabe: keine automatische Änderung."


class ProviderRetryStabilityTests(unittest.TestCase):
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
                    "text": "review\n" + FOOTER,
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
            "providers": {
                "gemini": {"status": "error", "model": "gemini-3.7-flash", "error": "500 high demand", "text": ""},
                "glm": {"status": "error", "model": "glm-5.3", "error": "timeout", "text": ""},
            }
        }
        previous = {
            "providers": {
                "gemini": {"status": "ok", "model": "gemini-3.7-flash", "text": "old\n" + FOOTER, "outputComplete": True},
                "glm": {"status": "ok", "model": "glm-4.7-flash", "text": "old glm\n" + FOOTER, "outputComplete": True},
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


if __name__ == "__main__":
    unittest.main()

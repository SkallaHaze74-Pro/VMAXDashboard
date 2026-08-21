import unittest

import provider_retry
import provider_review


FOOTER = "Freigabe: keine automatische Änderung."


class ProviderRetryTests(unittest.TestCase):
    def test_gemini_quota_error_uses_36_fallback(self):
        original_post = provider_review.post_json
        provider_review.post_json = lambda url, headers, payload: {
            "status": "completed",
            "steps": [{
                "type": "model_output",
                "content": [{
                    "type": "text",
                    "text": "Gemini fallback ok\n" + FOOTER,
                }],
            }],
        }
        try:
            result = provider_retry.retry_failed_providers(
                {"providers": {"gemini": {"status": "error", "error": "Gratis-/Ratenlimit erreicht (429)"}}},
                "prompt",
                "gem-key",
                None,
            )
        finally:
            provider_review.post_json = original_post

        gemini = result["providers"]["gemini"]
        self.assertEqual("ok", gemini["status"])
        self.assertEqual(provider_retry.GEMINI_FALLBACK_MODEL, gemini["model"])
        self.assertTrue(gemini["fallback"])
        self.assertIn("429", gemini["primaryError"])

    def test_incomplete_primary_gemini_answer_is_retried(self):
        original_post = provider_review.post_json
        provider_review.post_json = lambda url, headers, payload: {
            "status": "completed",
            "steps": [{
                "type": "model_output",
                "content": [{"type": "text", "text": "complete fallback\n" + FOOTER}],
            }],
        }
        try:
            result = provider_retry.retry_failed_providers(
                {"providers": {"gemini": {"status": "ok", "model": "gemini-3.7-flash", "text": "abgeschnitten"}}},
                "prompt",
                "gem-key",
                None,
            )
        finally:
            provider_review.post_json = original_post

        gemini = result["providers"]["gemini"]
        self.assertEqual("ok", gemini["status"])
        self.assertTrue(gemini["text"].endswith(FOOTER))
        self.assertIn("Unvollständige Primärantwort", gemini["primaryError"])

    def test_glm_timeout_uses_free_fallback(self):
        original_call = provider_review.call_glm_model

        def fake_call(api_key, prompt, model, allow_bigmodel_fallback):
            self.assertEqual(provider_review.GLM_FREE_MODEL, model)
            return "Z.ai", "GLM fallback ok\n" + FOOTER

        provider_review.call_glm_model = fake_call
        try:
            result = provider_retry.retry_failed_providers(
                {"providers": {"glm": {"status": "error", "error": "read operation timed out"}}},
                "prompt",
                None,
                "glm-key",
            )
        finally:
            provider_review.call_glm_model = original_call

        glm = result["providers"]["glm"]
        self.assertEqual("ok", glm["status"])
        self.assertEqual(provider_review.GLM_FREE_MODEL, glm["model"])
        self.assertTrue(glm["fallback"])
        self.assertIn("timed out", glm["primaryError"])

    def test_successful_provider_is_not_retried(self):
        result = provider_retry.retry_failed_providers(
            {"providers": {"glm": {"status": "ok", "model": "existing", "text": "done\n" + FOOTER}}},
            "prompt",
            None,
            "glm-key",
        )
        self.assertEqual("existing", result["providers"]["glm"]["model"])


if __name__ == "__main__":
    unittest.main()

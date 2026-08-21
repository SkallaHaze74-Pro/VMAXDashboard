import unittest

import provider_retry
import provider_review


class ProviderRetryTests(unittest.TestCase):
    def test_gemini_error_uses_36_fallback(self):
        original_post = provider_review.post_json
        provider_review.post_json = lambda url, headers, payload: {
            "status": "completed",
            "steps": [{
                "type": "model_output",
                "content": [{
                    "type": "text",
                    "text": "Gemini fallback ok\nFreigabe: keine automatische Änderung.",
                }],
            }],
        }
        try:
            result = provider_retry.retry_failed_providers(
                {"providers": {"gemini": {"status": "error", "error": "high demand"}}},
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
        self.assertIn("high demand", gemini["primaryError"])

    def test_glm_timeout_uses_free_fallback(self):
        original_call = provider_review.call_glm_model

        def fake_call(api_key, prompt, model, allow_bigmodel_fallback):
            self.assertEqual(provider_review.GLM_FREE_MODEL, model)
            return "Z.ai", "GLM fallback ok\nFreigabe: keine automatische Änderung."

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
            {"providers": {"glm": {"status": "ok", "model": "existing", "text": "done"}}},
            "prompt",
            None,
            "glm-key",
        )
        self.assertEqual("existing", result["providers"]["glm"]["model"])


if __name__ == "__main__":
    unittest.main()

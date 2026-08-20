import tempfile
import unittest
from pathlib import Path

import provider_review


class ProviderReviewTest(unittest.TestCase):
    def test_build_prompt_contains_all_sources_and_no_secret_fields(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            analysis = root / "analysis.md"
            profile = root / "profile.json"
            libble = root / "libble.json"
            original = root / "original.json"
            analysis.write_text("analysis-evidence", encoding="utf-8")
            profile.write_text('{"rules": []}', encoding="utf-8")
            libble.write_text('{"matches": 3}', encoding="utf-8")
            original.write_text('{"semantics": "read-only"}', encoding="utf-8")

            prompt = provider_review.build_prompt(analysis, profile, libble, original)

            self.assertIn("analysis-evidence", prompt)
            self.assertIn('"rules": []', prompt)
            self.assertIn('"matches": 3', prompt)
            self.assertIn('"semantics": "read-only"', prompt)
            self.assertNotIn("API_KEY", prompt)

    def test_read_limited_truncates_large_input(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "large.txt"
            path.write_text("x" * 200, encoding="utf-8")
            text = provider_review.read_limited(path, 50)
            self.assertTrue(text.startswith("x" * 50))
            self.assertIn("gekürzt", text)

    def test_extract_gemini_text_reads_interactions_steps(self):
        data = {
            "status": "completed",
            "steps": [
                {"type": "google_search_call", "arguments": {"queries": ["ignored"]}},
                {
                    "type": "model_output",
                    "content": [
                        {"type": "text", "text": "Teil eins"},
                        {"type": "text", "text": "Teil zwei"},
                    ],
                },
            ],
        }
        text = provider_review.extract_gemini_text(data)
        self.assertEqual("Teil eins\nTeil zwei", text)

    def test_extract_gemini_text_rejects_failed_interaction(self):
        with self.assertRaisesRegex(RuntimeError, "Status failed"):
            provider_review.extract_gemini_text({"status": "failed", "steps": []})

    def test_glm_prefers_zai_endpoint(self):
        calls = []
        original = provider_review.post_json

        def fake_post(url, headers, payload):
            calls.append((url, payload["model"]))
            return {"choices": [{"message": {"content": "Z.ai ok"}}]}

        provider_review.post_json = fake_post
        try:
            result = provider_review.ask_glm_with_meta("id.secret", "prompt")
        finally:
            provider_review.post_json = original

        self.assertEqual("Z.ai ok", result["text"])
        self.assertEqual(provider_review.GLM_MODEL, result["model"])
        self.assertFalse(result["fallback"])
        self.assertEqual([(provider_review.ZAI_GLM_URL, provider_review.GLM_MODEL)], calls)

    def test_glm_falls_back_to_bigmodel_on_auth_mismatch(self):
        calls = []
        original = provider_review.post_json

        def fake_post(url, headers, payload):
            calls.append((url, payload["model"]))
            if url == provider_review.ZAI_GLM_URL:
                raise provider_review.ProviderHttpError(401, "wrong platform")
            return {"choices": [{"message": {"content": "BigModel ok"}}]}

        provider_review.post_json = fake_post
        try:
            result = provider_review.ask_glm_with_meta("id.secret", "prompt")
        finally:
            provider_review.post_json = original

        self.assertEqual("BigModel ok", result["text"])
        self.assertEqual("BigModel", result["provider"])
        self.assertEqual(
            [
                (provider_review.ZAI_GLM_URL, provider_review.GLM_MODEL),
                (provider_review.BIGMODEL_GLM_URL, provider_review.GLM_MODEL),
            ],
            calls,
        )

    def test_glm_uses_free_47_after_primary_quota(self):
        calls = []
        original = provider_review.post_json

        def fake_post(url, headers, payload):
            model = payload["model"]
            calls.append((url, model))
            if model == provider_review.GLM_MODEL:
                raise provider_review.ProviderHttpError(429, "余额不足，请充值")
            if model == provider_review.GLM_FREE_MODEL:
                return {"choices": [{"message": {"content": "free 4.7"}}]}
            raise AssertionError(model)

        provider_review.post_json = fake_post
        try:
            result = provider_review.ask_glm_with_meta("id.secret", "prompt")
        finally:
            provider_review.post_json = original

        self.assertEqual(provider_review.GLM_FREE_MODEL, result["model"])
        self.assertEqual("free 4.7", result["text"])
        self.assertTrue(result["fallback"])
        self.assertEqual(
            [
                (provider_review.ZAI_GLM_URL, provider_review.GLM_MODEL),
                (provider_review.ZAI_GLM_URL, provider_review.GLM_FREE_MODEL),
            ],
            calls,
        )

    def test_glm_uses_free_45_if_free_47_is_unavailable(self):
        calls = []
        original = provider_review.post_json

        def fake_post(url, headers, payload):
            model = payload["model"]
            calls.append(model)
            if model in (provider_review.GLM_MODEL, provider_review.GLM_FREE_MODEL):
                raise provider_review.ProviderHttpError(429, "quota")
            if model == provider_review.GLM_FREE_BACKUP_MODEL:
                return {"choices": [{"message": {"content": "free 4.5"}}]}
            raise AssertionError(model)

        provider_review.post_json = fake_post
        try:
            result = provider_review.ask_glm_with_meta("id.secret", "prompt")
        finally:
            provider_review.post_json = original

        self.assertEqual(provider_review.GLM_FREE_BACKUP_MODEL, result["model"])
        self.assertEqual("free 4.5", result["text"])
        self.assertTrue(result["fallback"])
        self.assertEqual(
            [provider_review.GLM_MODEL, provider_review.GLM_FREE_MODEL, provider_review.GLM_FREE_BACKUP_MODEL],
            calls,
        )

    def test_missing_provider_key_is_non_fatal(self):
        result = provider_review.run_provider(
            "Gemini",
            provider_review.GEMINI_MODEL,
            None,
            lambda *_: "should-not-run",
            "prompt",
        )
        self.assertEqual("not_configured", result["status"])
        self.assertEqual("", result["text"])

    def test_provider_failure_is_advisory(self):
        original = provider_review.ask_glm_with_meta

        def fail(*_):
            raise RuntimeError("quota")

        provider_review.ask_glm_with_meta = fail
        try:
            result = provider_review.run_provider(
                "GLM",
                provider_review.GLM_MODEL,
                "test-key-not-real",
                provider_review.ask_glm,
                "prompt",
            )
        finally:
            provider_review.ask_glm_with_meta = original

        self.assertEqual("error", result["status"])
        self.assertIn("quota", result["error"])

    def test_markdown_marks_free_glm_fallback(self):
        result = {
            "providers": {
                "gemini": {"status": "ok", "text": "G", "model": "gemini"},
                "glm": {
                    "status": "ok",
                    "text": "L",
                    "model": provider_review.GLM_FREE_MODEL,
                    "fallback": True,
                    "provider": "Z.ai",
                },
            }
        }
        report = provider_review.render_markdown(result)
        self.assertIn("Advisory only", report)
        self.assertIn(provider_review.GLM_FREE_MODEL, report)
        self.assertIn("Kostenloser GLM-Fallback aktiv", report)


if __name__ == "__main__":
    unittest.main()

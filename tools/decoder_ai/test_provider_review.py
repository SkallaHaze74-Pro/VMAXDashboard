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
        def fail(*_):
            raise RuntimeError("quota")

        result = provider_review.run_provider(
            "GLM",
            provider_review.GLM_MODEL,
            "test-key-not-real",
            fail,
            "prompt",
        )
        self.assertEqual("error", result["status"])
        self.assertIn("quota", result["error"])

    def test_markdown_marks_external_models_advisory_only(self):
        result = {
            "providers": {
                "gemini": {"status": "ok", "text": "G", "model": "gemini"},
                "glm": {"status": "ok", "text": "L", "model": "glm"},
            }
        }
        report = provider_review.render_markdown(result)
        self.assertIn("Advisory only", report)
        self.assertIn("Gemini 3.7 Flash", report)
        self.assertIn("GLM-5.3", report)


if __name__ == "__main__":
    unittest.main()

import tempfile
import unittest
from pathlib import Path

import provider_review


def complete_review(label="review"):
    return "\n".join([
        "Belastbare Evidenz",
        f"- {label}: deterministische Messdaten bleiben maßgeblich und werden konkret geprüft.",
        "Konflikte / mögliche Bugs",
        "- Modell-Konsens ist keine unabhängige Evidenz und darf nichts bestätigen.",
        "Hypothesen (nicht bestätigt)",
        "- Offene Byte-Semantik bleibt offen und wird nicht aktiviert.",
        "Nächste sichere READ-ONLY-Tests",
        "- Reproduzierbaren Stillstandstest mit getrenntem Dump ausführen.",
        "Automatische Änderungen: KEINE",
        "Freigabe: keine automatische Änderung.",
    ])


class ProviderReviewTest(unittest.TestCase):
    @staticmethod
    def glm_success(text, finish_reason="stop"):
        return {
            "choices": [{
                "finish_reason": finish_reason,
                "message": {"content": text},
            }],
        }

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
            self.assertIn("BLE verschwindet", prompt)
            self.assertIn("kein Live-Monitoring während des Ladens", prompt)

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

    def test_non_completed_provider_responses_are_rejected(self):
        with self.assertRaisesRegex(RuntimeError, "nicht vollständig"):
            provider_review.extract_gemini_text({"status": "in_progress", "steps": []})
        with self.assertRaisesRegex(RuntimeError, "nicht vollständig"):
            provider_review.extract_gemini_text({
                "steps": [{
                    "type": "model_output",
                    "content": [{"type": "text", "text": "Text ohne Status"}],
                }],
            })
        with self.assertRaisesRegex(RuntimeError, "nicht vollständig"):
            provider_review.extract_gemini_text({"status": "COMPLETED", "steps": []})
        with self.assertRaisesRegex(RuntimeError, "nicht vollständig"):
            provider_review.extract_glm_text({
                "choices": [{
                    "finish_reason": "length",
                    "message": {"content": "abgeschnitten"},
                }],
            })
        with self.assertRaisesRegex(RuntimeError, "nicht vollständig"):
            provider_review.extract_glm_text({
                "choices": [{"message": {"content": "Text ohne Abschlussstatus"}}],
            })
        with self.assertRaisesRegex(RuntimeError, "nicht vollständig"):
            provider_review.extract_glm_text(self.glm_success("Text", finish_reason="STOP"))

        with self.assertRaisesRegex(RuntimeError, "nicht vollständig"):
            provider_review.extract_openai_text({
                "status": "incomplete",
                "output": [{
                    "type": "message",
                    "content": [{"type": "output_text", "text": complete_review("partial")}],
                }],
            })

    def test_openai_completed_response_text_is_extracted(self):
        data = {
            "status": "completed",
            "output": [
                {"type": "reasoning", "summary": []},
                {
                    "type": "message",
                    "content": [{"type": "output_text", "text": complete_review("team")}],
                },
            ],
        }

        self.assertEqual(complete_review("team"), provider_review.extract_openai_text(data))

    def test_openai_request_is_non_storing_low_effort_synthesis(self):
        captured = {}
        original = provider_review.post_json

        def fake_post(url, headers, payload):
            captured.update({"url": url, "headers": headers, "payload": payload})
            return {
                "status": "completed",
                "output": [{
                    "type": "message",
                    "content": [{"type": "output_text", "text": complete_review("team")}],
                }],
            }

        provider_review.post_json = fake_post
        try:
            text = provider_review.ask_openai("secret", "two drafts only")
        finally:
            provider_review.post_json = original

        self.assertEqual(complete_review("team"), text)
        self.assertEqual(provider_review.OPENAI_URL, captured["url"])
        self.assertEqual("Bearer secret", captured["headers"]["Authorization"])
        self.assertFalse(captured["payload"]["store"])
        self.assertEqual({"effort": "low"}, captured["payload"]["reasoning"])
        self.assertEqual("two drafts only", captured["payload"]["input"])

    def test_team_synthesis_gets_only_two_complete_drafts_and_runs_once(self):
        calls = []
        providers = {
            "gemini": {"status": "ok", "text": complete_review("gemini")},
            "glm": {"status": "ok", "text": complete_review("glm")},
        }

        def fake_ask(api_key, prompt):
            calls.append((api_key, prompt))
            return complete_review("openai synthesis")

        result = provider_review.run_team_synthesis(
            "openai-test-key",
            providers,
            ask=fake_ask,
        )

        self.assertEqual("ok", result["status"])
        self.assertEqual(1, len(calls))
        self.assertIn(complete_review("gemini"), calls[0][1])
        self.assertIn(complete_review("glm"), calls[0][1])
        self.assertNotIn("Deterministischer Analysebericht", calls[0][1])
        self.assertEqual("synthesis_only", result["role"])

    def test_team_synthesis_is_skipped_without_two_complete_drafts(self):
        calls = []
        providers = {
            "gemini": {"status": "ok", "text": complete_review("gemini")},
            "glm": {"status": "error", "text": "", "error": "timeout"},
        }

        result = provider_review.run_team_synthesis(
            "openai-test-key",
            providers,
            ask=lambda *_: calls.append("unexpected"),
        )

        self.assertEqual("skipped", result["status"])
        self.assertEqual("insufficient_independent_drafts", result["reason"])
        self.assertEqual([], calls)

    def test_matching_complete_team_synthesis_is_not_called_again(self):
        providers = {
            "gemini": {"status": "ok", "model": "g", "text": complete_review("gemini")},
            "glm": {"status": "ok", "model": "l", "text": complete_review("glm")},
        }
        fingerprint = provider_review.team_synthesis_fingerprint(providers)
        result = {
            "providers": providers,
            "teamSynthesis": {
                "status": "ok",
                "role": "synthesis_only",
                "inputFingerprint": fingerprint,
                "text": complete_review("cached synthesis"),
            },
        }
        calls = []

        refreshed = provider_review.refresh_team_synthesis(
            result,
            "openai-test-key",
            ask=lambda *_: calls.append("unexpected"),
        )

        self.assertEqual([], calls)
        self.assertEqual(
            complete_review("cached synthesis"),
            refreshed["teamSynthesis"]["text"],
        )

    def test_matching_failed_synthesis_is_not_retried_in_same_workflow(self):
        providers = {
            "gemini": {"status": "ok", "model": "g", "text": complete_review("gemini")},
            "glm": {"status": "ok", "model": "l", "text": complete_review("glm")},
        }
        fingerprint = provider_review.team_synthesis_fingerprint(providers)
        result = {
            "providers": providers,
            "teamSynthesis": {
                "status": "error",
                "role": "synthesis_only",
                "inputFingerprint": fingerprint,
                "text": "",
                "error": "503",
            },
        }
        calls = []

        refreshed = provider_review.refresh_team_synthesis(
            result,
            "openai-test-key",
            ask=lambda *_: calls.append("unexpected"),
        )

        self.assertEqual([], calls)
        self.assertEqual("error", refreshed["teamSynthesis"]["status"])

    def test_same_evidence_reuses_complete_panel_without_external_calls(self):
        providers = {
            "gemini": {
                "status": "ok",
                "provider": "Gemini",
                "model": "g",
                "text": complete_review("gemini"),
            },
            "glm": {
                "status": "ok",
                "provider": "Z.ai",
                "model": "l",
                "text": complete_review("glm"),
            },
        }
        previous = {
            "inputFingerprint": provider_review.prompt_fingerprint("same prompt"),
            "providers": providers,
            "teamSynthesis": {
                "status": "ok",
                "role": "synthesis_only",
                "inputFingerprint": provider_review.team_synthesis_fingerprint(providers),
                "text": complete_review("old synthesis"),
            },
        }
        provider_calls = []
        synthesis_calls = []

        result = provider_review.build_review_result(
            "same prompt",
            previous=previous,
            gemini_key="g-key",
            glm_key="l-key",
            openai_key="o-key",
            provider_runner=lambda *args: provider_calls.append(args),
            synthesis_ask=lambda *args: synthesis_calls.append(args),
        )

        self.assertEqual([], provider_calls)
        self.assertEqual([], synthesis_calls)
        self.assertEqual("cached_ok", result["providers"]["gemini"]["status"])
        self.assertEqual("cached_ok", result["providers"]["glm"]["status"])
        self.assertEqual("cached_ok", result["teamSynthesis"]["status"])
        self.assertEqual(2, result["independentReviewerCount"])
        self.assertFalse(result["modelConsensusCountsAsEvidence"])
        self.assertFalse(result["automaticChangeAuthority"])

    def test_cached_reviewers_allow_exactly_one_missing_synthesis_call(self):
        providers = {
            "gemini": {"status": "ok", "provider": "Gemini", "model": "g", "text": complete_review("g")},
            "glm": {"status": "ok", "provider": "Z.ai", "model": "l", "text": complete_review("l")},
        }
        previous = {
            "inputFingerprint": provider_review.prompt_fingerprint("same prompt"),
            "providers": providers,
            "teamSynthesis": {"status": "not_configured", "text": ""},
        }
        synthesis_calls = []

        result = provider_review.build_review_result(
            "same prompt",
            previous=previous,
            gemini_key="g-key",
            glm_key="l-key",
            openai_key="o-key",
            provider_runner=lambda *args: self.fail(f"unexpected provider call: {args}"),
            synthesis_ask=lambda _key, _prompt: synthesis_calls.append("openai") or complete_review("new synthesis"),
        )

        self.assertEqual(["openai"], synthesis_calls)
        self.assertEqual("ok", result["teamSynthesis"]["status"])

    def test_failed_synthesis_is_attempted_only_once_for_identical_drafts(self):
        calls = []
        previous = {}

        def provider_runner(name, model, _key, _ask, _prompt):
            return {
                "status": "ok",
                "provider": name,
                "model": model,
                "text": complete_review(name),
            }

        def failing_synthesis(_key, _prompt):
            calls.append("openai")
            raise RuntimeError("503")

        for _ in range(3):
            previous = provider_review.build_review_result(
                "same prompt",
                previous=previous,
                gemini_key="g-key",
                glm_key="l-key",
                openai_key="o-key",
                provider_runner=provider_runner,
                synthesis_ask=failing_synthesis,
            )

        self.assertEqual(["openai"], calls)
        self.assertEqual("attempted_error", previous["teamSynthesis"]["status"])

    def test_manual_override_may_retry_failed_synthesis_once(self):
        providers = {
            "gemini": {"status": "ok", "provider": "Gemini", "model": "g", "text": complete_review("g")},
            "glm": {"status": "ok", "provider": "GLM", "model": "l", "text": complete_review("l")},
        }
        previous = {
            "inputFingerprint": provider_review.prompt_fingerprint("same prompt"),
            "providers": providers,
            "teamSynthesis": {
                "status": "attempted_error",
                "role": "synthesis_only",
                "inputFingerprint": provider_review.team_synthesis_fingerprint(providers),
                "text": "",
                "error": "503",
            },
        }
        calls = []

        result = provider_review.build_review_result(
            "same prompt",
            previous=previous,
            gemini_key="g-key",
            glm_key="l-key",
            openai_key="o-key",
            provider_runner=lambda *args: self.fail(f"unexpected reviewer call: {args}"),
            synthesis_ask=lambda _key, _prompt: calls.append("openai") or complete_review("manual retry"),
            force_synthesis=True,
        )

        self.assertEqual(["openai"], calls)
        self.assertEqual("ok", result["teamSynthesis"]["status"])

    def test_oversized_provider_text_is_rejected_instead_of_truncated(self):
        oversized = "x" * (provider_review.MAX_PROVIDER_TEXT + 1)
        with self.assertRaisesRegex(RuntimeError, "zu lang"):
            provider_review.extract_gemini_text({
                "status": "completed",
                "steps": [{
                    "type": "model_output",
                    "content": [{"type": "text", "text": oversized}],
                }],
            })
        with self.assertRaisesRegex(RuntimeError, "zu lang"):
            provider_review.extract_glm_text(self.glm_success(oversized))

    def test_prompt_fingerprint_changes_with_evidence(self):
        self.assertEqual(
            provider_review.prompt_fingerprint("same"),
            provider_review.prompt_fingerprint("same"),
        )
        self.assertNotEqual(
            provider_review.prompt_fingerprint("old"),
            provider_review.prompt_fingerprint("new"),
        )

    def test_glm_prefers_zai_endpoint(self):
        calls = []
        original = provider_review.post_json

        def fake_post(url, headers, payload):
            calls.append((url, payload["model"]))
            return self.glm_success("Z.ai ok")

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
            return self.glm_success("BigModel ok")

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
                return self.glm_success("free 4.7")
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
                return self.glm_success("free 4.5")
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
        self.assertIn("Fallbackmodell aktiv", report)


if __name__ == "__main__":
    unittest.main()

import unittest

import review_output_guard


def complete_review(label="review"):
    return "\n".join([
        "Belastbare Evidenz", f"- {label}: deterministische Messdaten bleiben maßgeblich und werden konkret geprüft.",
        "Konflikte / mögliche Bugs", "- Modell-Konsens ist keine unabhängige Evidenz und darf nichts bestätigen.",
        "Hypothesen (nicht bestätigt)", "- Offene Byte-Semantik bleibt offen und wird nicht aktiviert.",
        "Nächste sichere READ-ONLY-Tests", "- Reproduzierbaren Stillstandstest mit getrenntem Dump ausführen.",
        "Automatische Änderungen: KEINE", review_output_guard.REQUIRED_FOOTER,
    ])


class ReviewOutputGuardTests(unittest.TestCase):
    def test_complete_reviewer_answer_stays_ok(self):
        result = {
            "providers": {
                "gemini": {
                    "status": "ok",
                    "model": "gemini-3.7-flash",
                    "text": complete_review("Gemini"),
                }
            }
        }
        guarded = review_output_guard.validate(result)
        item = guarded["providers"]["gemini"]
        self.assertEqual("ok", item["status"])
        self.assertTrue(item["outputComplete"])
        self.assertTrue(guarded["outputContractValidated"])
        self.assertFalse(guarded["automaticChangeAuthority"])

    def test_truncated_reviewer_answer_is_not_marked_successful(self):
        result = {
            "providers": {
                "gemini": {
                    "status": "ok",
                    "model": "gemini-3.7-flash",
                    "text": "Belastbare Evidenz\n- Cross-Field-Plausibilität: 1509/9 Skalierung 1.",
                },
                "glm": {
                    "status": "ok",
                    "model": "glm-4.5-flash",
                    "text": complete_review("GLM"),
                },
            }
        }
        guarded = review_output_guard.validate(result)
        gemini = guarded["providers"]["gemini"]
        glm = guarded["providers"]["glm"]
        self.assertEqual("error", gemini["status"])
        self.assertFalse(gemini["outputComplete"])
        self.assertEqual("", gemini["text"])
        self.assertIn("Unvollständige Reviewer-Antwort", gemini["error"])
        self.assertEqual("ok", glm["status"])
        self.assertTrue(glm["outputComplete"])

    def test_footer_only_junk_and_missing_required_section_are_rejected(self):
        result = {
            "providers": {
                "gemini": {"status": "ok", "text": "Junk\n" + review_output_guard.REQUIRED_FOOTER},
                "glm": {
                    "status": "ok",
                    "text": complete_review().replace("Automatische Änderungen: KEINE\n", ""),
                },
            }
        }
        guarded = review_output_guard.validate(result)
        self.assertEqual("error", guarded["providers"]["gemini"]["status"])
        self.assertEqual("error", guarded["providers"]["glm"]["status"])

    def test_oversized_complete_text_is_rejected_instead_of_truncated(self):
        oversized = complete_review().replace(
            "Automatische Änderungen: KEINE",
            "x" * review_output_guard.MAX_PROVIDER_TEXT + "\nAutomatische Änderungen: KEINE",
        )
        guarded = review_output_guard.validate({
            "providers": {"gemini": {"status": "ok", "text": oversized}},
        })

        self.assertEqual("error", guarded["providers"]["gemini"]["status"])
        self.assertEqual("", guarded["providers"]["gemini"]["text"])
        self.assertFalse(guarded["providers"]["gemini"]["outputComplete"])

    def test_existing_provider_error_remains_error(self):
        result = {
            "providers": {
                "gemini": {"status": "error", "error": "500", "text": ""}
            }
        }
        guarded = review_output_guard.validate(result)
        self.assertEqual("error", guarded["providers"]["gemini"]["status"])
        self.assertFalse(guarded["providers"]["gemini"]["outputComplete"])

    def test_team_synthesis_is_validated_separately_from_reviewers(self):
        result = {
            "providers": {
                "gemini": {"status": "ok", "text": complete_review("Gemini")},
                "glm": {"status": "ok", "text": complete_review("GLM")},
            },
            "teamSynthesis": {
                "status": "ok",
                "role": "synthesis_only",
                "countsAsIndependentEvidence": False,
                "text": "abgeschnitten",
            },
        }

        guarded = review_output_guard.validate(result)

        self.assertEqual("ok", guarded["providers"]["gemini"]["status"])
        self.assertEqual("ok", guarded["providers"]["glm"]["status"])
        self.assertEqual("error", guarded["teamSynthesis"]["status"])
        self.assertEqual("", guarded["teamSynthesis"]["text"])
        self.assertFalse(guarded["teamSynthesis"]["outputComplete"])
        self.assertFalse(guarded["teamSynthesis"]["countsAsIndependentEvidence"])


if __name__ == "__main__":
    unittest.main()

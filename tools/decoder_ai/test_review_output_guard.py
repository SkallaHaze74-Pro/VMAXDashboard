import unittest

import review_output_guard


class ReviewOutputGuardTests(unittest.TestCase):
    def test_complete_reviewer_answer_stays_ok(self):
        result = {
            "providers": {
                "gemini": {
                    "status": "ok",
                    "model": "gemini-3.7-flash",
                    "text": "Belastbare Evidenz\nFreigabe: keine automatische Änderung.",
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
                    "text": "Analyse vollständig.\nFreigabe: keine automatische Änderung.",
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

    def test_existing_provider_error_remains_error(self):
        result = {
            "providers": {
                "gemini": {"status": "error", "error": "500", "text": ""}
            }
        }
        guarded = review_output_guard.validate(result)
        self.assertEqual("error", guarded["providers"]["gemini"]["status"])
        self.assertFalse(guarded["providers"]["gemini"]["outputComplete"])


if __name__ == "__main__":
    unittest.main()

import importlib.util
import sys
import unittest
from pathlib import Path

MODULE_PATH = Path(__file__).with_name("evidence_guard.py")
spec = importlib.util.spec_from_file_location("evidence_guard", MODULE_PATH)
evidence_guard = importlib.util.module_from_spec(spec)
sys.modules["evidence_guard"] = evidence_guard
spec.loader.exec_module(evidence_guard)


class EvidenceGuardTests(unittest.TestCase):
    def test_direct_power_is_demoted_without_independent_semantic_proof(self):
        profile = {
            "revision": "old",
            "rideCount": 13,
            "ruleCount": 2,
            "confirmedRuleCount": 2,
            "rules": [
                {
                    "signal": "speedKmh", "channel": "1505", "offset": 6,
                    "encoding": "u16be", "status": "confirmed", "confidence": 99,
                    "rides": 11, "observations": 1400,
                },
                {
                    "signal": "powerW", "channel": "1509", "offset": 9,
                    "encoding": "u16be", "status": "confirmed", "confidence": 99,
                    "rides": 8, "observations": 939,
                },
            ],
        }
        libble = {
            "aggregate_fields": {
                "1509.direct_power_W": {
                    "match_percent": 89.05,
                    "mae_to_app_live": 11.605336,
                    "independent_semantic_confirmation": False,
                    "verdict": "OBSERVED_NEEDS_MORE_PROOF",
                }
            }
        }

        guarded = evidence_guard.apply_evidence_guard(profile, libble)
        power = next(rule for rule in guarded["rules"] if rule["signal"] == "powerW")
        speed = next(rule for rule in guarded["rules"] if rule["signal"] == "speedKmh")

        self.assertEqual("candidate", power["status"])
        self.assertEqual(89, power["confidence"])
        self.assertEqual("sdk-layout-observed-needs-independent-proof", power["source"])
        self.assertEqual("confirmed", speed["status"])
        self.assertEqual(1, guarded["confirmedRuleCount"])
        self.assertNotEqual("old", guarded["revision"])

    def test_independently_confirmed_direct_power_stays_confirmed(self):
        profile = {
            "revision": "old",
            "rideCount": 2,
            "ruleCount": 1,
            "confirmedRuleCount": 1,
            "rules": [{
                "signal": "powerW", "channel": "1509", "offset": 9,
                "encoding": "u16be", "status": "confirmed", "confidence": 99,
            }],
        }
        libble = {
            "aggregate_fields": {
                "1509.direct_power_W": {
                    "independent_semantic_confirmation": True,
                    "verdict": "BT638_INDEPENDENTLY_CONFIRMED",
                }
            }
        }
        guarded = evidence_guard.apply_evidence_guard(profile, libble)
        self.assertEqual("confirmed", guarded["rules"][0]["status"])
        self.assertEqual(1, guarded["confirmedRuleCount"])

    def test_report_does_not_call_direct_power_ground_truth(self):
        profile = {
            "revision": "rev",
            "rideCount": 13,
            "ruleCount": 1,
            "confirmedRuleCount": 0,
            "rules": [{
                "signal": "powerW", "channel": "1509", "offset": 9,
                "encoding": "u16be", "status": "candidate", "confidence": 89,
                "rides": 8, "observations": 939,
                "source": "sdk-layout-observed-needs-independent-proof",
            }],
        }
        report = evidence_guard.render_report(profile)
        self.assertIn("Leistungs-Kandidat", report)
        self.assertNotIn("1509/9 u16be = direkte Leistung", report)


if __name__ == "__main__":
    unittest.main()

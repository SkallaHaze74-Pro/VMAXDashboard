#!/usr/bin/env python3
import json
import tempfile
import unittest
from pathlib import Path

from original_app_compare import build_comparison, write_report


SEMANTICS_PATH = Path(__file__).with_name("original_app_semantics.json")


class OriginalAppComparisonTests(unittest.TestCase):
    def setUp(self):
        self.semantics = json.loads(SEMANTICS_PATH.read_text(encoding="utf-8"))

    def test_remaining_range_uses_proven_layout_without_claiming_numeric_bt638_value(self):
        signal = next(item for item in self.semantics["signals"] if item["signal"] == "remainingDistanceKm")
        self.assertEqual(
            {
                "channel": "1505",
                "offset": 10,
                "encoding": "u16be",
                "scale": 1.0,
                "invalidRaw": [65535],
                "plausibleKmMax": 1000,
            },
            signal["mapping"],
        )
        self.assertIn("no numeric remaining-range value", signal["evidence"]["bt638Observation"])

        payload = build_comparison(self.semantics, {"aggregate_fields": {}})
        remaining = next(item for item in payload["signals"] if item["signal"] == "remainingDistanceKm")
        self.assertEqual("1505.remaining_range_km", remaining["libbleEvidenceKey"])
        self.assertEqual("MAPPED_VERIFY_WITH_MORE_BT638_DATA", remaining["runtimeStatus"])
        self.assertNotIn("remainingDistanceKm", {item["signal"] for item in payload["unresolvedTargets"]})

    def test_app_export_layout_verdict_is_explicitly_non_independent(self):
        libble = {
            "aggregate_fields": {
                "1505.speed_kmh": {
                    "verdict": "APP_EXPORT_CONSISTENT_WITH_SDK_LAYOUT",
                    "evidence_type": "same_raw_app_export_layout_consistency",
                    "independent_semantic_confirmation": False,
                }
            }
        }
        payload = build_comparison(self.semantics, libble)
        speed = next(item for item in payload["signals"] if item["signal"] == "speedKmh")
        self.assertEqual("APP_EXPORT_LAYOUT_CONSISTENT_NON_INDEPENDENT", speed["runtimeStatus"])
        self.assertFalse(speed["evidenceAssessment"]["independentSemanticConfirmation"])
        self.assertEqual(0, payload["summary"]["confirmedByLiveEvidence"])
        self.assertEqual(1, payload["summary"]["layoutConsistentWithAppExportOnly"])

        with tempfile.TemporaryDirectory() as tmp:
            report = Path(tmp) / "report.md"
            write_report(payload, report)
            self.assertIn("kein unabhängiger Live-Sensornachweis", report.read_text(encoding="utf-8"))

    def test_confirmation_requires_explicit_independent_evidence_flag(self):
        evidence = {"1505.speed_kmh": {"verdict": "BT638_CONFIRMED"}}
        payload = build_comparison(self.semantics, {"aggregate_fields": evidence})
        self.assertEqual(0, payload["summary"]["confirmedByLiveEvidence"])

        evidence["1505.speed_kmh"]["independent_semantic_confirmation"] = True
        payload = build_comparison(self.semantics, {"aggregate_fields": evidence})
        self.assertEqual(1, payload["summary"]["confirmedByLiveEvidence"])

    def test_source_metadata_does_not_equate_hyena_with_bt638_or_da1a(self):
        raw_source = self.semantics["source"]
        self.assertIn("GPSTProtocolHandler", raw_source["nativeSdk"])
        self.assertIn("not proven", raw_source["bundledHyenaSdk"])
        self.assertNotIn("libble-sdk-native-lib.so", raw_source["bundledHyenaSdk"])

        payload = build_comparison(self.semantics, {"aggregate_fields": {}})
        source = payload["source"]
        self.assertIn("V-Core Gear", source["hardwareControllerBranding"])
        self.assertIn("BT638/GPST-DA1A", source["liveProtocolEvidence"])
        self.assertIn("not proven", source["bundledHyenaSdk"])
        self.assertIn("Do not label DA1A/15xx", source["policy"])


if __name__ == "__main__":
    unittest.main()

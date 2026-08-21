import importlib.util
import sys
import unittest
from pathlib import Path

MODULE_PATH = Path(__file__).with_name("provider_review.py")
spec = importlib.util.spec_from_file_location("provider_review", MODULE_PATH)
provider_review = importlib.util.module_from_spec(spec)
sys.modules["provider_review"] = provider_review
spec.loader.exec_module(provider_review)


class ProviderReviewPolicyTests(unittest.TestCase):
    def test_system_prompt_is_strictly_read_only(self):
        prompt = provider_review.SYSTEM_PROMPT
        self.assertIn("rein lesenden Review-Panels", prompt)
        self.assertIn("keinerlei Freigabe-,\nSchreib-, Aktivierungs- oder Entscheidungsbefugnis", prompt)
        self.assertIn("Bestätige, aktiviere, installiere oder ändere niemals selbst eine Decoder-Regel", prompt)
        self.assertIn("keine BLE-Schreibframes", prompt)
        self.assertIn("Freigabe: keine automatische Änderung.", prompt)

    def test_ai_outputs_cannot_self_confirm_or_confirm_each_other(self):
        prompt = provider_review.SYSTEM_PROMPT
        self.assertIn("weder deine eigene frühere Aussage noch die Aussage eines anderen KI-Modells", prompt)
        self.assertIn("weiterhin nur mehrere Meinungen und kein zusätzlicher Messbeweis", prompt)
        built = provider_review.build_prompt(
            Path("missing-analysis"),
            Path("missing-profile"),
            Path("missing-libble"),
            Path("missing-original"),
        )
        self.assertIn("KI-Mehrheit ist kein Freigabekriterium", built)
        self.assertIn("niemals als unabhängige Evidenz oder gegenseitige Bestätigung", built)


if __name__ == "__main__":
    unittest.main()

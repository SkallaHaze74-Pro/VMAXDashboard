from __future__ import annotations

import contextlib
import io
import subprocess
import tempfile
import unittest
from pathlib import Path

from tools import check_repository_hygiene as hygiene


PROJECT_ROOT = Path(__file__).resolve().parents[1]
WRAPPER_BYTES = (PROJECT_ROOT / "gradle/wrapper/gradle-wrapper.jar").read_bytes()


class RepositoryHygieneTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary_directory = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary_directory.name)
        subprocess.run(
            ["git", "init", "-q", self.root],
            check=True,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
        )
        self.original_root = hygiene.ROOT
        hygiene.ROOT = self.root
        self.add_clean_fixture()

    def tearDown(self) -> None:
        hygiene.ROOT = self.original_root
        self.temporary_directory.cleanup()

    def write(self, relative_path: str, content: str | bytes = "fixture\n") -> None:
        path = self.root / relative_path
        path.parent.mkdir(parents=True, exist_ok=True)
        if isinstance(content, bytes):
            path.write_bytes(content)
        else:
            path.write_text(content, encoding="utf-8")

    def stage(self, *paths: str) -> None:
        subprocess.run(
            ["git", "add", "--", *paths],
            cwd=self.root,
            check=True,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
        )

    def add_clean_fixture(self) -> None:
        for path in sorted(hygiene.REQUIRED_PATHS):
            self.write(path)
        self.write("build.gradle.kts", "plugins {}\n")
        self.write("app/build.gradle.kts", "plugins {}\n")
        self.write(
            "app/src/main/AndroidManifest.xml",
            '<application android:allowBackup="false" />\n',
        )
        self.write("gradle/wrapper/gradle-wrapper.jar", WRAPPER_BYTES)
        self.write(
            "gradle/wrapper/gradle-wrapper.properties",
            "distributionUrl=https\\://services.gradle.org/distributions/gradle-8.11.1-bin.zip\n"
            f"distributionSha256Sum={hygiene.EXPECTED_DISTRIBUTION_SHA256}\n",
        )
        launcher = (
            "java -classpath gradle/wrapper/gradle-wrapper.jar "
            "org.gradle.wrapper.GradleWrapperMain\n"
        )
        self.write("gradlew", launcher)
        self.write("gradlew.bat", launcher)
        self.stage(".")

    def run_hygiene(self) -> tuple[int, str]:
        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            result = hygiene.main()
        return result, output.getvalue()

    def test_clean_fixture_passes(self) -> None:
        result, output = self.run_hygiene()
        self.assertEqual(0, result, output)
        self.assertIn("Repository-Hygiene: OK", output)

    def test_tracked_measurement_is_rejected(self) -> None:
        self.write("fahrdaten/test/manifest.json", "{}\n")
        self.stage("fahrdaten/test/manifest.json")
        result, output = self.run_hygiene()
        self.assertEqual(1, result)
        self.assertIn("fahrdaten/", output)

    def test_generated_decoder_output_is_rejected(self) -> None:
        self.write("decoder-ai/analysis_report.md")
        self.stage("decoder-ai/analysis_report.md")
        result, output = self.run_hygiene()
        self.assertEqual(1, result)
        self.assertIn("erzeugte Dateien", output)

    def test_firebase_config_is_rejected(self) -> None:
        self.write("app/google-services.json", "{}\n")
        self.stage("app/google-services.json")
        result, output = self.run_hygiene()
        self.assertEqual(1, result)
        self.assertIn("app/google-services.json", output)

    def test_firebase_dependency_is_rejected(self) -> None:
        self.write(
            "app/build.gradle.kts",
            'implementation("com.google.firebase:firebase-analytics")\n',
        )
        result, output = self.run_hygiene()
        self.assertEqual(1, result)
        self.assertIn("Firebase", output)

    def test_enabled_android_backup_is_rejected(self) -> None:
        self.write(
            "app/src/main/AndroidManifest.xml",
            '<application android:allowBackup="true" />\n',
        )
        result, output = self.run_hygiene()
        self.assertEqual(1, result)
        self.assertIn("Android-Backup", output)

    def test_duplicate_root_document_is_rejected(self) -> None:
        self.write("DATENSCHUTZ_ENTWURF.md")
        self.stage("DATENSCHUTZ_ENTWURF.md")
        result, output = self.run_hygiene()
        self.assertEqual(1, result)
        self.assertIn("doppelte Root-Dokumente", output)

    def test_missing_required_policy_is_rejected(self) -> None:
        subprocess.run(
            ["git", "rm", "-q", "-f", "--cached", "AGENTS.md"],
            cwd=self.root,
            check=True,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
        )
        (self.root / "AGENTS.md").unlink()
        result, output = self.run_hygiene()
        self.assertEqual(1, result)
        self.assertIn("AGENTS.md", output)

    def test_wrong_wrapper_is_rejected(self) -> None:
        self.write("gradle/wrapper/gradle-wrapper.jar", b"not a wrapper")
        result, output = self.run_hygiene()
        self.assertEqual(1, result)
        self.assertIn("Gradle-Wrapper-JAR", output)


if __name__ == "__main__":
    unittest.main()

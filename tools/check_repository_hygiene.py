#!/usr/bin/env python3
"""Fail when main-oriented checkouts contain known repository hygiene regressions."""

from __future__ import annotations

import hashlib
import subprocess
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
EXPECTED_WRAPPER_SHA256 = (
    "2db75c40782f5e8ba1fc278a5574bab070adccb2d21ca5a6e5ed840888448046"
)
EXPECTED_DISTRIBUTION_SHA256 = (
    "f397b287023acdba1e9f6fc5ea72d22dd63669d59ed4a289a29b1a76eee151c6"
)

LEGACY_ROOT_DOCS = {
    "ANALYSE_EDITION.md",
    "APP_ICON_INFO.md",
    "BLE_TESTPLAN_V2.2.md",
    "DATENSCHUTZ_ENTWURF.md",
    "GEFUNDENE_DECODER_6.1.md",
    "GEFUNDENE_DECODER_6.2.md",
    "GEMINI_GLM_PRO_SETUP.md",
    "HANDY_APK_ANLEITUNG.md",
    "LIVE_AI_TEST_EDITION.md",
    "MESSFAHRT_MARKER_EDITION.md",
    "MESSFAHRT_PRO_EDITION.md",
    "TESTER_ANLEITUNG.md",
    "UPDATE_SIGNATUR_ANLEITUNG.md",
}

REQUIRED_PATHS = {
    "AGENTS.md",
    "CLAUDE.md",
    ".github/copilot-instructions.md",
    ".github/workflows/repository-hygiene.yml",
    "app/build.gradle.kts",
    "app/src/main/AndroidManifest.xml",
    "build.gradle.kts",
    "decoder-ai/README.md",
    "docs/README.md",
    "docs/privacy/DATENSCHUTZ_ENTWURF.md",
    "docs/release/UPDATE_SIGNATUR_ANLEITUNG.md",
    "gradle/wrapper/gradle-wrapper.jar",
    "gradle/wrapper/gradle-wrapper.properties",
    "gradlew",
    "gradlew.bat",
}


def tracked_paths() -> set[str]:
    output = subprocess.check_output(
        ["git", "ls-files"], cwd=ROOT, text=True, encoding="utf-8"
    )
    return {line for line in output.splitlines() if line}


def main() -> int:
    tracked = tracked_paths()
    violations: list[str] = []

    for prefix in ("fahrdaten/", "diagnostics/", "build-triggers/"):
        matches = sorted(path for path in tracked if path.startswith(prefix))
        if matches:
            violations.append(f"{prefix} enthält {len(matches)} getrackte Datei(en)")

    generated = sorted(
        path
        for path in tracked
        if path.startswith("decoder-ai/") and path != "decoder-ai/README.md"
    )
    if generated:
        violations.append(
            "decoder-ai/ auf main enthält erzeugte Dateien: " + ", ".join(generated)
        )

    forbidden_exact = {
        "GITHUB-SECRETS.txt",
        "app/google-services.json",
        "app/src/main/java/de/kevin/vmaxdashboard/TelemetryReporter.kt",
        ".github/workflows/vmaxbuild-apk.yml",
    }
    for path in sorted(tracked & forbidden_exact):
        violations.append(f"verbotene getrackte Datei: {path}")

    secret_or_binary = sorted(
        path
        for path in tracked
        if Path(path).suffix.lower() in {".apk", ".aab", ".jks", ".keystore"}
        or Path(path).name == ".env"
        or (Path(path).name.startswith(".env.") and Path(path).name != ".env.example")
    )
    if secret_or_binary:
        violations.append(
            "Secret-/Build-Artefakte sind getrackt: " + ", ".join(secret_or_binary)
        )

    duplicates = sorted(tracked & LEGACY_ROOT_DOCS)
    if duplicates:
        violations.append("doppelte Root-Dokumente: " + ", ".join(duplicates))

    for path in sorted(REQUIRED_PATHS - tracked):
        violations.append(f"erforderliche Projektregel/Dokumentation fehlt: {path}")

    wrapper = ROOT / "gradle/wrapper/gradle-wrapper.jar"
    wrapper_sha = hashlib.sha256(wrapper.read_bytes()).hexdigest() if wrapper.is_file() else ""
    if wrapper.is_file() and wrapper_sha != EXPECTED_WRAPPER_SHA256:
        violations.append(
            f"Gradle-Wrapper-JAR hat SHA-256 {wrapper_sha}, erwartet "
            f"{EXPECTED_WRAPPER_SHA256}"
        )

    properties_path = ROOT / "gradle/wrapper/gradle-wrapper.properties"
    properties = (
        properties_path.read_text(encoding="utf-8") if properties_path.is_file() else ""
    )
    if properties_path.is_file() and "gradle-8.11.1-bin.zip" not in properties:
        violations.append("Gradle-Distribution ist nicht auf 8.11.1 festgelegt")
    checksum_line = f"distributionSha256Sum={EXPECTED_DISTRIBUTION_SHA256}"
    if properties_path.is_file() and checksum_line not in properties:
        violations.append("Gradle-8.11.1-Distributionschecksumme fehlt oder ist falsch")

    for launcher_name in ("gradlew", "gradlew.bat"):
        launcher_path = ROOT / launcher_name
        if not launcher_path.is_file():
            continue
        launcher = launcher_path.read_text(encoding="utf-8")
        if "org.gradle.wrapper.GradleWrapperMain" not in launcher:
            violations.append(f"{launcher_name} startet GradleWrapperMain nicht explizit")
        if "-jar" in launcher and "gradle-wrapper.jar" in launcher:
            violations.append(
                f"{launcher_name} verwendet einen inkompatiblen -jar-Wrapperstart"
            )

    build_files = "\n".join(
        path.read_text(encoding="utf-8")
        for path in (ROOT / "build.gradle.kts", ROOT / "app/build.gradle.kts")
        if path.is_file()
    ).lower()
    if "firebase" in build_files or "com.google.gms.google-services" in build_files:
        violations.append("entfernte Firebase-/Google-Services-Clientintegration ist wieder aktiv")

    manifest_path = ROOT / "app/src/main/AndroidManifest.xml"
    manifest = manifest_path.read_text(encoding="utf-8") if manifest_path.is_file() else ""
    if manifest_path.is_file() and 'android:allowBackup="false"' not in manifest:
        violations.append("Android-Backup ist nicht fail-closed deaktiviert")

    if violations:
        print("Repository-Hygiene: FEHLGESCHLAGEN")
        for violation in violations:
            print(f"- {violation}")
        return 1

    print("Repository-Hygiene: OK")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

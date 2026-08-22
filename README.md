# VMAXDashboard

Android-Werkzeug für BLE-Telemetrie, Messfahrten und beweisorientierte Protokollanalyse am **VMAX New VX2 Gear**. Das konkret beobachtete Gerät ist `BT638`; die Livekommunikation läuft über GPST/DA1A und `15xx`-Characteristics.

## Aktueller Stand

- Geschwindigkeit, Kilometerstand, Akku-Prozent, Spannung und Strom besitzen starke Evidenz aus realen BT638-Messfahrten.
- Direkte Leistung und die unabhängige Berechnung `|Spannung × Strom|` werden getrennt ausgewertet, damit sich kein Rohfeld selbst bestätigt.
- Der GATT-Scanner liest ausschließlich Characteristics mit `PROPERTY_READ` über die bestehende Verbindung und archiviert Antworten als separaten **BT638 Deep READ / BMS & Controller Dump**.
- READ-Antworten werden nicht als Notification-Telemetrie behandelt und nicht in normales Decoder-Lernen gemischt.
- Messfahrten, Deep-READ-Dumps und erzeugte Decoder-Ausgaben werden auf dem Branch `telemetry-data` gehalten.

## Hardware- und Evidenzgrenze

VMAX bezeichnet die konkrete Hardware als V-Core Gear / V-Torque Gear. Die Original-App enthält zusätzlich echten Hyena/Hylink-, Brose-, Hobbywing- und weiteren Multi-Vendor-Code. Diese Quellen werden strikt getrennt:

- BT638-Livepakete und wiederholbare Messdaten belegen Verhalten des Zielgeräts.
- Original-App-/Native-Pfade können eine VMAX-SDK-Fähigkeit belegen.
- Ein Vendor-SDK-String oder natives Symbol belegt noch keine Funktion auf BT638.
- Ein Hersteller-Handshake oder Secret Key gilt nur mit gerätespezifischem Code- und Messnachweis als vorhanden; derzeit wird nichts erfunden oder vorausgesetzt.

Die vollständigen Regeln stehen in [`AGENTS.md`](AGENTS.md).

## Sicherheitsrahmen

Telemetrie, Deep READ, Decoder-Analyse und externe KI-Prüfungen sind read-only. Die einzige aktuell in der Oberfläche angeschlossene Einstellungsänderung ist die bewusst vom Nutzer ausgelöste Zero-/Kick-Start-Umschaltung; sie läuft über eine gesonderte, fail-closed Sicherheitsprüfung. Es gibt keine automatische KI-gesteuerte BLE-Schreiboperation, kein automatisches Tuning und keine Firmwareänderung.

Gemini, GLM und weitere Modelle sind ausschließlich Zweitprüfer. Modellkonsens bestätigt keine Decoder-Regel. Reale BT638-Daten, deterministische Guards und unabhängige Vergleiche entscheiden.

## Datenfluss

| Bereich | Branch / Speicherort | Zweck |
| --- | --- | --- |
| App, Tests, Workflows, Doku | `main` | überprüfbarer Entwicklungsstand |
| Messfahrten | `telemetry-data/fahrdaten/` | RAW-, Live-, Ereignis- und Lernprofildaten |
| Deep READ | `telemetry-data/diagnostics/` und Messfahrtordner | getrennte GATT-READ-Evidenz |
| Decoder-Ausgaben | `telemetry-data/decoder-ai/` | deterministische Berichte und advisory KI-Reviews |
| Lokale App-Daten | privater Android-App-Speicher | Warteschlangen, Einstellungen und Schlüssel |

GitHub-Sync und externe KI-Aufrufe funktionieren erst nach bewusster Einrichtung der jeweiligen Tokens/Keys. Neue Uploads durchlaufen die Privacy-Redaktion. GPS wird für diese Telemetrie nicht erfasst.

**Historische Privacy-Altlast:** Der aktuelle `telemetry-data`-Tip wurde am 22.08.2026 mit demselben Privacy-Contract redigiert, der neue Uploads schützt. Früher veröffentlichte exakte Werte bleiben jedoch in der Git-Historie und im ausdrücklich angelegten Wiederherstellungsref erreichbar. Es wurde kein History-Rewrite und kein Force-Push durchgeführt. Eine Bereinigung der Historie ist ein davon getrennter, destruktiver Vorgang und benötigt erneut eine ausdrückliche Freigabe.

## Projektstruktur

```text
app/                  Android-App und JVM-Tests
docs/                 aktuelle und historische Projektdokumentation
reverse-engineering/  Original-App-, Native- und Protokollevidenz
tools/decoder_ai/     deterministische Analyzer und Provider-Prüfer
.github/workflows/    CI, Decoder-Analyse und signierter Release-Build
```

## Prüfen und bauen

Voraussetzungen sind Java 17, Android SDK 35 und der eingecheckte Gradle-Wrapper.

```bash
python3 tools/check_repository_hygiene.py
PYTHONPATH=tools/decoder_ai python -m unittest discover -s tools/decoder_ai -p 'test_*.py'
./gradlew testDebugUnitTest --stacktrace
./gradlew assembleDebug --stacktrace
```

Der einzige ausliefernde Workflow heißt **VMAX Dashboard Fahrdaten Build**. Auf `main` baut er eine signierte, versionsgebundene Release-APK und prüft Paketname, Versionsdaten sowie das erwartete Signaturzertifikat, bevor das Artefakt bereitgestellt wird.

## Dokumentation

- [`docs/README.md`](docs/README.md) – vollständiger Doku-Index
- [`docs/privacy/DATENSCHUTZ_ENTWURF.md`](docs/privacy/DATENSCHUTZ_ENTWURF.md) – aktueller Datenschutzentwurf
- [`docs/release/UPDATE_SIGNATUR_ANLEITUNG.md`](docs/release/UPDATE_SIGNATUR_ANLEITUNG.md) – signierte Update-APK
- [`docs/ai/GEMINI_GLM_PRO_SETUP.md`](docs/ai/GEMINI_GLM_PRO_SETUP.md) – optionale advisory KI-Prüfer
- [`reverse-engineering/reports/VMAX_VCORE_HYENA_SEPARATION_2026-08-21.md`](reverse-engineering/reports/VMAX_VCORE_HYENA_SEPARATION_2026-08-21.md) – Hardware-/Vendor-Trennung
- [`reverse-engineering/reports/BT638_HANDSHAKE_AUTH_EVIDENCE_2026-08-21.md`](reverse-engineering/reports/BT638_HANDSHAKE_AUTH_EVIDENCE_2026-08-21.md) – Auth-/Handshake-Evidenz

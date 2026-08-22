# VMAXDashboard – verbindliche Arbeitsregeln

Diese Regeln gelten für Menschen und alle Coding-Assistenten im gesamten Repository.

## Projekt- und Datenstruktur

- `main` enthält App-Code, Tests, Workflows, Dokumentation und überprüfte Forschungsberichte.
- `telemetry-data` enthält Messfahrten unter `fahrdaten/`, Deep-READ-Dumps unter `diagnostics/` sowie erzeugte Ergebnisse unter `decoder-ai/`.
- Rohdaten und erzeugte Decoder-Berichte gehören nicht zusätzlich auf `main`.
- Reverse-Engineering-Berichte, native Symbolinventare und Original-App-Semantik sind Forschungsevidenz. Nicht stillschweigend löschen, umdeuten oder zusammenfassen.

## Hardware und Evidenz strikt trennen

- Zielgerät ist der VMAX New VX2 Gear mit V-Core-/V-Torque-Gear-Hardware und dem beobachteten BLE-Gerät `BT638`.
- Das live beobachtete Protokoll läuft über GPST/DA1A und die `15xx`-Characteristics.
- Hyena/Hylink-, Brose-, Hobbywing- und andere Vendor-SDK-Funde bleiben getrennte Hinweise. Eine Klasse, ein String oder ein natives Symbol beweist nur SDK-Fähigkeit, nicht die Verfügbarkeit auf BT638.
- Evidenzreihenfolge: reproduzierbare BT638-Messdaten und deterministische Prüfungen; dokumentierte VMAX-/Original-App-Pfade; SDK-Fähigkeit; erst danach Modellvorschläge.
- Einen Handshake, Schlüssel, Token oder Authentifizierungsweg niemals erraten. Ohne gerätespezifischen Aufrufpfad lautet das Ergebnis „nicht nachgewiesen“.

## BLE- und Fahrzeugsicherheit

- Deep READ verwendet ausschließlich die bestehende GATT-Verbindung, liest nacheinander nur Characteristics mit `PROPERTY_READ` und speichert Antworten getrennt von Live-Telemetrie.
- READ-Antworten dürfen weder in Notification-Telemetrie noch in Decoder-Lernen als normale Livepakete einfließen.
- KI-Ausgaben dürfen niemals automatisch BLE schreiben, Decoder bestätigen, Tuning freigeben oder Firmware verändern.
- Der einzige aktuell in der UI angeschlossene Einstellungs-Write ist die bewusst vom Nutzer ausgelöste Zero-/Kick-Start-Umschaltung. Änderungen daran müssen fail-closed bleiben und die vorhandenen Verbindungs-, Geräte-, Protokoll- und Bestätigungsprüfungen samt Tests erhalten.
- Experimentellen Motor-/Tuning-Code nicht ohne ausdrücklichen Auftrag in die UI einbinden. Keine automatischen Parameter-, Firmware- oder Entsperränderungen.
- Keine gefährlichen Fahrtests vorschlagen. Messfahrten müssen im normalen, sicheren Fahrbetrieb möglich sein.

## Decoder- und KI-Regeln

- Geschwindigkeit, Kilometerstand, Akku-Prozent, Spannung und Strom besitzen starke Live-Evidenz. Andere Felder bleiben Kandidaten, bis unabhängige Evidenz vorliegt.
- Direkte Leistung und `Spannung × Strom` bleiben getrennte Quellen; kein Feld darf sich über einen aus demselben Rohwert erzeugten Export selbst bestätigen.
- Gemini, GLM, GPT, Claude, Codex und Copilot sind nur Prüfer bzw. Implementierungshelfer. Übereinstimmende Modelle sind kein Messbeweis.
- Neue Regeln benötigen deterministische Guards, passende Tests und nachvollziehbare Rohdaten. Providerfehler dürfen die letzte vollständige, zum selben Input passende Analyse nicht überschreiben.

## Datenschutz und Geheimnisse

- Keine API-Keys, GitHub-Tokens, Keystores, APKs oder `google-services.json` committen.
- Neue Uploads müssen Bluetooth-Adressen, Seriennummern, Firmware-/Identitätsantworten und andere Gerätekennungen gemäß dem bestehenden Privacy-Contract entfernen oder deterministisch redigieren.
- Keine sensiblen Roh-Payloads in Issues, PR-Texte oder KI-Prompts kopieren. Bei Befunden Pfad, Zeile und Befundklasse nennen, nicht den geheimen Wert.
- GitHub-Sync und externe KI-Aufrufe bleiben bewusst einzurichtende Funktionen. Keine neue automatische Datenübertragung ohne sichtbare Nutzerkontrolle.
- Löschen oder Überschreiben von `telemetry-data`, Branch-Löschung, History-Rewrite, Force-Push und Entfernung der letzten Rohdatenkopie benötigen eine frische, ausdrückliche menschliche Freigabe. Vorher Pfade, Hashgleichheit, Wiederherstellungsref und private Sicherung nachweisen.

## Änderungen und Nachweis

- Vor Änderungen den aktuellen `main`-Stand und offene PRs prüfen; in einem isolierten Branch/Worktree arbeiten.
- Relevante JVM-Tests: `./gradlew testDebugUnitTest --stacktrace`.
- Decoder-Tests: `PYTHONPATH=tools/decoder_ai python -m unittest discover -s tools/decoder_ai -p 'test_*.py'`.
- Repository-Hygiene: `python3 tools/check_repository_hygiene.py`.
- Release-APKs dürfen nur der aktive Workflow `VMAX Dashboard Fahrdaten Build` und das fest erwartete Signaturzertifikat ausliefern.
- `.github/workflows/decoder-ai.yml` und `.github/workflows/targeted-1505-scan.yml` werden bewusst auch auf `telemetry-data` benötigt. Änderungen sind vor dem Merge bytegleich auf den Datenbranch zu synchronisieren; niemals dafür `main` pauschal in `telemetry-data` mergen.
- Nur bei grünen Tests/CI mergen. Bekannte Prüflücken und nicht nachgewiesene Annahmen im PR offen nennen.

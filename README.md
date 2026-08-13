# Scooter Telemetry VX 5.1 - Tester Lab

Inoffizielles, lokales BLE-Telemetrie- und Diagnosewerkzeug fur kompatible E-Scooter.

Die App ist in erster Linie ein eigenes Diagnose- und Testwerkzeug. Der Fokus liegt auf lokaler Auswertung, reproduzierbaren Tests und vorsichtiger, nachvollziehbarer Analyse - nicht auf Eingriffen in Fahrparameter.

## Aktueller Fokus

- **Eigenes Werkzeug zuerst:** auf Stabilitat, Verlasslichkeit und Erweiterbarkeit fur den eigenen Einsatz optimiert.
- **Google/oeffentlich optional spater:** Struktur und Dokumentation werden sauber gehalten, ohne schon jetzt alles auf einen offentlichen Release auszurichten.
- **Keine Datenverluste beim Aufraumen:** bestehende Analyse-, Test- und Forschungsdateien bleiben erhalten und werden nur besser strukturiert.

## Hauptfunktionen

- universeller BLE-Geratescanner mit manueller Auswahl
- lokales Telemetrie- und Diagnose-Dashboard
- gefuhrter, standardisierter Decoder-Test
- Sitzungs- und Messfahrtsupport
- Vergleichsberichte und Export fur Testzwecke
- experimentelle Unterstutzung unbekannter Modelle

## Sicherheits- und Nutzungsrahmen

- **Nur-Lese-Analyse:** es werden keine Fahrparameter verandert
- **lokaler Fokus:** keine automatische Datenubertragung
- **kein GPS im Testerbericht**
- **experimentelle Modellunterstutzung** muss durch reale Testeraufnahmen bestatigt werden

## Versionshinweise

### Neu in 5.1

- universeller BLE-Geratescanner mit manueller Auswahl
- gefuhrter, standardisierter Decoder-Test
- Tester- und Modellangaben
- STVX-1 Vergleichsbericht uber Android Teilen
- keine automatische Datenubertragung
- kein GPS im Testerbericht
- weiterhin Nur-Lese-Analyse: keine Fahrparameter werden verandert

### Scooter-Finder 5.1

Beim Offnen des Tester-Labs startet automatisch ein universeller BLE-Scan. Wahrscheinliche Scooter werden hervorgehoben, nach Signalstarke sortiert und konnen mit einem Tipp verbunden werden.

### Smart Connect (5.2)

- speichert den zuletzt erfolgreich verbundenen Scooter lokal
- verbindet ihn beim nachsten App-Start automatisch
- versucht nach einem unerwarteten BLE-Abbruch automatisch erneut zu verbinden
- kann unter Setup deaktiviert oder uber "Vergessen" zuruckgesetzt werden
- eine manuelle Trennung startet absichtlich keine Wiederverbindung

## Projektstruktur

```text
app/                   Android-App mit BLE-, Telemetrie- und UI-Logik
docs/                  gebundelte Projektdokumentation
reverse-engineering/   technische Analyse und Forschung
store-assets/          Assets fur Darstellung und Distribution
tools/decoder_ai/      Hilfswerkzeuge rund um Decoder und Analyse
.github/workflows/     Automatisierung und Build-Ablaufe
```

## Dokumentation

Die Dokumentation wird schrittweise unter `docs/` gebundelt, damit das Root-Verzeichnis ruhiger und professioneller bleibt.

### Testing

- `docs/testing/BLE_TESTPLAN_V2.2.md`
- `docs/testing/TESTER_ANLEITUNG.md`

### Privacy

- `docs/privacy/DATENSCHUTZ_ENTWURF.md`

### Release

- `docs/release/HANDY_APK_ANLEITUNG.md`
- `docs/release/UPDATE_SIGNATUR_ANLEITUNG.md`

### Research

- `docs/research/ANALYSE_EDITION.md`
- `docs/research/GEFUNDENE_DECODER_6.1.md`
- `docs/research/GEFUNDENE_DECODER_6.2.md`

### Features

- `docs/features/LIVE_AI_TEST_EDITION.md`
- `docs/features/MESSFAHRT_MARKER_EDITION.md`
- `docs/features/MESSFAHRT_PRO_EDITION.md`

## Empfohlene interne Weiterentwicklung

1. `MainActivity.kt` in kleinere UI-Bausteine zerlegen
2. BLE-, Decoder-, Telemetrie- und Sync-Logik sauber paketieren
3. Dokumentation weiter aus dem Root in `docs/` verschieben
4. experimentelle und bestatigte Modellunterstutzung klar markieren
5. Google-/offentliche Tauglichkeit erst nach Stabilisierung bewerten

## Hinweis zur Ausrichtung

Dieses Projekt ist aktuell primar fur den eigenen praktischen Einsatz gedacht. Eine spatere offentliche oder store-nahe Aufbereitung bleibt moglich, steht aber bewusst hinter technischer Stabilitat, sauberer Struktur und nachvollziehbarer Diagnose zuruck.

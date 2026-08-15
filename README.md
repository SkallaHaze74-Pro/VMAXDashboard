# VMAXDashboard

Lokales BLE-Telemetrie- und Diagnosewerkzeug fuer kompatible E-Scooter. Das Projekt ist aktuell auf **eigene Nutzung, Tests und reale Messfahrten** ausgerichtet. Die App arbeitet bewusst **read-only**: Es werden keine Fahrparameter veraendert.

## Aktueller Fokus

Der aktuelle Schwerpunkt liegt auf:

- stabiler BLE-Verbindung
- reproduzierbaren Messfahrten
- lokaler Aufzeichnung und Auswertung
- strukturiertem Decoder-Lernen
- spaeter optional besserer oeffentlicher Praesentation

## Hauptfunktionen

- universeller BLE-Geraetescanner mit manueller Auswahl
- lokale Telemetrie- und Diagnoseanzeige
- automatische Daueraufnahme waehrend der Nutzung
- Marker fuer reale Testereignisse waehrend der Fahrt
- GATT-Explorer mit sicheren READ-Operationen
- GitHub-Sync fuer Fahrdaten und Decoder-Ablage
- Adaptive-Decoder- und Vergleichslogik

## Sicherheitsrahmen

- **Nur Lesen:** keine Aenderung von Fahrparametern
- **Keine automatische Datenuebertragung** ohne bewusste Einrichtung
- **Kein GPS im Testerbericht**
- **Unbekannte Modelle** sind experimentell und muessen durch reale Testeraufnahmen bestaetigt werden

## Versionen und sichtbare Entwicklung

### Neu in 5.1

- universeller BLE-Geraetescanner mit manueller Auswahl
- gefuehrter, standardisierter Decoder-Test
- Tester- und Modellangaben
- STVX-1 Vergleichsbericht ueber Android Teilen
- keine automatische Datenuebertragung
- kein GPS im Testerbericht
- weiterhin Nur-Lese-Analyse: keine Fahrparameter werden veraendert

### Scooter-Finder 5.1

Beim Oeffnen des Tester-Labs startet automatisch ein universeller BLE-Scan. Wahrscheinliche Scooter werden hervorgehoben, nach Signalstaerke sortiert und koennen mit einem Tipp verbunden werden.

### Smart Connect (5.2)

- speichert den zuletzt erfolgreich verbundenen Scooter lokal
- verbindet ihn beim naechsten App-Start automatisch
- versucht nach einem unerwarteten BLE-Abbruch automatisch erneut zu verbinden
- kann unter Setup deaktiviert oder ueber "Vergessen" zurueckgesetzt werden
- eine manuelle Trennung startet absichtlich keine Wiederverbindung

## Projektstruktur

```text
app/                  Android-App
reverse-engineering/  technische Analyse und Protokollarbeit
tools/decoder_ai/     Hilfstools fuer Decoder-/KI-Unterstuetzung
store-assets/         Assets fuer Darstellung und Verteilung
.github/workflows/    Automatisierung und Build-Helfer
docs/                 geordnete Projektdokumentation
```

## Dokumentation

Die bestehende Doku wird schrittweise in eine klarere Struktur ueberfuehrt, ohne bestehende Inhalte zu verlieren.

- `docs/testing/` fuer Testablaeufe und Testerhinweise
- `docs/privacy/` fuer Datenschutz und Grenzen
- `docs/release/` fuer APK-, Signatur- und Release-Hinweise
- `docs/research/` fuer Analyse, Reverse-Engineering-Ergebnisse und Decoder-Funde
- `docs/features/` fuer feature-spezifische Notizen und Editionsstaende

## Empfohlene interne Weiterentwicklung

Die naechsten sinnvollen Schritte fuer dieses Projekt sind:

- MainActivity entlasten und UI sauber aufteilen
- BLE-, Decoder-, Sync- und Telemetrie-Logik fachlich trennen
- Session- und Report-Logik weiter strukturieren
- Dokumentation ordnen, ohne bestehende Daten zu loeschen
- reale erste Messfahrt und anschliessenden Upload robust machen

## Zielbild

Kurzfristig soll die App fuer echte Fahrten zuverlaessig sein. Mittel- bis langfristig kann daraus eine oeffentlich besser praesentierbare App werden, ohne den aktuellen privaten Fokus zu verlieren.

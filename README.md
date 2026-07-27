# Scooter Telemetry VX 5.1 – Tester Lab

Inoffizielles, lokales BLE-Telemetrie- und Diagnosewerkzeug für kompatible E-Scooter.

## Neu in 5.1
- universeller BLE-Gerätescanner mit manueller Auswahl
- geführter, standardisierter Decoder-Test
- Tester- und Modellangaben
- STVX-1 Vergleichsbericht über Android Teilen
- keine automatische Datenübertragung
- kein GPS im Testerbericht
- weiterhin Nur-Lese-Analyse: keine Fahrparameter werden verändert

Die Unterstützung unbekannter Modelle ist experimentell und muss durch reale Testeraufnahmen bestätigt werden.


## Scooter-Finder 5.1
Beim Öffnen des Tester-Labs startet automatisch ein universeller BLE-Scan. Wahrscheinliche Scooter werden hervorgehoben, nach Signalstärke sortiert und können mit einem Tipp verbunden werden.

## Smart Connect (5.2)
- Speichert den zuletzt erfolgreich verbundenen Scooter lokal.
- Verbindet ihn beim nächsten App-Start automatisch.
- Versucht nach einem unerwarteten BLE-Abbruch automatisch erneut zu verbinden.
- Kann unter Setup deaktiviert oder über „Vergessen“ zurückgesetzt werden.
- Eine manuelle Trennung startet absichtlich keine Wiederverbindung.

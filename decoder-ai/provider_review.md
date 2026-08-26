# Gemini + GLM Decoder-Zweitprüfung mit optionaler GPT-Synthese

> Advisory only • STRICT READ-ONLY: Gemini und GLM sind unabhängige Prüfer. GPT ordnet ihre Entwürfe optional nur als Synthese und zählt nicht als dritter Evidenzbeleg.
> Eigene oder fremde KI-Antworten zählen niemals als unabhängige Bestätigung; maßgeblich bleiben Mess-Evidenz, deterministische Konsenslogik und Evidence Guard.

## Gemini 3.7 Flash

Status: `ok`

Modell: `gemini-3.5-flash-lite`

Fallbackmodell aktiv: `gemini-3.5-flash-lite`.

- Belastbare Evidenz
  * Das Decoder-Profil (`83eb267d6a348481`) basiert auf 21 ausgewerteten Messfahrten mit insgesamt 5 bestätigten und 1 Kandidaten-Regel (`powerW`).
  * Der libble-Vergleich zeigt, dass die Felder `speed_kmh` (1505), `current_A` (1509), `direct_power_W` (1509), `soc_percent` (1509) und `voltage_V` (1509) eine 100%ige Layout-Konsistenz mit dem SDK aufweisen (`APP_EXPORT_CONSISTENT_WITH_SDK_LAYOUT`).
  * Der GATT-Deep-Read-Abgleich verzeichnet 644 erfolgreiche Callback-Payloads über 28 Charakteristiken ohne fehlerhafte Payloads.

- Konflikte / mögliche Bugs
  * Mehrere Quellangaben (z.B. im Original-App-Vergleich) stufen `APP_EXPORT_CONSISTENT_WITH_SDK_LAYOUT` als "non-independent" ein; das bedeutet, sie vergleichen nur App-Extraktionen mit demselben RAW-Paket, was keinen echten physischen Sensornachweis darstellt.
  * Das Feld `powerW` (1509/9) verbleibt trotz einer Kreuzvalidierung von 95.02% Nähe und 0.992 Korrelation lediglich im Status `candidate`, da eine unabhängige externe Validierung fehlt.
  * Datenqualitätslücken: Einige ältere Messfahrten (z.B. `Messfahrt_2026-08-13_19-17-14`) weisen 0 akzeptierte Exportzeilen oder unvollständige Zähler auf.

- Hypothesen (nicht bestätigt)
  * `powerW` (1509/9) entspricht der echten elektrischen Leistung, allerdings ist die semantische Natur derzeit nur durch interne Kreuzrechnung (`Spannung × Strom`) gestützt.
  * Charakteristik `150C` wird weiterhin als reiner `BatteryCellUpdate`-Platzhalter gewertet, ohne bestätigte Byte-Offets.

- Nächste sichere READ-ONLY-Tests (max. 5)
  1. Lesen der statischen Charakteristik `1502` im Stillstand zur Verifizierung der Akku-/Controller-Struktur.
  2. Lesen der dynamischen Charakteristik `1505` im Stillstand für Basiswerte.
  3. Lesen der Charakteristik `1506` im Stillstand zur Odometer-Konsistenzprüfung.
  4. Lesen der Charakteristik `1509` im Stillstand zur Überprüfung der Live-Spannungs- und Strom-Defaults.
  5. Lesen der Charakteristik `150A` im Stillstand zur Verifizierung des Motor-Layouts.

- Automatische Änderungen: KEINE

Freigabe: keine automatische Änderung.

## GLM

Status: `error`

Modell: `glm-5.3`

Fehler: The read operation timed out

## OpenAI GPT-5.6 Luna • Synthese, kein Evidenzvotum

Status: `not_configured`

Nicht konfiguriert.

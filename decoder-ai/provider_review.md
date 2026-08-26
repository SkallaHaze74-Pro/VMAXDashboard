# Gemini + GLM Decoder-Zweitprüfung mit optionaler GPT-Synthese

> Advisory only • STRICT READ-ONLY: Gemini und GLM sind unabhängige Prüfer. GPT ordnet ihre Entwürfe optional nur als Synthese und zählt nicht als dritter Evidenzbeleg.
> Eigene oder fremde KI-Antworten zählen niemals als unabhängige Bestätigung; maßgeblich bleiben Mess-Evidenz, deterministische Konsenslogik und Evidence Guard.

## Gemini 3.7 Flash

Status: `ok`

Modell: `gemini-3.5-flash-lite`

Fallbackmodell aktiv: `gemini-3.5-flash-lite`.

- Belastbare Evidenz:
  - 22 Messfahrten und 672 erfolgreiche GATT-Callbacks belegen konstante Rohdatenstrukturen für die Kanäle 1505, 1506 und 1509 (Quelle: BT638 GATT DEEP READ & libble-Vergleich).
  - 5 Decoder-Regeln (`speedKmh`, `odometerKm`, `voltageV`, `currentA`, `batteryPercent`) besitzen eine Konsistenz von 99% basierend auf SDK-Layout- und App-Extraktionsprüfungen (Quelle: Decoder-Profil).
  - `powerW` (Kanal 1509, Offset 9) ist im Profil explizit als `candidate` markiert, da eine reine app-interne RAW-Konsistenz und Kreuzvalidierung kein unabhängiger semantischer Beweis sind (Quelle: Evidence Guard).

- Konflikte / mögliche Bugs:
  - Selbstreferenz bei Leistungswerten: Die Kreuzvalidierung von `powerW` gegen `abs(voltage_v * current_a)` vergleicht App-Export-Daten miteinander, was zu Zirkelschlüssen führen kann, solange keine externe physikalische Messung vorliegt (Quelle: Evidence Guard).
  - Diskrepanzen in den Export-Zusammenfassungen: Einige frühe Messfahrten (z. B. `Messfahrt_2026-08-13_19-17-14`) zeigen unvollständige oder fehlende Zählerwerte (`?` in der Datenqualitäts-Tabelle) (Quelle: libble-Vergleich).

- Hypothesen (nicht bestätigt):
  - 1509/9 u16be entspricht physischer elektrischer Leistung in Watt (offen bis zur externen Validierung).
  - Kanal 150C repräsentiert Zellspannungen oder ein BatteryCellUpdate (bisher nur Platzhalter- und Sentinel-Bytes beobachtet; Quelle: GATT Deep Read).

- Nächste sichere READ-ONLY-Tests (max. 5):
  1. Nur im Stillstand und ohne Ladekabel: Einen kurzen READ-/Notify-Mitschnitt der Kanäle 1505 und 1509 aufzeichnen.
  2. Abgleich der gemessenen Odometer-Werte aus Kanal 1506 mit einem unbeteiligten externen GPS-Log bei konstanter Fahrt (Soll-Ist-Vergleich ohne Schreibzugriff).
  3. Statische Konsistenzprüfung der Characteristics 1502 und 1503 im getrennten Verbindungszustand.
  4. Überprüfung der Rohdaten-Payloads auf Plausibilität (keine Sprünge im SOC oder der Spannung) bei aufeinanderfolgenden READ-Versuchen.
  5. Kontrolle der Export-Zusammenfassungen auf konsistente Zähler ohne Annahme von Nullwerten für fehlende Einträge.

- Automatische Änderungen: KEINE

Freigabe: keine automatische Änderung.

## GLM

Status: `ok`

Modell: `glm-4.5-flash`

Fallbackmodell aktiv: `glm-4.5-flash`.

Belastbare Evidenz
- batteryPercent, currentA, odometerKm, speedKmh und voltageV sind mit 99% Konfidenz bestätigt
- Diese Felder zeigen 100% Trefferrate im libble-Vergleich mit dem BT638-Livestand
- BT638 GATT DEEP READ bestätigt erfolgreiche Lesezugriffe auf die entsprechenden Characteristics
- Cross-Field-Check für powerW zeigt hohe Korrelation (0.992124) mit |Spannung × Strom|

Konflikte / mögliche Bugs
- powerW bleibt Kandidat mit 93% Konfidenz, da unabhängige semantische Validierung fehlt
- Im libble-Vergleich wird powerW nicht direkt geprüft, nur indirekt über Cross-Field-Check
- Mehrere wichtige Akkufelder (charging, chargingRemainSeconds, stateOfHealth) sind noch unzugeordnet
- 1502 (Battery/static candidate) hat statische Payloads, könnte aber wichtige Batterieparameter enthalten

Hypothesen (nicht bestätigt)
- powerW könnte tatsächlich die Leistung in Watt darstellen, basierend auf der hohen Korrelation mit Spannung × Strom
- 1502 könnte fehlende Akkuzustandsdaten wie Ladezeit oder Gesundheitsparameter enthalten
- 150C (BatteryCellUpdate candidate) könnte Zellspannungs- oder Temperaturdaten liefern, die noch nicht extrahiert wurden
- Die Unterschiede in 1505-Payload-Varianten könnten verschiedene Betriebsmodi widerspiegeln

Nächste sichere READ-ONLY-Tests
1. Detaillierte Analyse von 1502 (Battery/static candidate) zur Identifizierung noch fehlender Batterieparameter
2. Unabhängige Validierung von powerW durch Vergleich mit bekannten physikalischen Messwerten
3. Untersuchung von 150C auf mögliche Zellinformationen und deren Bedeutung
4. Analyse von 1507 zur Klärung der Statistikblock-Semantik
5. Überprüfung der Korrelation zwischen 1503-Varianten und bekannten Gerätezuständen

Automatische Änderungen: KEINE

Freigabe: keine automatische Änderung.

## OpenAI GPT-5.6 Luna • Synthese, kein Evidenzvotum

Status: `not_configured`

Nicht konfiguriert.

# Gemini + GLM Decoder-Zweitprüfung mit optionaler GPT-Synthese

> Advisory only • STRICT READ-ONLY: Gemini und GLM sind unabhängige Prüfer. GPT ordnet ihre Entwürfe optional nur als Synthese und zählt nicht als dritter Evidenzbeleg.
> Eigene oder fremde KI-Antworten zählen niemals als unabhängige Bestätigung; maßgeblich bleiben Mess-Evidenz, deterministische Konsenslogik und Evidence Guard.

## Gemini 3.7 Flash

Status: `ok`

Modell: `gemini-3.5-flash-lite`

Fallbackmodell aktiv: `gemini-3.5-flash-lite`.

- Belastbare Evidenz
  - Die RAW-Extraktionen der Kanäle 1505, 1506 und 1509 zeigen eine konsistente Layout-Übereinstimmung mit dem originalen SDK (`APP_EXPORT_CONSISTENT_WITH_SDK_LAYOUT`).
  - GATT-Deep-Reads (392 erfolgreiche Callbacks) bestätigen die Erreichbarkeit und Dynamik von 28 Characteristics im BT638-Profil.
  - Fünf Kernregeln (SOC, Strom, Kilometerstand, Geschwindigkeit, Spannung) stützen sich auf wiederholte SDK-Layout-Prüfungen über bis zu 11 Messfahrten.

- Konflikte / mögliche Bugs
  - Zirkuläre Selbstreferenz bei `powerW` (1509/9): Die hohe Korrelation (0.9918) und der MAE basieren auf der Berechnung Spannung × Strom aus demselben RAW-Paket, was keine unabhängige physikalische Evidenz darstellt.
  - Datenqualitätslücken: Für mehrere frühe Messfahrten (z. B. `Messfahrt_2026-08-13_19-17-14`) fehlen in der Zusammenfassung die READ/Hybrid-Zähler komplett.
  - Unklare Semantik bei Kandidaten wie 150C (bisher nur Sentinel-/Platzhalterbytes ohne Live-Daten).

- Hypothesen (nicht bestätigt)
  - `powerW` (1509/9) repräsentiert die berechnete elektrische Leistung, ist jedoch mangels externer Messreferenz (z.B. geeichtes Zangenamperemeter) nicht endgültig validiert.
  - Die Characteristics 1502 und 1508 enthalten herstellerspezifische Status- oder Steuerungsblöcke, deren exakte Bit-Offsets unbekannt sind.

- Nächste sichere READ-ONLY-Tests (max. 5)
  1. Statischer Read-Abgleich von Kanal 1509 im ausgeschalteten/Stillstands-Zustand (Null-Last-Prüfung für Strom).
  2. Zeitstempel-Analyse der RAW-Exportzeilen zur Erkennung potenzieller Carry-forward- oder Stale-Sample-Effekte.
  3. Vergleichende READ-Abfrage der Charakteristik 1506 (Odometer) im Stand zur Validierung der Integer-Stabilität.
  4. Strukturierte Längenprüfung der GATT-Payloads von Charakteristik 1503 über mehrere Scans hinweg.

- Automatische Änderungen: KEINE

Freigabe: keine automatische Änderung.

## GLM

Status: `ok`

Modell: `glm-4.5-flash`

Fallbackmodell aktiv: `glm-4.5-flash`.

- **Belastbare Evidenz**
  - batteryPercent, currentA, odometerKm, speedKmh und voltageV sind mit 99% Konfidenz durch original-sdk-layout+app-extraction-check validiert (5/6 Regeln bestätigt)
  - BT638 GATT Deep Read zeigt stabile Lesefunktionen für 28 Characteristics, mit 9 dynamischen Charakteristiken
  - Cross-Field-Validierung für powerW zeigt hohe Korrelation (0.991833) und niedrigen Fehler (4.50 W), bleibt aber unabhängige semantische Bestätigung schuldig

- **Konflikte / mögliche Bugs**
  - powerW-Regel bleibt trotz starker Cross-Field-Übereinstimmung im Kandidatenstatus (nur 93% Konfidenz) - Evidence Guard explizit: "same-raw export consistency is not independent semantic proof"
  - Mehrere SDK-Felder werden als "OBSERVED_NEEDS_MORE_PROOF" markiert (powerA_W, powerB_W, secondary_current_A, motor_current_A)
  - Inkonsistente Zählungen in "Datenqualität je Export": Akzeptierte Exportzeilen (z.B. 1064) vs. laut Zusammenfassung gezählte Werte (168 READ, 5 Hybrid) - mögliche Verwerfungslogik unklar
  - Characteristic 150C wird als "BatteryCellUpdate candidate" bezeichnet, enthält aber bisher nur Sentinel-Bytes ohne semantische Zuordnung

- **Hypothesen (nicht bestätigt)**
  - powerW (1509/9) könnte elektrische Leistung darstellen, da stark mit |Spannung × Strom| korreliert, benötigt aber unabhängige physikalische Validierung
  - Characteristic 1502 ("Battery/static candidate") könnte statische Akkuinformationen wie Seriennummer oder Herstellungsdaten enthalten
  - Characteristic 1503 zeigt dynamische Veränderungen zwischen Scans, könnte noch unentdeckte Motor-/Controllerstatistiken enthalten
  - Die "APP_EXPORT_LAYOUT_CONSISTENT_NON_INDEPENDENT"-Felder (z.B. batteryVoltageMv, batteryPercent) sind layout-konsistent, aber semantisch noch nicht unabhängig validiert

- **Nächste sichere READ-ONLY-Tests (max. 5)**
  1. Unabhängige physikalische Validierung von powerW durch Vergleich mit externem Leistungsmesser während Fahrt
  2. Detaillierte Analyse von Characteristic 1502 zur Identifizierung möglicher statischer Akkuinformationen
  3. Untersuchung von Characteristic 150C auf Zellspannungsdaten, besonders während Ladevorgang
  4. Gezielte Validierung der "OBSERVED_NEEDS_MORE_PROOF"-Felder (powerA_W, powerB_W, secondary_current_A, motor_current_A)
  5. Analyse von Characteristic 1503 zur Identifizierung möglicher Motor-/Controllerstatistiken

- **Automatische Änderungen: KEINE**

Freigabe: keine automatische Änderung.

## OpenAI GPT-5.6 Luna • Synthese, kein Evidenzvotum

Status: `not_configured`

Nicht konfiguriert.

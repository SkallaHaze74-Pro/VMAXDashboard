# Gemini + GLM Decoder-Zweitprüfung mit optionaler GPT-Synthese

> Advisory only • STRICT READ-ONLY: Gemini und GLM sind unabhängige Prüfer. GPT ordnet ihre Entwürfe optional nur als Synthese und zählt nicht als dritter Evidenzbeleg.
> Eigene oder fremde KI-Antworten zählen niemals als unabhängige Bestätigung; maßgeblich bleiben Mess-Evidenz, deterministische Konsenslogik und Evidence Guard.

## Gemini 3.7 Flash

Status: `cached_ok`

Modell: `gemini-3.5-flash-lite`

Fallbackmodell aktiv: `gemini-3.5-flash-lite`.

- Belastbare Evidenz: 
  - Die Kanäle 1505 (`speedKmh`), 1506 (`odometerKm`) und 1509 (`currentA`, `voltageV`, `batteryPercent`) zeigen in `libble-Vergleich` und Original-App-Abgleich eine 100% konsistente Layout-Übereinstimmung mit dem SDK (`APP_EXPORT_CONSISTENT_WITH_SDK_LAYOUT`).
  - `powerW` (1509/9) bleibt laut Decoder-Profil explizit ein `candidate` (Konfidenz 93%), gestützt durch 1837 plattforminterne Cross-Field-Vergleiche mit einer Korrelation von 0.9919.

- Konflikte / mögliche Bugs: 
  - Selbstreferenz / Zirkelschluss: Die Validierung von `powerW` basiert auf `abs(voltage_v * current_a)` aus demselben RAW-Export (`1509`), was keine unabhängige externe Sensorquelle darstellt (`independentExternalConfirmation: false`).
  - Unvollständige Zähler in der Datenqualitäts-Tabelle (viele Fragezeichen bei READ/Hybrid in den älteren Messfahrten laut Zusammenfassung).

- Hypothesen (nicht bestätigt): 
  - `powerW` (1509/9) entspricht der physikalisch tatsächlichen elektrischen Leistung am Motor, benötigt jedoch noch externe Hardware-Validierung.
  - Charakteristik `150C` repräsentiert ein BatteryCellUpdate, ist aber aktuell nur auf Sentinel-/Platzhalterbytes reduziert.

- Nächste sichere READ-ONLY-Tests (max. 5): 
  1. Statischer READ-Abruf von Characteristic 1502 und 1509 im Leerlauf zur Verifizierung der Basisregister.
  2. Prüfung der Payload-Änderungen von 1503 und 1508 bei verschiedenen Stufen ohne MotormLast.
  3. Verifikation der Odometer-Inkremente (1506) bei stationärem Radlauf ohne Schreibbefehle.
  4. Abgleich der Rohdaten-Exportzeilen gegen die Zusammenfassungs-Zähler für Messfahrt 2026-08-16_22-12-43.
  5. Lesen von 150A zur Untersuchung des motor_current_A Kandidatenstatus im Stillstand.

- Automatische Änderungen: KEINE

Freigabe: keine automatische Änderung.

## GLM

Status: `cached_ok`

Modell: `glm-4.5-flash`

Fallbackmodell aktiv: `glm-4.5-flash`.

- **Belastbare Evidenz**
  - Die 5 bestätigten Regeln (batteryPercent, currentA, odometerKm, speedKmh, voltageV) zeigen konsistent hohe Konfidenz (99%) und perfekte Korrelationen (1.0)
  - Der libble-Vergleich bestätigt 100% Übereinstimmung dieser Felder mit dem SDK-Layout
  - Die Cross-Field-Validierung für powerW (94.72% Übereinstimmung mit |Spannung × Strom|) ist statistisch signifikant
  - BT638 GATT Deep-Read zeigt stabile Ergebnisse für die bestätigten Felder

- **Konflikte / mögliche Bugs**
  - Widerspruch zwischen powerW als Kandidat (93%) im Decoder und 100%-Übereinstimmung im libble-Vergleich
  - Datenqualitätsanzeige zeigt für einige Messfahrten "0 akzeptierte Exportzeilen", obwohl später Daten verarbeitet wurden
  - Sentinel-Only Characteristics (150C, 150B) werden als Platzhalter behandelt, könnten aber Zustandsinformationen enthalten
  - Unterschiedliche Bewertung desselben Feldes in Original-App-Vergleich ("APP_EXPORT_LAYOUT_CONSISTENT_NON_INDEPENDENT") vs. Decoder-Bestätigung

- **Hypothesen (nicht bestätigt)**
  - powerW könnte bereits ausreichend validiert sein, aber Evidence Guard blockiert Bestätigung ohne unabhängige externe Validierung
  - Dynamische Characteristics könnten zusätzliche relevante Informationen enthalten, die noch nicht vollständig extrahiert wurden
  - Die variablen Bytes in dynamischen Charakteristiken könnten mit bestimmten Gerätezuständen korrelieren
  - Unterschiede in Datenqualität zwischen Messfahrten könnten auf inkonsistente Exportmethoden hindeuten

- **Nächste sichere READ-ONLY-Tests**
  - Unabhängige Validierung von powerW durch Vergleich mit Referenzmessung (externes Wattmeter)
  - Analyse von dynamischen Characteristics (1502, 1503, 1505) auf Muster und Korrelation mit bekannten Parametern
  - Untersuchung von Sentinel-Only Characteristics (150C, 150B) auf Zustandsänderungen bei bestimmten Gerätezuständen
  - Überprüfung der Datenqualitätsunterschiede zwischen verschiedenen Messfahrten

- **Automatische Änderungen: KEINE**

Freigabe: keine automatische Änderung.

## OpenAI GPT-5.6 Luna • Synthese, kein Evidenzvotum

Status: `not_configured`

Nicht konfiguriert.

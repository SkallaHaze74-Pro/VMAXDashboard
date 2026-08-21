# Gemini + GLM Decoder-Zweitprüfung

> Advisory only • STRICT READ-ONLY: Diese Modelle sind ausschließlich Prüfer. Sie aktivieren keine Decoder-Regel, ändern keinen Code und erzeugen keine BLE-Schreibbefehle.
> Eigene oder fremde KI-Antworten zählen niemals als unabhängige Bestätigung; maßgeblich bleiben Mess-Evidenz, deterministische Konsenslogik und Evidence Guard.

## Gemini 3.7 Flash

Status: `ok`

Modell: `gemini-3.7-flash`

### Belastbare Evidenz
- **Layout-Konsistenz (SDK vs. App-RAW-Extraktion):** 100% Übereinstimmung (1428–1444 Vergleiche, MAE = 0.0) bei `1505.speed_kmh` (1505@6 u16be, Skalierung 0.1), `1509.current_A` (1509@0 s16be, Skalierung 0.001), `1509.soc_percent` (1509@4 u8, Skalierung 1.0) und `1509.voltage_V` (1509@5 u16be, Skalierung 0.001).
- **Cross-Field-Plausibilität Leistung:** 1509@9 (`u16be`, Skalierung 1.

## GLM

Status: `ok`

Modell: `glm-4.5-flash`

Kostenloser GLM-Fallback aktiv.

- Belastbare Evidenz:
  - Die Felder batteryPercent, currentA, odometerKm, speedKmh und voltageV zeigen 99% Übereinstimmung zwischen SDK-Layout und App-Extraktion mit hohen Sample-Anzahlen (868-1414)
  - Die powerW-Regel (1509/9/u16be) weist 95.18% Übereinstimmung mit dem berechneten Wert |Spannung × Strom| auf, hat aber nur 93% Konfidenz
  - Alle bestätigten Regeln stammen aus "original-sdk-layout+app-extraction-check"

- Konflikte / mögliche Bugs:
  - PowerW-Regel hat "independentExternalConfirmation: false" in der evidenceGuard, obwohl sie als candidate eingestuft ist
  - In der Datenqualitätstabelle fehlen Werte für "READ im Export" und "Hybrid im Export" bei mehreren Fahrten
  - Die libble-Vergleichstabelle zeigt "OBSERVED_NEEDS_MORE_PROOF" für 1509.direct_power_W trotz 89.05% Trefferquote
  - Alle "APP_EXPORT_LAYOUT_CONSISTENT_NON_INDEPENDENT"-Felder werden als konsistent betrachtet, ohne unabhängige semantische Validierung

- Hypothesen (nicht bestätigt):
  - Die hohe Korrelation von powerW mit anderen Feldern könnte auf Selbstreferenz zurückzuführen sein
  - Die hohe Übereinstimmung (99-100%) bei einigen Feldern könnte Carry-forward-Artefakte aus früheren Fahrten enthalten
  - Die Skalierungsfaktoren (z.B. 0.001 für currentA und voltageV) wurden nicht unabhängig verifiziert
  - Die Big-Endian-Interpretation könnte bei einigen Feldern zu Fehlern führen

- Nächste sichere READ-ONLY-Tests (max. 5):
  1. Unabhängige physikalische Validierung von powerW durch Vergleich mit externem Leistungs Messgerät
  2. Gezielte Analyse der remainingDistanceKm-Zuordnung zu Feld 1505 @ 10 u16be
  3. Klärung der motorPower- und treadlePower-Rollen (A/B-Zuordnung)
  4. Suche nach den fehlenden Feldern: batteryCapacityMwh, chargingRemainSeconds, charging, stateOfHealthPercent und stateOfHealthMwh
  5. Überprüfung der Endianness-Handhabung bei Multi-Byte-Feldern durch unabhängige Hex-Analyse

- Automatische Änderungen: KEINE

Freigabe: keine automatische Änderung.

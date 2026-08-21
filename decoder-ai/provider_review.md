# Gemini + GLM Decoder-Zweitprüfung

> Advisory only • STRICT READ-ONLY: Diese Modelle sind ausschließlich Prüfer. Sie aktivieren keine Decoder-Regel, ändern keinen Code und erzeugen keine BLE-Schreibbefehle.
> Eigene oder fremde KI-Antworten zählen niemals als unabhängige Bestätigung; maßgeblich bleiben Mess-Evidenz, deterministische Konsenslogik und Evidence Guard.

## Gemini 3.7 Flash

Status: `error`

Modell: `gemini-3.7-flash`

Fehler: Provider vorübergehend nicht verfügbar (500) • gemini-3.7-flash is currently experiencing high demand, spikes in demand are usually temporary. Please try again later.

## GLM

Status: `ok`

Modell: `glm-4.5-flash`

Kostenloser GLM-Fallback aktiv.

- **Belastbare Evidenz**
  - Die Regeln batteryPercent, currentA, odometerKm, speedKmh und voltageV zeigen 99% Konfidenz und hohe Sample-Anzahlen (868-1414) mit perfekter Korrelation (1.0) und MAE 0.0 im Decoder-Profil.
  - Der libble-Vergleich bestätigt für diese Felder 100% Übereinstimmung mit MAE 0.0, was Layout-Konsistenz belegt.
  - Die Original-App-Vergleich zeigt für diese Felder "APP_EXPORT_LAYOUT_CONSISTENT_NON_INDEPENDENT", was die Übereinstimmung der Extraktionen bestätigt.

- **Konflikte / mögliche Bugs**
  - Die powerW-Regel (1509/9:u16be) hat nur 89% Konfidenz und eine signifikante MAE von 11.605336 im libble-Vergleich, was auf mögliche Inkonstanz oder falsche Semantik hindeutet.
  - Diskrepanz in Sample-Anzahlen zwischen Deterministischem Bericht (z.B. speedKmh: 1414 Samples) und libble-Vergleich (speed_kmh: 1428 Samples) trotz gleicher 13 ausgewerteter Fahrten.
  - Die Felder 1505.powerA_W und 1505.powerB_W haben 0 Vergleiche im libble-Vergleich, obwohl sie SDK-bekannt sind, was auf unvollständige Validierung hindeutet.
  - In der Datenqualitäts-Tabelle fehlen die Zähler für READ/Hybrid-Exportzeilen in den ersten 10 Messfahrten, was die Verwerfungslogik unvollständig macht.

- **Hypothesen (nicht bestätigt)**
  - Die powerW-Regel könnte tatsächlich eine andere physikalische Größe abbilden (z.B. nur eine von zwei Leistungskomponenten), was die niedrigere Übereinstimmung erklären würde.
  - Die Sample-Anzahl-Diskrepanzen könnten auf unterschiedliche Verwerfungskriterien in den Analysepipelines hindeuten.
  - Die Felder 1505.powerA_W und 1505.powerB_W könnten die korrekten Leistungswerte sein, während die aktuelle powerW-Regel ein Artefakt oder Teilwert darstellt.
  - Die fehlenden Zuordnungen für batteryCapacityMwh, chargingRemainSeconds, charging, stateOfHealthPercent und stateOfHealthMwh könnten in Kanälen liegen, die noch nicht systematisch analysiert wurden.

- **Nächste sichere READ-ONLY-Tests (max. 5)**
  1. Unabhängige Korrelationsanalyse zwischen powerW (1509/9) und den SDK-Feldern 1505.powerA_W und 1505.powerB_W mit denselben Rohdaten.
  2. Zeitversetzte Analyse der Sample-Anzahl-Diskrepanzen zwischen Deterministischem Bericht und libble-Vergleich pro Fahrt.
  3. Gezielte Suche nach Mustern in den Verwerfungslogs der READ/Hybrid-Exporte, insbesondere in den ersten 10 Messfahrten.
  4. Analyse der Kanäle 1508 (für assistanceLevel und lightOn) und 150A (für motor_current_A) mit erhöhter Aufmerksamkeit für mögliche Leistungs- oder Zustandsinformationen.
  5. Statistische Untersuchung der powerW-Werte über verschiedene Fahrtbedingungen hinweg, um systematische Abweichungen zu identifizieren.

- **Automatische Änderungen: KEINE**

Freigabe: keine automatische Änderung.

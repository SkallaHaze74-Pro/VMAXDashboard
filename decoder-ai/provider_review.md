# Gemini + GLM Decoder-Zweitprüfung

> Advisory only: Diese Modellantworten aktivieren keine Decoder-Regel und erzeugen keine BLE-Schreibbefehle.

## Gemini 3.7 Flash

Status: `error`

Modell: `gemini-3.7-flash`

Fehler: Gratis-/Ratenlimit erreicht (429) • Your prepayment credits are depleted. Please go to AI Studio at https://ai.studio/projects to manage your project and billing. Learn more at https://ai.google.dev/gemini-api/docs/billing#prepay.

## GLM

Status: `ok`

Modell: `glm-4.7-flash`

Kostenloser GLM-Fallback aktiv.

### Belastbare Übereinstimmungen
*   **Speed (1505/6)**, **Voltage (1509/5)**, **Current (1509/0)**, **Battery % (1509/4)** und **Odometer (1506/0)** sind extrem robust (99% Konfidenz, >1000 Samples, 100% App-Übereinstimmung).
*   **Layout-Konsistenz:** Die SDK-Offsets (z.B. Current bei 0, Voltage bei 5, SOC bei 4) sind durch die App-Extraktion bestätigt.

### Konflikte/Unsicherheiten
*   **Power (1509/9):** Grobe Diskrepanz zwischen dem Konsensbericht (89% Konfidenz) und dem libble-Vergleich (64.74% Match, MAE 34.6). Die physikalische Validierung steht aus; der aktuelle Wert ist sehr wahrscheinlich inkorrekt.
*   **Stichprobenumfang:** `batteryPercent` basiert nur auf **4 Fahrten** (868 Samples), was für statistische Sicherheit gering ist. `odometerKm` auf **8 Fahrten**.
*   **1505 Power-Offset:** Das Decoder-Profil dekodiert `speed` bei 6, ignoriert aber die Power-Felder bei 0 und 2, die in der SDK-Liste existieren und vom App-Listener erwartet werden.

### Konkrete nächste Tests
1.  **Validierung `1505/0` und `1505/2` (Power A/B):** Testen gegen App Live, um Tret- und Motorleistung zu identifizieren.
2.  **Neubewertung `1509/9` (Power):** Physikalische Validierung (z.B. Spannung * Strom vs. Rohwert) oder Verwerfen, da die App-Übereinstimmung nur 64.74% beträgt.
3.  **Erhöhung der Stichprobe für `batteryPercent`:** Mindestens 5 weitere Fahrten für statistische Signifikanz.
4.  **Test `1505/8` (RPM):** Validierung des App-Mappings (Command 259) gegen die roten Daten.
5.  **Überprüfung `1509/1` (Secondary Current):** Korrelation mit `150A` (Motor Current) oder Verifizierung der Null-Werte.

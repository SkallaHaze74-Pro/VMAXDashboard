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

**Belastbare Fakten**
*   **SDK-Layouts bestätigt:** Die Decodierung für Kanal 1505 (Speed), 1506 (Odo) und 1509 (Volt, SOC, Current) basiert auf dem Original-SDK und ist 100% mit der App-Export-Datenbank konsistent.
*   **Messwerte korrekt:** Speed, Odometer, Voltage, Current und SOC zeigen 100% Match und 0.0 MAE gegen die Live-App.
*   **Skalierungen:** Odo (0.1 km), Speed (0.1 km/h) und Voltage (0.001 V) sind korrekt angegeben.

**Konflikte / Unsicherheiten**
*   **Diskrepanz bei Direkt-Leistung (1509/9):** Der Report gibt 99% Bestätigung, das libble-Tool zeigt jedoch nur 64,74% Übereinstimmung (MAE 34,6). Dies deutet darauf hin, dass das Feld bei Offset 9 **keine direkte physikalische Leistung (W) darstellt** oder die App die Leistung anders berechnet (z.B. Spannung x Strom), während das SDK-Layout den Rohwert direkt liest.
*   **Motor/Tretler-Leistung (1505):** Offset 0 und 2 sind identifiziert, aber keine semantische Zuordnung (Motor vs. Tretler) getroffen.
*   **150D Kanal:** Enthält 397 Pakete (100% Nutzlast), wird aber nicht für Geschwindigkeit genutzt. Ursache unklar (Sensor-Müll oder nicht verwendeter Parameter).
*   **Stichprobengröße:** 13 Fahrten sind für eine statistisch "robuste" Bestätigung gering. Die hohe Konfidenz (99%) beruht stark auf Layout-Konsistenz (App-Export), nicht auf physikalischer Validierung.

**Max 5 konkrete nächste Tests**
1.  **1509 Direkt-Leistung Validierung:** Fahrt mit bekanntem Lastprofil -> Prüfe ob Rohwert bei Offset 9 exakt Spannung x Strom ergibt.
2.  **Motor/Tretler Zuordnung:** Fahrt mit aktivem Motor und Pedaleingang -> Teste ob Offset 0 Motor-Leistung und Offset 2 Tretler-Leistung ist.
3.  **Kilometerstand-Delta:** Fahrt mit bekannter Distanz -> Prüfe Inkrement-Genauigkeit des Kilometerstands über die Zeit.
4.  **150D Analyse:** Durchsuche die 150D-Pakete auf Muster (z.B. Temperatur, Phasenstrom, Ladezustand) um Funktionsweise zu klären.
5.  **Sekundär-Strom (1509):** Fahrt -> Prüfe ob Offset 10 (secondary_current) tatsächlich konstant 0.0 bleibt oder einen versteckten Strom misst (z.B. für Licht/Display).

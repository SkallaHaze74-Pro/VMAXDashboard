# Gemini + GLM Decoder-Zweitprüfung mit optionaler GPT-Synthese

> Advisory only • STRICT READ-ONLY: Gemini und GLM sind unabhängige Prüfer. GPT ordnet ihre Entwürfe optional nur als Synthese und zählt nicht als dritter Evidenzbeleg.
> Eigene oder fremde KI-Antworten zählen niemals als unabhängige Bestätigung; maßgeblich bleiben Mess-Evidenz, deterministische Konsenslogik und Evidence Guard.

## Gemini 3.7 Flash

Status: `ok`

Modell: `gemini-3.5-flash-lite`

Fallbackmodell aktiv: `gemini-3.5-flash-lite`.

- Belastbare Evidenz:
  - Fünf Decoder-Regeln (`batteryPercent`, `currentA`, `odometerKm`, `speedKmh`, `voltageV`) basieren auf konsistentem `original-sdk-layout+app-extraction-check` über 5 bis 10 Messfahrten (bis zu 1320 Samples, Konfidenz 99%).
  - Das Signal `powerW` (1509/9, `u16be`) ist explizit als `candidate` markiert (Konfidenz 93%) mit dem Evidence-Guard-Vermerk, dass App-Export-Konsistenz keine unabhängige semantische Beweisführung darstellt.
  - Der GATT-Deep-Read-Abgleich verzeichnet 364 erfolgreicheCallbacks aus 17 Scans, bestätigt aber keine Byte-Semantiken automatisch.

- Konflikte / mögliche Bugs:
  - Selbstreferenz / Carry-forward-Effekt: Mehrere Vergleiche (`APP_EXPORT_CONSISTENT_WITH_SDK_LAYOUT`) vergleichen nur App-Extraktionen gegen denselben RAW-Datenstrom; dies ist kein externer physikalischer Sensornachweis.
  - Das Leistungsfeld `powerW` zeigt zwar eine hohe Korrelation (0.9916) und geringe MAE (4.13 W) zur Multiplikation aus Spannung und Strom, bleibt jedoch ohne unabhängige Validierung ein statistischer Kandidat.

- Hypothesen (nicht bestätigt):
  - 1509/9 (`u16be`) repräsentiert die elektrische Leistung in Watt, benötigt jedoch externe physikalische Verifizierung (z. B. Leistungsmesser am Scooter).
  - Charakteristik 150C enthält derzeit nur Sentinel-/Platzhalterbytes und ist noch nicht als `BatteryCellUpdate` verifiziert.

- Nächste sichere READ-ONLY-Tests (max. 5):
  1. Statischer READ-Abgleich der Charakteristik 1502 im unbewegten Zustand zur Validierung des statischen Akku-/Controllerblocks.
  2. Prüfung der Read-Only-Datenqualität in den Zusammenfassungs-Texten auf Vollständigkeit der Zähler ohne Annahme von Nullwerten.
  3. Vergleichende Protokollierung von 1505 (`speedKmh`) im Stand, um Offset-Drift auszuschließen.
  4. Konsistenzprüfung der Odometer-Werte (1506) zwischen aufeinanderfolgenden unbewegten Scans.
  5. Validierung der Rohdaten-Exportzeilen ohne automatische Zuweisung von unbekannten Kanälen.

- Automatische Änderungen: KEINE

Freigabe: keine automatische Änderung.

## GLM

Status: `ok`

Modell: `glm-4.5-flash`

Fallbackmodell aktiv: `glm-4.5-flash`.

Belastbare Evidenz
- Alle 5 bestätigten Regeln zeigen 100% Trefferquote mit 0.0 MAE im libble-Vergleich
- Cross-Field-Check für powerW zeigt hohe Korrelation (0.991626) mit geringer Abweichung (4.13W)
- BT638 GATT DEEP READ zeigt stabile Ergebnisse für 28 Characteristics mit konsistenten Payloads

Konflikte / mögliche Bugs
- powerW bleibt Kandidat ohne unabhängige externe Validierung
- Felder 1505.powerA_W und 1505.powerB_W im libble-Vergleich als "OBSERVED_NEEDS_MORE_PROOF" markiert
- 11 Characteristics ändern sich zwischen Scans, was auf mögliche Instabilität hindeutet
- 5 Felder in Original-App-Vergleich noch als "MAPPED_VERIFY_WITH_MORE_BT638_DATA" ohne Zuordnung

Hypothesen (nicht bestätigt)
- powerW könnte alternative Leistungsgröße sein, nicht direkt |Spannung × Strom|
- Dynamische Characteristics könnten vom Fahrzustand (Geschwindigkeit, Ladung) abhängen
- Unterschiede in Characteristics könnten auf Firmware-Hardware-Varianten zurückgehen
- Unzugeordnete Felder könnten neue Firmware-Features repräsentieren

Nächste sichere READ-ONLY-Tests (max. 5)
- Analyse der 11 dynamischen Characteristics zur Identifikation von Mustern
- Unabhängige physikalische Validierung der powerW-Regel
- Untersuchung der "OBSERVED_NEEDS_MORE_PROOF"-Felder aus libble-Vergleich
- Gezieltes Mapping der 5 noch unzugeordneten App-Listener-Felder
- Cross-Check Characteristics über verschiedene Firmware-Versionen

Automatische Änderungen: KEINE

Freigabe: keine automatische Änderung.

## OpenAI GPT-5.6 Luna • Synthese, kein Evidenzvotum

Status: `not_configured`

Nicht konfiguriert.

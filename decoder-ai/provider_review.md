# Gemini + GLM Decoder-Zweitprüfung mit optionaler GPT-Synthese

> Advisory only • STRICT READ-ONLY: Gemini und GLM sind unabhängige Prüfer. GPT ordnet ihre Entwürfe optional nur als Synthese und zählt nicht als dritter Evidenzbeleg.
> Eigene oder fremde KI-Antworten zählen niemals als unabhängige Bestätigung; maßgeblich bleiben Mess-Evidenz, deterministische Konsenslogik und Evidence Guard.

## Gemini 3.7 Flash

Status: `ok`

Modell: `gemini-3.5-flash-lite`

Fallbackmodell aktiv: `gemini-3.5-flash-lite`.

- Belastbare Evidenz:
  * Die Kanäle 1505 (`speedKmh`), 1506 (`odometerKm`) und 1509 (`batteryPercent`, `currentA`, `voltageV`) basieren auf wiederholten app-internen SDK-Layout-Abgleichen über 12 bis 15 Fahrten mit hoher Korrelation (Konsensbericht).
  * Der Power-Kandidat (`powerW` auf 1509/9) zeigt im Kreuzvergleich eine Konsistenz von 95.02% (MAE 4.22W) gegenüber der Formel `|Spannung × Strom|` (Decoder-Profil EvidenzGuard).
  * 28 GATT-Characteristics wurden in 31 Scans erfolgreich abgefragt (588/588 Callback-Erfolge laut GATT Deep READ), wobei dynamische Änderungen bei 9 Characteristics nachgewiesen sind.

- Konflikte / mögliche Bugs:
  * Der Power-Kandidat (`powerW`) leitet sich aus demselben RAW-Paket ab wie Spannung und Strom; dies ist laut EvidenzGuard keine unabhängige physikalische Validierung ("same-raw export consistency is not independent semantic proof").
  * Mehrere originale SDK-Felder (z. B. `motorPower`, `treadlePower`) haben noch offene A/B-Rollen und sind im BT638-Livebetrieb nicht eindeutig gemappt (Original-App-Vergleich).
  * Unbekannte Zähler in der Datenqualitäts-Tabelle (z. B. Leerwerte `?` bei älteren Messfahrten) dürfen nicht durch Nullen aus akzeptierten RAW-Zeilen ersetzt werden (Schutzregel libble-Vergleich).

- Hypothesen (nicht bestätigt):
  * Kanal 1509 Offset 9 (`powerW`) stellt die tatsächliche elektrische Leistung dar, bleibt jedoch ohne externen Messgerät-Abgleich ein reiner SDK-Layout-Kandidat.
  * Characteristic 150C repräsentiert ein BatteryCellUpdate, enthält aktuell jedoch ausschließlich Sentinel-/Platzhalterbytes (GATT Deep READ).
  * Die Characteristics 1503, 1505, 1506, 1507 und 1508 könnten nach weiteren Scans zusätzlichen Steuerungs- oder Statistikdaten zugeordnet werden.

- Nächste sichere READ-ONLY-Tests (max. 5):
  1. Kontinuierlicher passiver READ-/Notify-Mitschnitt von Kanal 1505 und 1509 im Stillstand zur Stabilitätsprufung der Basiswerte.
  2. Verifizierung des Odometer-Kanals (1506) durch stationäres Auslesen vor und nach einer kontrollierten Schiebeprüfung (ohne Schreibzugriff).
  3. Protokollierung der dynamischen Characteristics (1503 bis 1508) bei unmotorisiertem Rollen im ausgeschalteten/Stand-Zustand.
  4. Abgleich der Rohdaten-Exportzeilen (Zusammenfassung.txt) auf Vollständigkeit der READ- und Hybrid-Zähler ohne künstliche Imputation.
  5. Stichprobenartiger Read-Request auf Characteristic 1502 (Battery/static candidate) zur Prüfung auf Konstanz im unbelasteten Zustand.

- Automatische Änderungen: KEINE

Freigabe: keine automatische Änderung.

## GLM

Status: `ok`

Modell: `glm-4.5-flash`

Fallbackmodell aktiv: `glm-4.5-flash`.

- Belastbare Evidenz
  - Die Felder batteryPercent, currentA, odometerKm, speedKmh und voltageV weisen eine hohe Konfidenz (99%) auf und sind durch übereinstimmende SDK-Layout- und App-Extraktions-Checks validiert.
  - Der libble-Vergleich zeigt 100% Übereinstimmung für speed_kmh, current_A, direct_power_W, soc_percent und voltage_V mit einer MAE von 0.0.
  - Die BT638 GATT-Deep-Read-Daten zeigen konsistente Payload-Längen für Characteristics wie 1505 (18 Bytes), 1506 (16 Bytes) und 1509 (variable Länge).

- Konflikte / mögliche Bugs
  - Es existiert ein Widerspruch zwischen dem Decoder-Profil (powerW als Kandidat) und dem libble-Vergleich (direct_power_W als bestätigt), ohne dass die Beziehung zwischen diesen Feldern geklärt ist.
  - Die Felder batteryCapacityMwh, chargingRemainSeconds, charging, stateOfHealthPercent und stateOfHealthMwh bleiben ungeklärt, obwohl sie in der Original-App vorhanden sind.
  - In der libble-Vergleichstabelle zeigen viele Messfahrten NULL-Werte für "READ im Export" und "Hybrid im Export", was auf mögliche Datenausfälle oder unvollständige Aufzeichnungen hindeutet.
  - Characteristic 150C wird nur als Platzhalter bewertet, obwohl es möglicherweise relevante Informationen enthält.

- Hypothesen (nicht bestätigt)
  - Das Feld 1509/9 (powerW) könnte mit "direct_power_W" aus dem libble-Vergleich identisch sein, benötigt aber eine unabhängige physikalische Validierung.
  - Die variablen Bytes in Characteristics wie 1505, 1506 und 1509 könnten zusätzliche, noch nicht identifizierte Informationen enthalten.
  - Die hohen Korrelationswerte (95.02%) und niedrigen MAE-Werte (4.22 W) bei der Cross-Field-Prüfung von powerW deuten auf mathematische Konsistenz hin, reichen aber nicht für eine semantische Bestätigung aus.

- Nächste sichere READ-ONLY-Tests (max. 5)
  1. Unabhängige Validierung der Beziehung zwischen powerW (1509/9) und direct_power_W durch direkten Vergleich der Rohdaten.
  2. Detaillierte Analyse der Payloads von Characteristic 150C, um festzustellen, ob es sich tatsächlich nur um Platzhalter handelt oder zusätzliche Informationen enthält.
  3. Untersuchung der Muster in den variablen Bytes von Characteristics 1505, 1506 und 1509, um mögliche verborgene Felder zu identifizieren.
  4. Vergleich der fehlenden App-Felder (batteryCapacityMwh, chargingRemainSeconds etc.) mit allen verfügbaren Characteristics, insbesondere 1502 und 150C.
  5. Überprüfung der Endianness und Skalierungsfaktoren bei allen multi-Byte-Feldern durch unabhängigen Vergleich mit den Original-App-Werten.

- Automatische Änderungen: KEINE

Freigabe: keine automatische Änderung.

## OpenAI GPT-5.6 Luna • Synthese, kein Evidenzvotum

Status: `not_configured`

Nicht konfiguriert.

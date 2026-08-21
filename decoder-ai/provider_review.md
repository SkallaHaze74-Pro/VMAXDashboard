# Gemini + GLM Decoder-Zweitprüfung mit optionaler GPT-Synthese

> Advisory only • STRICT READ-ONLY: Gemini und GLM sind unabhängige Prüfer. GPT ordnet ihre Entwürfe optional nur als Synthese und zählt nicht als dritter Evidenzbeleg.
> Eigene oder fremde KI-Antworten zählen niemals als unabhängige Bestätigung; maßgeblich bleiben Mess-Evidenz, deterministische Konsenslogik und Evidence Guard.

## Gemini 3.7 Flash

Status: `ok`

Modell: `gemini-3.5-flash-lite`

Fallbackmodell aktiv: `gemini-3.5-flash-lite`.

- Belastbare Evidenz:
  - Fünf Decoder-Regeln (batteryPercent, currentA, odometerKm, speedKmh, voltageV) sind mit hoher Konfidenz (99%) über mehrere Fahrten und Samples bestätigt (`original-sdk-layout+app-extraction-check`).
  - Das Feld `powerW` (1509/9) verbleibt explizit als Kandidat (93% Konfidenz), da die bisherige Validierung auf derselben RAW-Extraktion beruht (`sdk-layout+cross-field-check-needs-external-proof`).

- Konflikte / mögliche Bugs:
  - Selbstreferenz und Carry-forward-Effekte: Mehrere Vergleiche basieren auf der Extraktion derselben RAW-Pakete (`APP_EXPORT_LAYOUT_CONSISTENT_NON_INDEPENDENT`), was keinen unabhängigen Live-Sensornachweis darstellt.
  - Datenqualitätsschwankungen: Mehrere Messfahrten weisen 0 akzeptierte Exportzeilen oder unvollständige READ-/Hybrid-Zähler aus der `Zusammenfassung.txt` auf.

- Hypothesen (nicht bestätigt):
  - `powerW` (1509/9) entspricht der echten elektrischen Leistung (Validierung nur über interne Kreuzkorrelation mit Spannung und Strom, MAE 3.94 W).
  - Unbekannte Kanäle (z. B. 150A, 150C) lassen sich ohne externe physische Referenz eindeutig semantisch zuordnen.

- Nächste sichere READ-ONLY-Tests (max. 5):
  1. Letzten bekannten Zustand vor einem Ladeabbruch bzw. den ersten Zustand nach einem Abziehen/Reconnect im Stillstand auslesen.
  2. Statische GATT-Characteristics (z. B. 1502) auf unveränderte Byte-Muster bei konstantem Gerätezustand prüfen.
  3. READ-Abgleiche der Characteristics 1505 bis 1509 im Stillstand wiederholen, um dynamische von statischen Bytes zu trennen.
  4. Konsistenzprüfung der Rohdaten-Exportzeilen gegen die Zusammenfassungsdateien (`Zusammenfassung.txt`) ohne automatische Nullableitung durchführen.
  5. Unabhängige Überprüfung der Kanal-Offsets gegen das native SDK (`libble-sdk-native-lib.so`) ohne Schreiboperationen.

- Automatische Änderungen: KEINE

Freigabe: keine automatische Änderung.

## GLM

Status: `ok`

Modell: `glm-4.7-flash`

Fallbackmodell aktiv: `glm-4.7-flash`.

- **Belastbare Evidenz**
  - Die RAW-Extraktions-Logs (BT638 Deep Read) zeigen konsistente Werte für `batteryPercent` (Offset 4: `30` = 48%), `currentA` (Offset 0: `00-00` = 0A) und `voltageV` (Offset 5-6: `BB 1C` -> 47.9V bei 0.001-Scale), was die Layout-Annahmen bestätigt.
  - Der SDK-Vergleich zeigt eine 100%ige Übereinstimmung für `direct_power_W`, was hardwareseitig die Konsistenz des Layouts 1509 belegt, auch wenn die semantische Bestätigung des Feldes noch aussteht.
  - Die Geschwindigkeit (1505/6) und der Kilometerstand (1506/0) zeigen im Deep-Read Konsistenz, sind aber mit der extrem niedrigen Odometer-Berechnung inkonsistent.

- **Konflikte / mögliche Bugs**
  - **Massiver Datenverlust / Parsing-Fehler:** Der SDK-Vergleich zeigt für 11 von 14 Fahrten "Akzeptierte Exportzeilen: 0". Die Decoder-Logik scheitert an der Verarbeitung der Rohdaten für die Mehrheit der Fahrten, was die Güte der "bestätigten" Regeln in Frage stellt.
  - **Odometer-Skalierungs-Fehler:** Das Sample `1506` (`00-00-1B-F5`) wird als `u32be@0` mit Scale `0.1` interpretiert. Das ergibt ca. 472,5 km. Dies ist für ein e-Bike mit nur 14 Fahrten physikalisch unplausibel (wahrscheinlich falsche Skala, sollte 1.0 sein).
  - **Zirkuläre Power-Validierung:** Die Power-Cross-Check-Logik nutzt `abs(voltage_v * current_a)` aus denselben Feldern, um `powerW` zu validieren. Dies ist keine unabhängige Evidenz, sondern eine mathematische Abhängigkeit innerhalb desselben Pakets.

- **Hypothesen (nicht bestätigt)**
  - Die `odometerKm`-Skala wurde fälschlicherweise auf 0.1 statt 1.0 gesetzt, was zu einem Faktor-10-Unterschied führt.
  - Die "0 akzeptierten Zeilen" deuten auf ein generelles Formatierungs- oder Regex-Problem im Decoder-Setup hin, das nicht nur einzelne Felder betrifft.

- **Nächste sichere READ-ONLY-Tests**
  - Manuelle Berechnung des Odometers aus dem Sample `1506` (Bytes `00-00-1B-F5` -> `0x1BF5` -> 4725) und Vergleich mit der Skalierung 0.1 vs. 1.0.
  - Analyse der rohen Datenstruktur der 11 Fahrten mit 0 akzeptierten Zeilen, um das Parsing-Problem zu identifizieren.
  - Live-Read von 1505/8 (RPM) und 1505/10 (Distanz) während einer Fahrt, um die fehlenden Dekodierungen zu validieren.

- **Automatische Änderungen: KEINE**

Freigabe: keine automatische Änderung.

## OpenAI GPT-5.6 Luna • Synthese, kein Evidenzvotum

Status: `not_configured`

Nicht konfiguriert.

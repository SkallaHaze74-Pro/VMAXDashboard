# Gemini + GLM Decoder-Zweitprüfung mit optionaler GPT-Synthese

> Advisory only • STRICT READ-ONLY: Gemini und GLM sind unabhängige Prüfer. GPT ordnet ihre Entwürfe optional nur als Synthese und zählt nicht als dritter Evidenzbeleg.
> Eigene oder fremde KI-Antworten zählen niemals als unabhängige Bestätigung; maßgeblich bleiben Mess-Evidenz, deterministische Konsenslogik und Evidence Guard.

## Gemini 3.7 Flash

Status: `ok`

Modell: `gemini-3.6-flash`

Fallbackmodell aktiv: `gemini-3.6-flash`.

### Belastbare Evidenz

- **Layout-Konsistenz der Grundsignale**: Für `speedKmh` (1505@6), `currentA` (1509@0), `batteryPercent` (1509@4), `voltageV` (1509@5) und `odometerKm` (1506@0) liegt eine 100%ige Übereinstimmung zwischen der App-Extraktion und dem SDK-Layout vor.
- **BT638 GATT-Read-Erreichbarkeit**: Alle 28 angefragten Characteristics im Service `1500` liefern valide GATT-READ-Callbacks (504/504 Versuche erfolgreich).
- **Dynamische Datenkanäle**: Die Channels 1503, 1505, 1506, 1507, 1508, 1509, 150A, 150D und 1802 verändern ihre Byte-Inhalte zwischen Scans und enthalten Echtdaten.
- **Konsistente Leistungskorrelation**: Das Feld 1509@9 korreliert zu 0.992055 mit $|V \cdot I|$ (MAE 4.41 W bei 1932 Samples), bleibt aber regelkonform als `candidate` isoliert.

---

### Konflikte / mögliche Bugs

- **Zirkelschluss / Selbstreferenz**: Der Abgleich `APP_EXPORT_CONSISTENT_WITH_SDK_LAYOUT` prüft lediglich zwei Extraktionspfade derselben RAW-Quelle. Dies beweist Parser-Symmetrie, jedoch keine externe physikalische Sensorik.
- **Datenverlust in Frühsamples**: Die ersten 6 Messfahrten weisen 0 akzeptierte Exportzeilen auf, da die Metadaten in `Zusammenfassung.txt` fehlen oder unvollständig sind.
- **Diskrepanz bei READ-Zählern**: Die Zusammenfassungen melden teils READ-Zugriffe (z. B. 168 in Fahrt 7), während die RAW-Exportauswertung 0 READ-Zeilen ausweist.
- **Offene Signalrollen**: Für `motorPower` vs. `treadlePower` (1505.powerA_W / powerB_W) existieren 0 Vergleiche; die Zuordnung ist ungesichert.

---

### Hypothesen (nicht bestätigt)

- **1509@9 u16be**: Repräsentiert die reelle elektrische Wirkleistung in Watt (hängt von externer Messgeräte-Ground-Truth ab).
- **1508@0 u8 / 1508@3 u8**: Steuern bzw. melden den Lichtstatus (`lightOn`) und die Fahrstufe (`assistanceLevel`).
- **1502 / 150C**: Enthalten statische BMS-Parameter bzw. Einzelzellspannungen (bisher nur als Sentinel-Bytes `FF-FF...` beobachtet).

---

### Nächste sichere READ-ONLY-Tests (max. 5)

1. **Licht-Schalt-Test (Stillstand)**: Aufzeichnung von Kanal 1508 bei manuellem Ein-/Ausschalten des Lichts im Stillstand.
2. **Fahrstufen-Wechsel (Stillstand)**: Ändern der Support-Stufe am Display ohne Motorlauf zur Absicherung von 1508@3.
3. **Vor/Nach-Lade-Read**: Auslesen von 1509 (SOC/Spannung) unmittelbar vor dem Laden und direkt nach dem ersten Reconnect nach dem Laden (im Stillstand, ohne Ladekabel).
4. **Odometer-Vergleich**: Abgleich von 1506@0 mit dem physikalischen Display-Stand nach einer manuellen Schiebestrecke.

---

### Automatische Änderungen: KEINE

Freigabe: keine automatische Änderung.

## GLM

Status: `error`

Modell: `glm-5.3`

Fehler: The read operation timed out

## OpenAI GPT-5.6 Luna • Synthese, kein Evidenzvotum

Status: `not_configured`

Nicht konfiguriert.

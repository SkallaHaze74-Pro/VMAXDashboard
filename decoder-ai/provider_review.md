# Gemini + GLM Decoder-Zweitprüfung mit optionaler GPT-Synthese

> Advisory only • STRICT READ-ONLY: Gemini und GLM sind unabhängige Prüfer. GPT ordnet ihre Entwürfe optional nur als Synthese und zählt nicht als dritter Evidenzbeleg.
> Eigene oder fremde KI-Antworten zählen niemals als unabhängige Bestätigung; maßgeblich bleiben Mess-Evidenz, deterministische Konsenslogik und Evidence Guard.

## Gemini 3.7 Flash

Status: `ok`

Modell: `gemini-3.5-flash-lite`

Fallbackmodell aktiv: `gemini-3.5-flash-lite`.

- Belastbare Evidenz:
  - Das Profil `d234ab3a232cc544` enthält 5 bestätigte Regeln und 1 Kandidaten (`powerW` auf Kanal 1509, Offset 9), gestützt auf 14 ausgewertete Fahrten.
  - Der libble- und App-Vergleich zeigt, dass `APP_EXPORT_CONSISTENT_WITH_SDK_LAYOUT` vorliegt, was laut Evidence Guard jedoch keinen unabhängigen semantischen Sensornachweis darstellt.
  - Laut BT638 GATT Deep-READ-Abgleich existieren 112 erfolgreiche Callbacks über 6 logische Scans, wobei bestimmte Kanäle wie 1502, 1505, 1506 und 1509 variable Bytes aufweisen.

- Konflikte / mögliche Bugs:
  - Der Leistungskandidat `powerW` (1509/9) basiert auf einem Cross-Field-Check (`abs(voltage_v * current_a)`), wird aber vom Evidence Guard korrekt als "nicht unabhängig" eingestuft (`independentExternalConfirmation: false`).
  - Mehrere Datenqualitätszeilen im libble-Vergleich zeigen 0 akzeptierte Exportzeilen oder ungeklärte READ-/Hybrid-Zähler (`?`), was auf veraltete oder unvollständige Log-Samples hindeutet.
  - Rollen für `motorPower` und `treadlePower` sind in der App-Zuordnung noch offen ("A/B-Rolle noch offen").

- Hypothesen (nicht bestätigt):
  - Kanal 1509 Offset 9 (`powerW`) könnte die tatsächliche elektrische Leistung des Controllers repräsentieren, da die Korrelation mit dem berechneten Strom-Spannungs-Produkt bei 0.9855 liegt; dies ist jedoch ohne physische Externevidenz nur ein internes Konsistenzartefakt.
  - Kanal 150C könnte trotz "Sentinel-only"-Markierung in bestimmten Betriebszuständen als `BatteryCellUpdate` dienen.

- Nächste sichere READ-ONLY-Tests (max. 5):
  1. Durchführung eines passiven BLE-Mitschnitts im Stillstand zur Validierung der statischen Charakteristik von Kanal 1502.
  2. Auswertung von unvollständigen RAW-Exporten (`Messfahrt_2026-08-13_19-17-14` etc.) auf Parser-Fehler oder veraltete Firmware-Strukturen.
  3. Protokollierung von Kanal 1508 (`lightOn` / assistanceLevel) während manueller Lichtschalterbetätigung im Stand (ohne Ladezustand).
  4. Überprüfung der Byte-Variabilität von Kanal 150A und 150B bei minimaler Controller-Last im aufgebockten Zustand (sicherer Stillstand).
  5. Abgleich der Odometer-Werte (Kanal 1506) über mehrere aufeinanderfolgende, kurze READ-Abfragen nach einem Reconnect.

- Automatische Änderungen: KEINE

Freigabe: keine automatische Änderung.

## GLM

Status: `error`

Modell: `glm-5.3`

Fehler: Gratis-/Ratenlimit erreicht (429) • 您的账户已达到速率限制，请您控制请求频率

## OpenAI GPT-5.6 Luna • Synthese, kein Evidenzvotum

Status: `not_configured`

Nicht konfiguriert.

# Auswertung – Messfahrt 2026-08-17 17:05:47

## Datenstand

Der Recorder-Upload ist derzeit noch unvollständig: Auf GitHub liegen `BLE_Rohdaten.csv` und `Automatische_Analyse.txt`. Die übrigen Standarddateien des Messfahrt-Pakets wurden beim ersten Upload noch nicht übertragen. Die Rohdaten selbst sind vollständig lesbar und wurden in den Decoder-AI-Abgleich einbezogen.

## Messqualität

- 938 akzeptierte Notification-Pakete
- Telemetriezeitraum: 347.029 ms (ca. 5:47 min)
- acht bekannte BT638-Kanäle: 1502, 1505, 1506, 1508, 1509, 150A, 150B und 150D
- zwei Verbindungsepochen; eine kurze Start-/Wiederverbindungsphase, danach durchgehende Aufzeichnung
- keine READ- oder Hybrid-Pakete im Rohdatenexport
- keine manuellen Marker und keine neuen unbekannten Schalter-/Impulskandidaten

## Bestätigte Fahrwerte

- Live-Höchstgeschwindigkeit aus 1505 Byte 6–7: **21,8 km/h**
- persistentes Fahrtmaximum aus 150D Byte 0–1: **21,8 km/h**
- persistenter Fahrtdurchschnitt aus 150D Byte 2–3: **15,2 km/h**
- Kilometerstand aus 1506 Byte 0–3: **709,9 km → 710,7 km**, also **+0,8 km** im aufgezeichneten Ausschnitt
- 1505 Byte 10–11 blieb `FFFF`; die SDK-Restreichweite war bei dieser Fahrt daher nicht numerisch verfügbar

## Neue starke 1506-Evidenz

- 1506 Byte 4–7: **141115 → 141323**, also **+208**
- der Zähler steigt im Verlauf ungefähr im Sekundentakt während der Bewegung und bleibt nach dem Anhalten konstant
- zusammen mit der gemessenen Strecke und dem 150D-Durchschnitt ist dies starke Evidenz für einen **aktiven Fahrzeit-Zähler in Sekunden**; die Zuordnung bleibt bis zu einer weiteren Vergleichsfahrt als stark bestätigt, aber defensiv dokumentiert
- 1506 Byte 8–11: **709 → 710** und folgt damit dem ganzzahligen Kilometerstand; dies wirkt wie ein zusätzlicher Ganzkilometer-Spiegel

## Zustände und Akku

- 1508 Byte 0 blieb `0`: Licht während der gesamten Messung aus
- 1508 Byte 3 wechselte mehrfach zwischen `1` und `2`: erneute Bestätigung für **ECO / SPORT**
- 1508 Byte 11 blieb `0`: während dieser Messfahrt war **Zero-Start** aktiv
- Akkuspannung lag zu Beginn bei ungefähr **50,1 V**, fiel unter hoher Last bis ungefähr **48,2 V** und erholte sich nach der Fahrt auf ungefähr **49,5 V**
- der vom Controller gelieferte Prozentwert reagierte ebenfalls auf Last (etwa 70 % im Stand, kurzzeitig 59 % unter Last, anschließend 64 %); das ist als Controller-/BMS-Livewert zu behandeln und nicht als plötzlicher realer Kapazitätsverlust
- 150B bestand weiterhin ausschließlich aus `FF`-Platzhaltern

## Decoder-Entscheidung

Die neue Fahrt bestätigt die vorhandenen Decoder für Geschwindigkeit, Akku, Spannung, Strom, Kilometerstand, Licht/Fahrmodus sowie 150D Max/Ø. Es wurde kein neuer unbekannter Kanal sicher genug erkannt. Deshalb werden keine unsicheren Schalterregeln freigeschaltet. Die sechs bestätigten read-only Decoderregeln bleiben unverändert aktiv.

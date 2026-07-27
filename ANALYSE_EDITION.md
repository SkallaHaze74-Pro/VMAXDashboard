# VMAX Dashboard – Analyse Edition

Diese Version enthält keine Motor-Tuning- oder Steuerbefehle.

## Neu eingebaut

- automatische Aktivierung aller Notify-/Indicate-Kanäle des Dienstes `da1a1500-...`
- eingebauter Protokollkatalog für bekannte und vermutete Kanäle
- bestätigter Akku-Decoder: `1509`, Byte 4, Wertebereich 0–100
- Live-Paketzähler je Kanal
- Erkennung geänderter Bytepositionen gegenüber dem vorherigen Paket
- Zeitstempel relativ zum Beginn jeder Verbindung
- Dauer-Messung mit bis zu 50.000 Datensätzen im Arbeitsspeicher
- CSV-Export nach `Downloads/VMAXDashboard`
- Decoder Lab für Ruhe-/Aktionsvergleich
- übersichtliche Bereiche Fahrt, Akku/Technik, Zubehör/Status und Kanaldiagnose
- klare Kennzeichnung: Bestätigt / Starker Kandidat / Noch unbekannt

## Bereits hinterlegte Kanalhinweise

- `1505`: Sensoren – starker Kandidat
- `1509`: Akkuänderung – bestätigt; Byte 4 als Prozentwert
- `150A`: Motor-Livedaten – starker Kandidat
- `150B`: Motorinformationen – starker Kandidat
- `150C`: Trip-/Fahrdaten – starker Kandidat
- `150D`: Statistik/Gesamtwerte – starker Kandidat
- `1514` bis `1518`: Ereignis-/Zubehörkanäle – noch zuzuordnen

## Empfohlener Testablauf

1. Bildschirmaufnahme starten.
2. App öffnen und verbinden.
3. Scooter 20–30 Sekunden ruhig stehen lassen.
4. Nacheinander Licht, linken Blinker, rechten Blinker und Bremse testen.
5. Langsam anfahren, mehrere Geschwindigkeiten fahren und wieder stoppen.
6. Nach Möglichkeit kurz laden und erneut messen.
7. In der App „CSV in Downloads speichern“ drücken.
8. Bildschirmaufnahme und CSV gemeinsam zur Analyse bereitstellen.

# Gefundene Decoder – Version 6.1 Beta

Ausgewertet wurden 672 BLE-Pakete der Messfahrt mit BT638.

## Direkt live eingebaut

- **1505 Byte 7 / 10** → Geschwindigkeits-/Radwert in km/h
- **1509 Byte 4** → Akkustand in Prozent
- **1509 Byte 5–6 Big Endian / 1000** → Akkuspannung in Volt
- **1509 Byte 0–1 Big Endian / 1000** → Strom-/Lastwert in Ampere
- Leistung wird aus Spannung × Strom berechnet.

## Noch im Lernmodus

- 1506 Byte 6–7: fortlaufender Weg-/Zählerkandidat
- 1508 Byte 0 und Byte 3: Zubehörstatus für Licht/Blinker
- 1505 Byte 0–3: Fahr-/Motorstatus
- 150D Byte 3: Statistik-/Zählerkandidat

Die noch offenen Kandidaten werden nicht als bestätigte Werte dargestellt.

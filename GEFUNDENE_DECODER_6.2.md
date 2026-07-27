# Live AI 6.2 – konservative Decoder

Aus zwei BT638-Prüfstandstests übernommen:

- 1509 Byte 4: Akku % (bestätigt)
- 1505 Byte 7: Fahrwert RAW, Live-Kandidat `/10`
- 1509/150A Byte 0–1 Big Endian: Motor-/Last-Rohwert
- 1509 Byte 6: Akku-/Last-Zustandsstufe RAW
- 1508 Byte 0 und 3: Zubehörstatus RAW

Die zuvor angenommene Spannung/Strom-Skalierung wurde entfernt, weil der zweite Test sie nicht bestätigt. Alle Kandidaten werden zusätzlich in `Live_Telemetrie.csv` gespeichert.

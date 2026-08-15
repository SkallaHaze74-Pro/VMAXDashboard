# VMAX libble Ground-Truth Vergleich

Quelle der Semantik: `libble-sdk-native-lib.so`
Ausgewertete Messfahrten: **5**

## SDK-Felder gegen echten BT638-Livestand

| Feld | Samples | Vergleiche | Treffer | MAE | Urteil |
|---|---:|---:|---:|---:|---|
| 1505.powerA_W | 505 | 0 | – | – | OBSERVED_NEEDS_MORE_PROOF |
| 1505.powerB_W | 505 | 0 | – | – | OBSERVED_NEEDS_MORE_PROOF |
| 1505.speed_kmh | 505 | 505 | 100.00% | 0.000000 | BT638_CONFIRMED |
| 1509.current_A | 505 | 505 | 100.00% | 0.000000 | BT638_CONFIRMED |
| 1509.direct_power_W | 505 | 505 | 100.00% | 0.000000 | BT638_CONFIRMED |
| 1509.secondary_current_A | 505 | 0 | – | – | OBSERVED_NEEDS_MORE_PROOF |
| 1509.soc_percent | 505 | 505 | 100.00% | 0.000000 | BT638_CONFIRMED |
| 1509.voltage_V | 505 | 505 | 100.00% | 0.000000 | BT638_CONFIRMED |
| 150A.motor_current_A | 505 | 0 | – | – | OBSERVED_NEEDS_MORE_PROOF |

## Schutzregel

SDK-bekannte Live-Felder werden als Ground Truth behandelt und dürfen nicht als Licht/Bremse/Blinker-Kandidaten umgedeutet werden.
Unbekannte Felder werden erst danach statistisch gelernt. 150C wird bis zur erneuten Bestätigung der exakten Byte-Offets nur als BatteryCellUpdate-Präsenz ausgewertet.

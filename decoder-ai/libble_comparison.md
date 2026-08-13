# VMAX libble Ground-Truth Vergleich

Quelle der Semantik: `libble-sdk-native-lib.so`
Ausgewertete Messfahrten: **2**

## SDK-Felder gegen echten BT638-Livestand

| Feld | Samples | Vergleiche | Treffer | MAE | Urteil |
|---|---:|---:|---:|---:|---|
| 1505.distance_raw | 1 | 0 | – | – | OBSERVED_NEEDS_MORE_PROOF |
| 1505.powerA_W | 401 | 0 | – | – | OBSERVED_NEEDS_MORE_PROOF |
| 1505.powerB_W | 401 | 0 | – | – | OBSERVED_NEEDS_MORE_PROOF |
| 1505.rpm | 1 | 0 | – | – | OBSERVED_NEEDS_MORE_PROOF |
| 1505.speed_kmh | 400 | 400 | 100.00% | 0.000000 | BT638_CONFIRMED |
| 1505.torque_Nm | 1 | 0 | – | – | OBSERVED_NEEDS_MORE_PROOF |
| 1509.current_A | 401 | 401 | 100.00% | 0.000000 | BT638_CONFIRMED |
| 1509.direct_power_W | 401 | 401 | 65.09% | 34.300499 | OBSERVED_NEEDS_MORE_PROOF |
| 1509.secondary_current_A | 401 | 0 | – | – | OBSERVED_NEEDS_MORE_PROOF |
| 1509.soc_percent | 401 | 401 | 100.00% | 0.000000 | BT638_CONFIRMED |
| 1509.voltage_V | 401 | 401 | 100.00% | 0.000000 | BT638_CONFIRMED |
| 150A.motor_current_A | 401 | 0 | – | – | OBSERVED_NEEDS_MORE_PROOF |

## Schutzregel

SDK-bekannte Live-Felder werden als Ground Truth behandelt und dürfen nicht als Licht/Bremse/Blinker-Kandidaten umgedeutet werden.
Unbekannte Felder werden erst danach statistisch gelernt. 150C wird bis zur erneuten Bestätigung der exakten Byte-Offets nur als BatteryCellUpdate-Präsenz ausgewertet.

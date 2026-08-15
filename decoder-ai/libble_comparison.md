# VMAX libble Ground-Truth Vergleich

Quelle der Semantik: `libble-sdk-native-lib.so`
Ausgewertete Messfahrten: **3**

## SDK-Felder gegen echten BT638-Livestand

| Feld | Samples | Vergleiche | Treffer | MAE | Urteil |
|---|---:|---:|---:|---:|---|
| 1505.distance_raw | 2 | 0 | – | – | OBSERVED_NEEDS_MORE_PROOF |
| 1505.powerA_W | 465 | 0 | – | – | OBSERVED_NEEDS_MORE_PROOF |
| 1505.powerB_W | 465 | 0 | – | – | OBSERVED_NEEDS_MORE_PROOF |
| 1505.rpm | 2 | 0 | – | – | OBSERVED_NEEDS_MORE_PROOF |
| 1505.speed_kmh | 464 | 464 | 100.00% | 0.000000 | BT638_CONFIRMED |
| 1505.torque_Nm | 1 | 0 | – | – | OBSERVED_NEEDS_MORE_PROOF |
| 1509.current_A | 465 | 465 | 100.00% | 0.000000 | BT638_CONFIRMED |
| 1509.direct_power_W | 465 | 464 | 65.95% | 36.091595 | OBSERVED_NEEDS_MORE_PROOF |
| 1509.secondary_current_A | 465 | 0 | – | – | OBSERVED_NEEDS_MORE_PROOF |
| 1509.soc_percent | 465 | 465 | 100.00% | 0.000000 | BT638_CONFIRMED |
| 1509.voltage_V | 465 | 465 | 100.00% | 0.000000 | BT638_CONFIRMED |
| 150A.motor_current_A | 465 | 0 | – | – | OBSERVED_NEEDS_MORE_PROOF |

## Schutzregel

SDK-bekannte Live-Felder werden als Ground Truth behandelt und dürfen nicht als Licht/Bremse/Blinker-Kandidaten umgedeutet werden.
Unbekannte Felder werden erst danach statistisch gelernt. 150C wird bis zur erneuten Bestätigung der exakten Byte-Offets nur als BatteryCellUpdate-Präsenz ausgewertet.

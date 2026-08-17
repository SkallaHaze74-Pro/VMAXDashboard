# VMAX libble Ground-Truth Vergleich

Quelle der Semantik: `libble-sdk-native-lib.so`
Ausgewertete Messfahrten: **8**

## SDK-Felder gegen echten BT638-Livestand

| Feld | Samples | Vergleiche | Treffer | MAE | Urteil |
|---|---:|---:|---:|---:|---|
| 1505.powerA_W | 853 | 0 | – | – | OBSERVED_NEEDS_MORE_PROOF |
| 1505.powerB_W | 853 | 0 | – | – | OBSERVED_NEEDS_MORE_PROOF |
| 1505.speed_kmh | 853 | 853 | 100.00% | 0.000000 | APP_EXPORT_CONSISTENT_WITH_SDK_LAYOUT |
| 1509.current_A | 866 | 866 | 100.00% | 0.000000 | APP_EXPORT_CONSISTENT_WITH_SDK_LAYOUT |
| 1509.direct_power_W | 866 | 865 | 81.74% | 19.360116 | OBSERVED_NEEDS_MORE_PROOF |
| 1509.secondary_current_A | 866 | 0 | – | – | OBSERVED_NEEDS_MORE_PROOF |
| 1509.soc_percent | 866 | 866 | 100.00% | 0.000000 | APP_EXPORT_CONSISTENT_WITH_SDK_LAYOUT |
| 1509.voltage_V | 866 | 866 | 100.00% | 0.000000 | APP_EXPORT_CONSISTENT_WITH_SDK_LAYOUT |
| 150A.motor_current_A | 865 | 0 | – | – | OBSERVED_NEEDS_MORE_PROOF |

## Datenqualität je Export

| Messfahrt | RAW-Exportzeilen | Akzeptierte Exportzeilen | READ im Export | Hybrid im Export | READ laut Zusammenfassung | Hybrid laut Zusammenfassung |
|---|---:|---:|---:|---:|---:|---:|
| Messfahrt_2026-08-13_19-17-14 | 3235 | 3234 | ? | 1 | ? | ? |
| Messfahrt_2026-08-13_20-26-18 | 41 | 41 | ? | 0 | ? | ? |
| Messfahrt_2026-08-15_21-09-05 | 533 | 532 | ? | 1 | ? | ? |
| Messfahrt_2026-08-15_21-12-46 | 104 | 104 | ? | 0 | ? | ? |
| Messfahrt_2026-08-16_00-09-26 | 251 | 251 | ? | 0 | ? | ? |
| Messfahrt_2026-08-16_18-46-03 | 871 | 871 | ? | 0 | ? | ? |
| Messfahrt_2026-08-16_22-12-43 | 1064 | 1064 | 0 | 0 | 168 | 5 |
| Messfahrt_2026-08-17_17-05-47 | 938 | 938 | 0 | 0 | 112 | 6 |

## Schutzregel

SDK-bekannte Layouts dürfen nicht als Licht/Bremse/Blinker-Kandidaten umgedeutet werden.
Der Vergleich prüft die App-Extraktion gegen dasselbe RAW-Paket; er ist kein unabhängiger semantischer Sensornachweis.
READ-/Hybrid-Verwerfungen stammen nur aus `Zusammenfassung.txt`. Fehlt dort der jeweilige Zähler, bleibt er unbekannt; eine Null wird nicht aus den bereits akzeptierten RAW-Exportzeilen abgeleitet.
Unbekannte Felder werden erst danach statistisch gelernt. 150C wird bis zur erneuten Bestätigung der exakten Byte-Offets nur als BatteryCellUpdate-Präsenz ausgewertet.

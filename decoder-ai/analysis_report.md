# VMAX Decoder AI – Konsensbericht

- Fahrten ausgewertet: **7**
- Regeln gesamt: **6**
- Davon bestätigt: **6**
- Profil-Revision: `79c508e77a883d08`

## Regeln

| Status | Signal | Kanal | Feld | Konfidenz | Evidenz | Quelle |
|---|---|---:|---|---:|---:|---|
| confirmed | batteryPercent | 1509 | u8@4 | 99% | 2 Fahrt(en), 531 Samples | original-sdk-layout+app-extraction-check |
| confirmed | currentA | 1509 | s16be@0 | 99% | 4 Fahrt(en), 704 Samples | original-sdk-layout+app-extraction-check |
| confirmed | odometerKm | 1506 | u32be@0 | 99% | 3 Fahrt(en), 641 Samples | original-sdk-layout+app-extraction-check |
| confirmed | powerW | 1509 | u16be@9 | 99% | 2 Fahrt(en), 243 Samples | original-sdk-layout+app-extraction-check |
| confirmed | speedKmh | 1505 | u16be@6 | 99% | 5 Fahrt(en), 727 Samples | original-sdk-layout+app-extraction-check |
| confirmed | voltageV | 1509 | u16be@5 | 99% | 4 Fahrt(en), 704 Samples | original-sdk-layout+app-extraction-check |

## Ground-Truth-Regeln

1505/6 u16be = Geschwindigkeit; 1509/0 s16be = Strom; 1509/4 u8 = SOC; 1509/5 u16be = Spannung; 1509/9 u16be = direkte Leistung; 1506/0 u32be = Kilometerstand.
Die Prozentwerte prüfen die konsistente App-Extraktion derselben RAW-Pakete; sie sind kein unabhängiger semantischer Sensorvergleich.
150D wird nach der ersten echten Fahrt nicht mehr als Live-Geschwindigkeit gelernt.

## Sicherheitsregel

Dieses Profil enthält ausschließlich **Lese-/Decoderregeln**. Es erzeugt keine BLE-Schreibbefehle und verändert keine Motorparameter.


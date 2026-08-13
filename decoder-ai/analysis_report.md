# VMAX Decoder AI – Konsensbericht

- Fahrten ausgewertet: **1**
- Regeln gesamt: **6**
- Davon bestätigt: **0**
- Profil-Revision: `803eaf49a61a3b57`

## Regeln

| Status | Signal | Kanal | Feld | Konfidenz | Evidenz | Quelle |
|---|---|---:|---|---:|---:|---|
| candidate | batteryPercent | 1509 | u8@4 | 98% | 1 Fahrt(en), 397 Samples | numeric-correlation |
| candidate | currentA | 1509 | u16be@0 | 98% | 1 Fahrt(en), 397 Samples | numeric-correlation |
| candidate | odometerKm | 1506 | u16be@2 | 98% | 1 Fahrt(en), 397 Samples | numeric-correlation |
| candidate | speedKmh | 1505 | u16be@6 | 98% | 1 Fahrt(en), 396 Samples | numeric-correlation |
| candidate | speedKmh | 150D | u16be@0 | 98% | 1 Fahrt(en), 397 Samples | numeric-correlation |
| candidate | voltageV | 1509 | u16be@5 | 98% | 1 Fahrt(en), 397 Samples | numeric-correlation |

## Sicherheitsregel

Dieses Profil enthält ausschließlich **Lese-/Decoderregeln**. Es erzeugt keine BLE-Schreibbefehle und verändert keine Motorparameter.


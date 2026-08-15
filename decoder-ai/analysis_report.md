# VMAX Decoder AI – Konsensbericht

- Fahrten ausgewertet: **4**
- Regeln gesamt: **6**
- Davon bestätigt: **3**
- Profil-Revision: `8da423a9e30a3d02`

## Regeln

| Status | Signal | Kanal | Feld | Konfidenz | Evidenz | Quelle |
|---|---|---:|---|---:|---:|---|
| confirmed | currentA | 1509 | u16be@0 | 99% | 2 Fahrt(en), 461 Samples | numeric-correlation |
| confirmed | speedKmh | 1505 | u16be@6 | 99% | 2 Fahrt(en), 460 Samples | numeric-correlation |
| confirmed | voltageV | 1509 | u16be@5 | 99% | 2 Fahrt(en), 461 Samples | numeric-correlation |
| candidate | batteryPercent | 1509 | u8@4 | 98% | 1 Fahrt(en), 397 Samples | numeric-correlation |
| candidate | odometerKm | 1506 | u16be@2 | 98% | 1 Fahrt(en), 397 Samples | numeric-correlation |
| candidate | speedKmh | 150D | u16be@0 | 98% | 1 Fahrt(en), 397 Samples | numeric-correlation |

## Sicherheitsregel

Dieses Profil enthält ausschließlich **Lese-/Decoderregeln**. Es erzeugt keine BLE-Schreibbefehle und verändert keine Motorparameter.


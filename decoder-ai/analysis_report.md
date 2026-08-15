# VMAX Decoder AI – Konsensbericht

- Fahrten ausgewertet: **5**
- Regeln gesamt: **5**
- Davon bestätigt: **3**
- Profil-Revision: `ea50aaaa09c851e9`

## Regeln

| Status | Signal | Kanal | Feld | Konfidenz | Evidenz | Quelle |
|---|---|---:|---|---:|---:|---|
| confirmed | currentA | 1509 | s16be@0 | 99% | 2 Fahrt(en), 461 Samples | libble-ground-truth+numeric |
| confirmed | speedKmh | 1505 | u16be@6 | 99% | 3 Fahrt(en), 491 Samples | libble-ground-truth+numeric |
| confirmed | voltageV | 1509 | u16be@5 | 99% | 2 Fahrt(en), 461 Samples | libble-ground-truth+numeric |
| candidate | batteryPercent | 1509 | u8@4 | 98% | 1 Fahrt(en), 397 Samples | libble-ground-truth+numeric |
| candidate | odometerKm | 1506 | u32be@0 | 98% | 1 Fahrt(en), 397 Samples | libble-ground-truth+numeric |

## Ground-Truth-Regeln

1505/6 u16be = Geschwindigkeit; 1509/0 s16be = Strom; 1509/4 u8 = SOC; 1509/5 u16be = Spannung; 1506/0 u32be = Kilometerstand.
150D wird nach der ersten echten Fahrt nicht mehr als Live-Geschwindigkeit gelernt.

## Sicherheitsregel

Dieses Profil enthält ausschließlich **Lese-/Decoderregeln**. Es erzeugt keine BLE-Schreibbefehle und verändert keine Motorparameter.


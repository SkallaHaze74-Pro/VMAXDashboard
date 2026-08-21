# VMAX Decoder AI – Konsensbericht

- Fahrten ausgewertet: **13**
- Regeln gesamt: **6**
- Davon bestätigt: **5**
- Profil-Revision: `0094d27e1200aa83`

## Regeln

| Status | Signal | Kanal | Feld | Konfidenz | Evidenz | Quelle |
|---|---|---:|---|---:|---:|---|
| confirmed | batteryPercent | 1509 | u8@4 | 99% | 4 Fahrt(en), 868 Samples | original-sdk-layout+app-extraction-check |
| confirmed | currentA | 1509 | s16be@0 | 99% | 10 Fahrt(en), 1400 Samples | original-sdk-layout+app-extraction-check |
| confirmed | odometerKm | 1506 | u32be@0 | 99% | 8 Fahrt(en), 1256 Samples | original-sdk-layout+app-extraction-check |
| candidate | powerW | 1509 | u16be@9 | 93% | 8 Fahrt(en), 939 Samples | sdk-layout+cross-field-check-needs-external-proof |
| confirmed | speedKmh | 1505 | u16be@6 | 99% | 11 Fahrt(en), 1414 Samples | original-sdk-layout+app-extraction-check |
| confirmed | voltageV | 1509 | u16be@5 | 99% | 10 Fahrt(en), 1400 Samples | original-sdk-layout+app-extraction-check |

## Ground-Truth-Regeln

1505/6 u16be = Geschwindigkeit; 1509/0 s16be = Strom; 1509/4 u8 = SOC; 1509/5 u16be = Spannung; 1506/0 u32be = Kilometerstand.
1509/9 u16be ist als SDK-Layout beobachtet, bleibt aber ein Leistungs-Kandidat, bis eine unabhängige physikalische/semantische Validierung vorliegt.
Die Prozentwerte aus derselben RAW-Extraktion belegen Layoutkonsistenz, nicht automatisch die physikalische Bedeutung.
150D wird nach der ersten echten Fahrt nicht mehr als Live-Geschwindigkeit gelernt.

## Power-Cross-Check ohne Selbstbestätigung

1509/9 wird gegen |Spannung × Strom| aus den anderen 1509-Feldern geprüft, nicht gegen den eigenen `power_w`-Export.
Vergleiche: **830** • Nähe: **95.18%** • MAE: **4.130964 W** • Korrelation: **0.984642**.
Auch diese Cross-Field-Übereinstimmung ist noch keine externe Ground Truth und aktiviert keine Regel automatisch.

## Sicherheitsregel

Dieses Profil enthält ausschließlich **Lese-/Decoderregeln**. Kandidaten werden nicht als bestätigte Live-Regeln aktiviert.


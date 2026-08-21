# Batterie-Zielanalyse 1502 / 150C

Fahrten: **13**

## 1502 – statische Akkuinformationen

- Pakete: **1449**; verschiedene Pakete: **1**
- Längen: `{"16": 1449}`
- Häufigste Pakete:
  - `47-18-FF-FF-FF-FF-47-18-FF-FF-FF-FF-FF-FF-00-00` × 1449

### Ausgerichtete u16be-Wörter

| Offset | gültig | verschieden | min | max | häufigste Werte |
|---:|---:|---:|---:|---:|---|
| 0 | 1449 | 1 | 18200 | 18200 | 18200×1449 |
| 2 | 0 | 0 | None | None | – |
| 4 | 0 | 0 | None | None | – |
| 6 | 1449 | 1 | 18200 | 18200 | 18200×1449 |
| 8 | 0 | 0 | None | None | – |
| 10 | 0 | 0 | None | None | – |
| 12 | 0 | 0 | None | None | – |
| 14 | 1449 | 1 | 0 | 0 | 0×1449 |

### Auffällig gleiche Wortpaare

- Offset 0 ↔ 6: 100.0% gleich (1449 Paare)

## 150C – BatteryCellUpdate

- Pakete: **6**; verschiedene Pakete: **1**
- Längen: `{"13": 6}`
- `FF-FF-FF-80-00-80-00-80-00-00-00-00-00` × 6

## Noch offen

- batteryCapacityMwh
- chargingRemainSeconds
- charging
- stateOfHealthPercent
- stateOfHealthMwh

> Keine Zuordnung wird aus einem plausiblen Zahlenwert allein bestätigt. 1502/150C bleiben bis zu unabhängiger Evidenz Kandidaten-/Diagnosequellen.

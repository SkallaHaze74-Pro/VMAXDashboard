# BT638 GATT Deep-READ-Abgleich

Logische Scans: **6** • Quellkopien: **6** • READ-Versuche: **112** • echte Callbacks: **112** • Callback-Payloads: **112** (valide: **112**) • Beobachtungen: **65**

> STRICT READ-ONLY. Nur ein echter GATT-Callback zählt als Antwort; auch er beweist noch keine Byte-Semantik. READ-Daten und BLE-Beobachtungen bleiben von Live-Telemetrie und Decoder-Lernen getrennt.

| Service | Characteristic | SDK/Inventar-Ziel | Callback-Erfolg | Versuche | Payloads | Varianten | variable Bytes | Sentinel-only |
|---|---|---|---:|---:|---:|---:|---|---|
| 0203 | 0203 | – | 4/4 | 4 | 4 | 2 | – | ja |
| 1500 | 1501 | – | 4/4 | 4 | 4 | 1 | – | nein |
| 1500 | 1502 | Battery/static candidate | 4/4 | 4 | 4 | 2 | – | nein |
| 1500 | 1503 | – | 4/4 | 4 | 4 | 2 | – | ja |
| 1500 | 1504 | – | 4/4 | 4 | 4 | 1 | – | nein |
| 1500 | 1505 | – | 4/4 | 4 | 4 | 6 | 7 | nein |
| 1500 | 1506 | – | 4/4 | 4 | 4 | 8 | 3,7,11 | nein |
| 1500 | 1507 | – | 4/4 | 4 | 4 | 6 | 5,7 | nein |
| 1500 | 1508 | – | 4/4 | 4 | 4 | 4 | 0,3 | nein |
| 1500 | 1509 | Battery live layout | 4/4 | 4 | 4 | 6 | 4,5,6 | nein |
| 1500 | 150A | Motor live layout | 4/4 | 4 | 4 | 2 | – | ja |
| 1500 | 150B | – | 4/4 | 4 | 4 | 2 | – | ja |
| 1500 | 150C | BatteryCellUpdate candidate | 4/4 | 4 | 4 | 2 | – | ja |
| 1500 | 150D | – | 4/4 | 4 | 4 | 6 | 1,3 | nein |
| 1500 | 150E | – | 4/4 | 4 | 4 | 1 | – | nein |
| 1500 | 150F | – | 4/4 | 4 | 4 | 1 | – | nein |
| 1500 | 1510 | – | 4/4 | 4 | 4 | 1 | – | nein |
| 1500 | 1511 | – | 4/4 | 4 | 4 | 2 | – | nein |
| 1500 | 1512 | – | 4/4 | 4 | 4 | 1 | – | nein |
| 1500 | 1513 | – | 4/4 | 4 | 4 | 2 | – | nein |
| 1500 | 151C | – | 4/4 | 4 | 4 | 1 | – | nein |
| 1800 | 1802 | – | 4/4 | 4 | 4 | 5 | – | nein |
| 1800 | 2A00 | – | 4/4 | 4 | 4 | 1 | – | nein |
| 1800 | 2A01 | – | 4/4 | 4 | 4 | 2 | – | ja |
| 1800 | 2A02 | – | 4/4 | 4 | 4 | 2 | – | ja |
| 1800 | 2A04 | – | 4/4 | 4 | 4 | 2 | – | nein |
| 1800 | 2A28 | – | 4/4 | 4 | 4 | 1 | – | nein |
| 1801 | 2A05 | – | 4/4 | 4 | 4 | 2 | – | ja |

## Priorität für die nächste Prüfung

- **0203** — ändert sich zwischen Scans; bisher nur Sentinel-/Platzhalterbytes
- **1502** — Battery/static candidate; ändert sich zwischen Scans
- **1503** — ändert sich zwischen Scans; bisher nur Sentinel-/Platzhalterbytes
- **1505** — ändert sich zwischen Scans
- **1506** — ändert sich zwischen Scans
- **1507** — ändert sich zwischen Scans
- **1508** — ändert sich zwischen Scans
- **1509** — Battery live layout; ändert sich zwischen Scans
- **150A** — Motor live layout; ändert sich zwischen Scans; bisher nur Sentinel-/Platzhalterbytes
- **150B** — ändert sich zwischen Scans; bisher nur Sentinel-/Platzhalterbytes
- **150C** — BatteryCellUpdate candidate; ändert sich zwischen Scans; bisher nur Sentinel-/Platzhalterbytes
- **150D** — ändert sich zwischen Scans

## Evidenzgrenze

- Timeout, Verbindungsende und Advertisement sind Beobachtungen, keine READ-Antworten.
- Kein READ-Wert wird automatisch als SOC, SOH, Zellspannung, Seriennummer oder Controllerparameter bezeichnet.
- Kein KI-Konsens kann diese Grenze ersetzen.
- Es werden keine Schreibframes, Schlüssel oder Authentifizierungswerte aus READ-Daten erzeugt.

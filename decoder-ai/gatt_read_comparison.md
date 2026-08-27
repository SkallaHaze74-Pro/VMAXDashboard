# BT638 GATT Deep-READ-Abgleich

Logische Scans: **40** • Quellkopien: **69** • READ-Versuche: **700** • echte Callbacks: **700** • Callback-Payloads: **700** (valide: **700**) • Beobachtungen: **666**

> STRICT READ-ONLY. Nur ein echter GATT-Callback zählt als Antwort; auch er beweist noch keine Byte-Semantik. READ-Daten und BLE-Beobachtungen bleiben von Live-Telemetrie und Decoder-Lernen getrennt.

| Service | Characteristic | SDK/Inventar-Ziel | Callback-Erfolg | Versuche | Payloads | Varianten | variable Bytes | Sentinel-only |
|---|---|---|---:|---:|---:|---:|---|---|
| 0203 | 0203 | – | 25/25 | 25 | 25 | 1 | – | ja |
| 1500 | 1501 | – | 25/25 | 25 | 25 | 1 | – | nein |
| 1500 | 1502 | Battery/static candidate | 25/25 | 25 | 25 | 1 | – | nein |
| 1500 | 1503 | – | 25/25 | 25 | 25 | 3 | 0,1 | nein |
| 1500 | 1504 | – | 25/25 | 25 | 25 | 1 | – | nein |
| 1500 | 1505 | – | 25/25 | 25 | 25 | 7 | 0,1,2,3,7 | nein |
| 1500 | 1506 | – | 25/25 | 25 | 25 | 19 | 2,3,6,7,11 | nein |
| 1500 | 1507 | – | 25/25 | 25 | 25 | 9 | 4,5,7 | nein |
| 1500 | 1508 | – | 25/25 | 25 | 25 | 4 | 0,3 | nein |
| 1500 | 1509 | Battery live layout | 25/25 | 25 | 25 | 24 | 0,1,4,5,6,10 | nein |
| 1500 | 150A | Motor live layout | 25/25 | 25 | 25 | 3 | 0,1 | nein |
| 1500 | 150B | – | 25/25 | 25 | 25 | 1 | – | ja |
| 1500 | 150C | BatteryCellUpdate candidate | 25/25 | 25 | 25 | 1 | – | ja |
| 1500 | 150D | – | 25/25 | 25 | 25 | 9 | 0,1,3 | nein |
| 1500 | 150E | – | 25/25 | 25 | 25 | 1 | – | nein |
| 1500 | 150F | – | 25/25 | 25 | 25 | 1 | – | nein |
| 1500 | 1510 | – | 25/25 | 25 | 25 | 1 | – | nein |
| 1500 | 1511 | – | 25/25 | 25 | 25 | 1 | – | nein |
| 1500 | 1512 | – | 25/25 | 25 | 25 | 1 | – | nein |
| 1500 | 1513 | – | 25/25 | 25 | 25 | 1 | – | nein |
| 1500 | 151C | – | 25/25 | 25 | 25 | 1 | – | nein |
| 1800 | 1802 | – | 25/25 | 25 | 25 | 3 | – | nein |
| 1800 | 2A00 | – | 25/25 | 25 | 25 | 1 | – | nein |
| 1800 | 2A01 | – | 25/25 | 25 | 25 | 1 | – | ja |
| 1800 | 2A02 | – | 25/25 | 25 | 25 | 1 | – | ja |
| 1800 | 2A04 | – | 25/25 | 25 | 25 | 1 | – | nein |
| 1800 | 2A28 | – | 25/25 | 25 | 25 | 1 | – | nein |
| 1801 | 2A05 | – | 25/25 | 25 | 25 | 1 | – | ja |

## Priorität für die nächste Prüfung

- **1502** — Battery/static candidate
- **1503** — ändert sich zwischen Scans
- **1505** — ändert sich zwischen Scans
- **1506** — ändert sich zwischen Scans
- **1507** — ändert sich zwischen Scans
- **1508** — ändert sich zwischen Scans
- **1509** — Battery live layout; ändert sich zwischen Scans
- **150A** — Motor live layout; ändert sich zwischen Scans
- **150C** — BatteryCellUpdate candidate; bisher nur Sentinel-/Platzhalterbytes
- **150D** — ändert sich zwischen Scans
- **1802** — ändert sich zwischen Scans; öffentliche Payload SHA-256-redigiert

## Evidenzgrenze

- Timeout, Verbindungsende und Advertisement sind Beobachtungen, keine READ-Antworten.
- Kein READ-Wert wird automatisch als SOC, SOH, Zellspannung, Seriennummer oder Controllerparameter bezeichnet.
- Kein KI-Konsens kann diese Grenze ersetzen.
- Es werden keine Schreibframes, Schlüssel oder Authentifizierungswerte aus READ-Daten erzeugt.

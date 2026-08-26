# BT638 GATT Deep-READ-Abgleich

Logische Scans: **38** • Quellkopien: **67** • READ-Versuche: **672** • echte Callbacks: **672** • Callback-Payloads: **672** (valide: **672**) • Beobachtungen: **608**

> STRICT READ-ONLY. Nur ein echter GATT-Callback zählt als Antwort; auch er beweist noch keine Byte-Semantik. READ-Daten und BLE-Beobachtungen bleiben von Live-Telemetrie und Decoder-Lernen getrennt.

| Service | Characteristic | SDK/Inventar-Ziel | Callback-Erfolg | Versuche | Payloads | Varianten | variable Bytes | Sentinel-only |
|---|---|---|---:|---:|---:|---:|---|---|
| 0203 | 0203 | – | 24/24 | 24 | 24 | 1 | – | ja |
| 1500 | 1501 | – | 24/24 | 24 | 24 | 1 | – | nein |
| 1500 | 1502 | Battery/static candidate | 24/24 | 24 | 24 | 1 | – | nein |
| 1500 | 1503 | – | 24/24 | 24 | 24 | 3 | 0,1 | nein |
| 1500 | 1504 | – | 24/24 | 24 | 24 | 1 | – | nein |
| 1500 | 1505 | – | 24/24 | 24 | 24 | 7 | 0,1,2,3,7 | nein |
| 1500 | 1506 | – | 24/24 | 24 | 24 | 19 | 2,3,6,7,11 | nein |
| 1500 | 1507 | – | 24/24 | 24 | 24 | 9 | 4,5,7 | nein |
| 1500 | 1508 | – | 24/24 | 24 | 24 | 4 | 0,3 | nein |
| 1500 | 1509 | Battery live layout | 24/24 | 24 | 24 | 23 | 0,1,4,5,6,10 | nein |
| 1500 | 150A | Motor live layout | 24/24 | 24 | 24 | 3 | 0,1 | nein |
| 1500 | 150B | – | 24/24 | 24 | 24 | 1 | – | ja |
| 1500 | 150C | BatteryCellUpdate candidate | 24/24 | 24 | 24 | 1 | – | ja |
| 1500 | 150D | – | 24/24 | 24 | 24 | 9 | 0,1,3 | nein |
| 1500 | 150E | – | 24/24 | 24 | 24 | 1 | – | nein |
| 1500 | 150F | – | 24/24 | 24 | 24 | 1 | – | nein |
| 1500 | 1510 | – | 24/24 | 24 | 24 | 1 | – | nein |
| 1500 | 1511 | – | 24/24 | 24 | 24 | 1 | – | nein |
| 1500 | 1512 | – | 24/24 | 24 | 24 | 1 | – | nein |
| 1500 | 1513 | – | 24/24 | 24 | 24 | 1 | – | nein |
| 1500 | 151C | – | 24/24 | 24 | 24 | 1 | – | nein |
| 1800 | 1802 | – | 24/24 | 24 | 24 | 3 | – | nein |
| 1800 | 2A00 | – | 24/24 | 24 | 24 | 1 | – | nein |
| 1800 | 2A01 | – | 24/24 | 24 | 24 | 1 | – | ja |
| 1800 | 2A02 | – | 24/24 | 24 | 24 | 1 | – | ja |
| 1800 | 2A04 | – | 24/24 | 24 | 24 | 1 | – | nein |
| 1800 | 2A28 | – | 24/24 | 24 | 24 | 1 | – | nein |
| 1801 | 2A05 | – | 24/24 | 24 | 24 | 1 | – | ja |

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

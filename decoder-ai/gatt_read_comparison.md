# BT638 GATT Deep-READ-Abgleich

Logische Scans: **57** • Quellkopien: **103** • READ-Versuche: **980** • echte Callbacks: **980** • Callback-Payloads: **980** (valide: **980**) • Beobachtungen: **1076**

> STRICT READ-ONLY. Nur ein echter GATT-Callback zählt als Antwort; auch er beweist noch keine Byte-Semantik. READ-Daten und BLE-Beobachtungen bleiben von Live-Telemetrie und Decoder-Lernen getrennt.

| Service | Characteristic | SDK/Inventar-Ziel | Callback-Erfolg | Versuche | Payloads | Varianten | variable Bytes | Sentinel-only |
|---|---|---|---:|---:|---:|---:|---|---|
| 0203 | 0203 | – | 35/35 | 35 | 35 | 1 | – | ja |
| 1500 | 1501 | – | 35/35 | 35 | 35 | 1 | – | nein |
| 1500 | 1502 | Battery/static candidate | 35/35 | 35 | 35 | 1 | – | nein |
| 1500 | 1503 | – | 35/35 | 35 | 35 | 4 | 0,1 | nein |
| 1500 | 1504 | – | 35/35 | 35 | 35 | 1 | – | nein |
| 1500 | 1505 | – | 35/35 | 35 | 35 | 9 | 0,1,2,3,7 | nein |
| 1500 | 1506 | – | 35/35 | 35 | 35 | 26 | 2,3,6,7,10,11 | nein |
| 1500 | 1507 | – | 35/35 | 35 | 35 | 12 | 4,5,7 | nein |
| 1500 | 1508 | – | 35/35 | 35 | 35 | 4 | 0,3 | nein |
| 1500 | 1509 | Battery live layout | 35/35 | 35 | 35 | 31 | 0,1,4,5,6,9,10 | nein |
| 1500 | 150A | Motor live layout | 35/35 | 35 | 35 | 4 | 0,1 | nein |
| 1500 | 150B | – | 35/35 | 35 | 35 | 1 | – | ja |
| 1500 | 150C | BatteryCellUpdate candidate | 35/35 | 35 | 35 | 1 | – | ja |
| 1500 | 150D | – | 35/35 | 35 | 35 | 12 | 0,1,3 | nein |
| 1500 | 150E | – | 35/35 | 35 | 35 | 1 | – | nein |
| 1500 | 150F | – | 35/35 | 35 | 35 | 1 | – | nein |
| 1500 | 1510 | – | 35/35 | 35 | 35 | 1 | – | nein |
| 1500 | 1511 | – | 35/35 | 35 | 35 | 1 | – | nein |
| 1500 | 1512 | – | 35/35 | 35 | 35 | 1 | – | nein |
| 1500 | 1513 | – | 35/35 | 35 | 35 | 1 | – | nein |
| 1500 | 151C | – | 35/35 | 35 | 35 | 1 | – | nein |
| 1800 | 1802 | – | 35/35 | 35 | 35 | 3 | – | nein |
| 1800 | 2A00 | – | 35/35 | 35 | 35 | 1 | – | nein |
| 1800 | 2A01 | – | 35/35 | 35 | 35 | 1 | – | ja |
| 1800 | 2A02 | – | 35/35 | 35 | 35 | 1 | – | ja |
| 1800 | 2A04 | – | 35/35 | 35 | 35 | 1 | – | nein |
| 1800 | 2A28 | – | 35/35 | 35 | 35 | 1 | – | nein |
| 1801 | 2A05 | – | 35/35 | 35 | 35 | 1 | – | ja |

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

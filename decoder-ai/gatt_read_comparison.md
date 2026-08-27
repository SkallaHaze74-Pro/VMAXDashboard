# BT638 GATT Deep-READ-Abgleich

Logische Scans: **43** • Quellkopien: **74** • READ-Versuche: **728** • echte Callbacks: **728** • Callback-Payloads: **728** (valide: **728**) • Beobachtungen: **725**

> STRICT READ-ONLY. Nur ein echter GATT-Callback zählt als Antwort; auch er beweist noch keine Byte-Semantik. READ-Daten und BLE-Beobachtungen bleiben von Live-Telemetrie und Decoder-Lernen getrennt.

| Service | Characteristic | SDK/Inventar-Ziel | Callback-Erfolg | Versuche | Payloads | Varianten | variable Bytes | Sentinel-only |
|---|---|---|---:|---:|---:|---:|---|---|
| 0203 | 0203 | – | 26/26 | 26 | 26 | 1 | – | ja |
| 1500 | 1501 | – | 26/26 | 26 | 26 | 1 | – | nein |
| 1500 | 1502 | Battery/static candidate | 26/26 | 26 | 26 | 1 | – | nein |
| 1500 | 1503 | – | 26/26 | 26 | 26 | 3 | 0,1 | nein |
| 1500 | 1504 | – | 26/26 | 26 | 26 | 1 | – | nein |
| 1500 | 1505 | – | 26/26 | 26 | 26 | 7 | 0,1,2,3,7 | nein |
| 1500 | 1506 | – | 26/26 | 26 | 26 | 20 | 2,3,6,7,11 | nein |
| 1500 | 1507 | – | 26/26 | 26 | 26 | 9 | 4,5,7 | nein |
| 1500 | 1508 | – | 26/26 | 26 | 26 | 4 | 0,3 | nein |
| 1500 | 1509 | Battery live layout | 26/26 | 26 | 26 | 25 | 0,1,4,5,6,10 | nein |
| 1500 | 150A | Motor live layout | 26/26 | 26 | 26 | 3 | 0,1 | nein |
| 1500 | 150B | – | 26/26 | 26 | 26 | 1 | – | ja |
| 1500 | 150C | BatteryCellUpdate candidate | 26/26 | 26 | 26 | 1 | – | ja |
| 1500 | 150D | – | 26/26 | 26 | 26 | 9 | 0,1,3 | nein |
| 1500 | 150E | – | 26/26 | 26 | 26 | 1 | – | nein |
| 1500 | 150F | – | 26/26 | 26 | 26 | 1 | – | nein |
| 1500 | 1510 | – | 26/26 | 26 | 26 | 1 | – | nein |
| 1500 | 1511 | – | 26/26 | 26 | 26 | 1 | – | nein |
| 1500 | 1512 | – | 26/26 | 26 | 26 | 1 | – | nein |
| 1500 | 1513 | – | 26/26 | 26 | 26 | 1 | – | nein |
| 1500 | 151C | – | 26/26 | 26 | 26 | 1 | – | nein |
| 1800 | 1802 | – | 26/26 | 26 | 26 | 3 | – | nein |
| 1800 | 2A00 | – | 26/26 | 26 | 26 | 1 | – | nein |
| 1800 | 2A01 | – | 26/26 | 26 | 26 | 1 | – | ja |
| 1800 | 2A02 | – | 26/26 | 26 | 26 | 1 | – | ja |
| 1800 | 2A04 | – | 26/26 | 26 | 26 | 1 | – | nein |
| 1800 | 2A28 | – | 26/26 | 26 | 26 | 1 | – | nein |
| 1801 | 2A05 | – | 26/26 | 26 | 26 | 1 | – | ja |

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

# Lade-/BLE-Übergangsanalyse

Fahrten: **13**
BLE-/1509-Übergänge: **3**
Explizite Lade-Übergänge: **0**
Batterieanstieg ohne expliziten Marker: **3**

> Während einer BLE-Lücke werden keine Werte erfunden. Es werden nur der letzte echte 1509-Zustand davor und der erste echte Zustand danach verglichen.

| Fahrt | Gap | Status | SOC Δ | Volt Δ | Marker |
|---|---:|---|---:|---:|---|
| Messfahrt_2026-08-16_22-12-43 | 7.9s | BATTERY_RISE_DURING_BLE_GAP | 1 | 0.0 | START / BLE getrennt • RAW 8 gesichert / BLE-Link wieder verbunden • Messfahrtdaten erhalten / Telemetrie wieder aktiv |
| Messfahrt_2026-08-17_17-05-47 | 7.1s | BATTERY_RISE_DURING_BLE_GAP | 1 | 0.0 | START / BLE getrennt • RAW 8 gesichert / BLE-Link wieder verbunden • Messfahrtdaten erhalten / Telemetrie wieder aktiv |
| Messfahrt_2026-08-17_20-24-15 | 7.5s | BATTERY_RISE_DURING_BLE_GAP | 1 | 0.0 | START / BLE getrennt • RAW 8 gesichert / BLE-Link wieder verbunden • Messfahrtdaten erhalten / Telemetrie wieder aktiv |

## Evidenzregel

BLE-Lücken werden nur als Vorher/Nachher-Übergang ausgewertet. Eine Lücke allein beweist keinen Ladevorgang.


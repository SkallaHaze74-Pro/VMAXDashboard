# Zielanalyse 1505 + Batterie/SOH

Ausgewertete Fahrten: **13**
1505-NOTIFY-Pakete: **1430**

## Power A/B — 1505/0 und 1505/2

- Status: `DUPLICATED_ON_CURRENT_BT638_DATA`
- Gültige A/B-Paare: **1430**
- Exakt gleich: **1429** (99.93%)
- Max. A/B-Abweichung: **1919.6 W**
- A vs |V×I|: `{"comparisons": 12, "maeW": 0.0, "medianAbsErrorW": 0.0, "closePercent": 100.0, "correlation": null}`
- B vs |V×I|: `{"comparisons": 12, "maeW": 0.0, "medianAbsErrorW": 0.0, "closePercent": 100.0, "correlation": null}`

## RPM — 1505/8

- Status: `RPM_VALUES_OBSERVED_NEEDS_VALIDATION`
- RAW-Zustände: `{"ffff": 1428, "valid": 2}`
- Werte: `{"samples": 2, "min": 13108.0, "max": 13108.0, "mean": 13108.0, "distinct": 1}`
- Korrelation zu Speed: `None`

## Restreichweite — 1505/10

- Status: `RANGE_VALUES_OBSERVED_NEEDS_VALIDATION`
- RAW-Zustände: `{"ffff": 1428, "valid": 2}`
- Werte: `{"samples": 2, "min": 13622.0, "max": 13622.0, "mean": 13622.0, "distinct": 1}`
- Korrelation zu SOC: `None`

## Batterie/SOH

Noch ohne erfundene Zuordnung: batteryCapacityMwh, chargingRemainSeconds, charging, stateOfHealthPercent, stateOfHealthMwh.
Die Kanäle werden nur auf Präsenz/Längen inventarisiert. Für dynamische Werte hilft eine Langfahrt; Charging/SOH kann zusätzliche Lade- oder READ-Evidenz brauchen.

## Sicherheits-/Evidenzregel

Diese Analyse ist rein lesend. Sie erzeugt keine BLE-Schreibbefehle und bestätigt keine Semantik allein aus Korrelation.

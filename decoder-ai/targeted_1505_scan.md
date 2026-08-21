# Zielanalyse 1505 + Batterie/SOH

Ausgewertete Fahrten: **13**
1505-NOTIFY-Pakete: **1428**
Verworfene 1505 ASCII-/READ-Hybridframes: **2**

## Power A/B — 1505/0 und 1505/2

- Status: `DUPLICATED_ON_CURRENT_BT638_DATA`
- Gültige A/B-Paare: **1428**
- Exakt gleich: **1428** (100.0%)
- Max. A/B-Abweichung: **0.0 W**
- A vs |V×I|: `{"comparisons": 12, "maeW": 0.0, "medianAbsErrorW": 0.0, "closePercent": 100.0, "correlation": null}`
- B vs |V×I|: `{"comparisons": 12, "maeW": 0.0, "medianAbsErrorW": 0.0, "closePercent": 100.0, "correlation": null}`

## RPM — 1505/8

- Status: `NO_VALID_RPM_SAMPLES_CURRENT_DATA`
- RAW-Zustände: `{"ffff": 1428}`
- Werte: `{"samples": 0}`
- Korrelation zu Speed: `None`

## Restreichweite — 1505/10

- Status: `NO_VALID_RANGE_SAMPLES_CURRENT_DATA`
- RAW-Zustände: `{"ffff": 1428}`
- Werte: `{"samples": 0}`
- Korrelation zu SOC: `None`

## Batterie/SOH

Noch ohne erfundene Zuordnung: batteryCapacityMwh, chargingRemainSeconds, charging, stateOfHealthPercent, stateOfHealthMwh.
Die Kanäle werden nur auf Präsenz/Längen inventarisiert. Für dynamische Werte hilft eine Langfahrt; Charging/SOH kann zusätzliche Lade- oder READ-Evidenz brauchen.

## Sicherheits-/Evidenzregel

Diese Analyse ist rein lesend. Sie erzeugt keine BLE-Schreibbefehle und bestätigt keine Semantik allein aus Korrelation.

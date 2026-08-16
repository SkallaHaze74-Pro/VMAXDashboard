# Original VMAX-App ↔ BT638 Echtzeit-Abgleich

Die Original-App-Callbacks definieren die Soll-Semantik. libble definiert bekannte Parserfelder. BT638-Fahrdaten entscheiden, ob die Zuordnung auf diesem Modell wirklich gilt.
Ein Treffer `APP_EXPORT_CONSISTENT_WITH_SDK_LAYOUT` vergleicht zwei Extraktionen desselben RAW-Pakets. Er belegt Layoutkonsistenz, ist aber kein unabhängiger Live-Sensornachweis und erhöht `confirmedByLiveEvidence` nicht.

| Original-Livewert | Callback | Mapping | Status |
|---|---|---|---|
| assistanceLevel | ControllerPartListener.onAssistanceLevel | 1508 @ 3 u8 | MAPPED_VERIFY_WITH_MORE_BT638_DATA |
| odometerKm | ControllerPartListener.onOdoInKm | 1506 @ 0 u32be | MAPPED_VERIFY_WITH_MORE_BT638_DATA |
| rpm | ControllerPartListener.onRPM | 1505 @ 8 u16be | MAPPED_VERIFY_WITH_MORE_BT638_DATA |
| remainingDistanceKm | ControllerPartListener.onRemainDistanceInKm | 1505 @ 10 u16be | MAPPED_VERIFY_WITH_MORE_BT638_DATA |
| speedKmh | ControllerPartListener.onSpeedInKm | 1505 @ 6 u16be | APP_EXPORT_LAYOUT_CONSISTENT_NON_INDEPENDENT |
| motorPower | ControllerPartListener.onMotorPowerInmw | A/B-Rolle noch offen | ROLE_ASSIGNMENT_REQUIRED |
| treadlePower | ControllerPartListener.onTreadlePowerInmw | A/B-Rolle noch offen | ROLE_ASSIGNMENT_REQUIRED |
| batteryVoltageMv | BatteryPartListener.onActVoltageInmv | 1509 @ 5 u16be | APP_EXPORT_LAYOUT_CONSISTENT_NON_INDEPENDENT |
| batteryPercent | BatteryPartListener.onCapacityInPercentage / ISOCListener.onISOCInPercentage | 1509 @ 4 u8 | APP_EXPORT_LAYOUT_CONSISTENT_NON_INDEPENDENT |
| batteryCapacityMwh | BatteryPartListener.onCapacityInmwh | gezielt suchen | TARGETED_MAPPING_REQUIRED |
| chargingRemainSeconds | BatteryPartListener.onChargingRemainSecond | gezielt suchen | TARGETED_MAPPING_REQUIRED |
| charging | BatteryPartListener.onIsCharging | gezielt suchen | TARGETED_MAPPING_REQUIRED |
| stateOfHealthPercent | BatteryPartListener.onStateOfHealthInPercentage | gezielt suchen | TARGETED_MAPPING_REQUIRED |
| stateOfHealthMwh | BatteryPartListener.onStateOfHealthInmwh | gezielt suchen | TARGETED_MAPPING_REQUIRED |
| batteryCurrentMa | BatteryPartListener.onActCurrentInma | 1509 @ 0 s16be | APP_EXPORT_LAYOUT_CONSISTENT_NON_INDEPENDENT |
| lightOn | LightPartListener.onLightState | 1508 @ 0 u8 | MAPPED_VERIFY_WITH_MORE_BT638_DATA |

## Noch gezielt zuzuordnen

- **batteryCapacityMwh** — BatteryPartListener.onCapacityInmwh (mWh)
- **chargingRemainSeconds** — BatteryPartListener.onChargingRemainSecond (s)
- **charging** — BatteryPartListener.onIsCharging (boolean)
- **stateOfHealthPercent** — BatteryPartListener.onStateOfHealthInPercentage (%)
- **stateOfHealthMwh** — BatteryPartListener.onStateOfHealthInmwh (mWh)

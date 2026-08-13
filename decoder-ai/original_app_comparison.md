# Original VMAX-App ↔ BT638 Echtzeit-Abgleich

Die Original-App-Callbacks definieren die Soll-Semantik. libble definiert bekannte Parserfelder. BT638-Fahrdaten entscheiden, ob die Zuordnung auf diesem Modell wirklich gilt.

| Original-Livewert | Callback | Mapping | Status |
|---|---|---|---|
| assistanceLevel | ControllerPartListener.onAssistanceLevel | 1508 @ 3 u8 | MAPPED_VERIFY_WITH_MORE_BT638_DATA |
| odometerKm | ControllerPartListener.onOdoInKm | 1506 @ 0 u32be | MAPPED_VERIFY_WITH_MORE_BT638_DATA |
| rpm | ControllerPartListener.onRPM | 1505 @ 8 u16be | MAPPED_VERIFY_WITH_MORE_BT638_DATA |
| remainingDistanceKm | ControllerPartListener.onRemainDistanceInKm | gezielt suchen | TARGETED_MAPPING_REQUIRED |
| speedKmh | ControllerPartListener.onSpeedInKm | 1505 @ 6 u16be | BT638_CONFIRMED_BY_LIBBLE_AND_LIVE |
| motorPower | ControllerPartListener.onMotorPowerInmw | A/B-Rolle noch offen | ROLE_ASSIGNMENT_REQUIRED |
| treadlePower | ControllerPartListener.onTreadlePowerInmw | A/B-Rolle noch offen | ROLE_ASSIGNMENT_REQUIRED |
| batteryVoltageMv | BatteryPartListener.onActVoltageInmv | 1509 @ 5 u16be | BT638_CONFIRMED_BY_LIBBLE_AND_LIVE |
| batteryPercent | BatteryPartListener.onCapacityInPercentage / ISOCListener.onISOCInPercentage | 1509 @ 4 u8 | BT638_CONFIRMED_BY_LIBBLE_AND_LIVE |
| batteryCapacityMwh | BatteryPartListener.onCapacityInmwh | gezielt suchen | TARGETED_MAPPING_REQUIRED |
| chargingRemainSeconds | BatteryPartListener.onChargingRemainSecond | gezielt suchen | TARGETED_MAPPING_REQUIRED |
| charging | BatteryPartListener.onIsCharging | gezielt suchen | TARGETED_MAPPING_REQUIRED |
| stateOfHealthPercent | BatteryPartListener.onStateOfHealthInPercentage | gezielt suchen | TARGETED_MAPPING_REQUIRED |
| stateOfHealthMwh | BatteryPartListener.onStateOfHealthInmwh | gezielt suchen | TARGETED_MAPPING_REQUIRED |
| batteryCurrentMa | BatteryPartListener.onActCurrentInma | 1509 @ 0 s16be | BT638_CONFIRMED_BY_LIBBLE_AND_LIVE |
| lightOn | LightPartListener.onLightState | 1508 @ 0 u8 | MAPPED_VERIFY_WITH_MORE_BT638_DATA |

## Noch gezielt zuzuordnen

- **remainingDistanceKm** — ControllerPartListener.onRemainDistanceInKm (km)
- **batteryCapacityMwh** — BatteryPartListener.onCapacityInmwh (mWh)
- **chargingRemainSeconds** — BatteryPartListener.onChargingRemainSecond (s)
- **charging** — BatteryPartListener.onIsCharging (boolean)
- **stateOfHealthPercent** — BatteryPartListener.onStateOfHealthInPercentage (%)
- **stateOfHealthMwh** — BatteryPartListener.onStateOfHealthInmwh (mWh)

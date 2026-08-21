# libble READ-Characteristic Inventar – 2026-08-21

Quelle: bereits verifizierte Originaldatei `libble-sdk-native-lib.so`, SHA-256 `6050df512a62edb19279d169c0e416a6674ed036d046f5026c6a6512dafe7760`.

Direkt demangelte native Symbole des `GPSTProtocolHandler` zeigen folgende READ-Pfade:

- `ReadCharacteristicBatteryCell`
- `ReadCharacteristicBatteryChange`
- `ReadCharacteristicBatteryInfo`
- `ReadCharacteristicCommands`
- `ReadCharacteristicDebugLog`
- `ReadCharacteristicError`
- `ReadCharacteristicErrorString`
- `ReadCharacteristicFirmwareDataExtended`
- `ReadCharacteristicFirmwareID`
- `ReadCharacteristicFirmwareTQData`
- `ReadCharacteristicInfo`
- `ReadCharacteristicMotor`
- `ReadCharacteristicMotorInfo`
- `ReadCharacteristicMotorTuning`
- `ReadCharacteristicReceiverFirmwareInfo`
- `ReadCharacteristicSensors`
- `ReadCharacteristicSensorsActiveSRAMId`
- `ReadCharacteristicSensorsError`
- `ReadCharacteristicSerialNumbers`
- `ReadCharacteristicSetSettings`
- `ReadCharacteristicSettings`
- `ReadCharacteristicStats`
- `ReadCharacteristicTotalTrip`
- `ReadCharacteristicTrip`
- `ReadCharacteristicUpdate`
- `ReadCharacteristicWirelessRemote`
- `ReadCharacteristicWirelessRemoteAction`

Im selben Binary sind die GPST-/DA1A-UUID-Familien `DA1A1500...`, `DA1A1600...`, `DA1A1800...`, `DA1A1A00...`, `DA1A1E00...` und `DA1A1F00...` vorhanden. Das ist keine automatische Hyena/Hylink-Zuordnung. Bereits deterministisch bekannte Zuordnungen wie `1514` Fehler, `1516` Seriennummern, `1517` Fehlertext, `1518` Debug sowie `1E03/1E04` Wireless Remote/Action bleiben die sicheren Startpunkte.

## Evidenzgrenze

Das Symbolinventar beweist SDK-Fähigkeit, aber nicht, dass jede Characteristic auf dem BT638 existiert, lesbar ist oder dieselbe Semantik trägt. Neue Zuordnungen werden erst nach echtem GATT-Inventar/READ-Ergebnis des BT638 übernommen. Keine Write-Characteristic wird aus diesem Inventar automatisch verwendet.

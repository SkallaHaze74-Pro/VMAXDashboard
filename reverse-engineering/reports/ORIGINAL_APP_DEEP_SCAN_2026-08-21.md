# Original-App Deep Scan – 2026-08-21

## Provenienz

Erneute READ-ONLY-Auswertung der bereits vom Nutzer bereitgestellten Originaldateien:

- `base.apk` / `VMAX_com.gpstuner.vmax_..._V125345.apk` — SHA-256 `5f9ee266672bf7f24c7b45dd35546a46317498a499daeeff0dc1ef121ccdb8af`
- `libble-sdk-native-lib.so` — SHA-256 `6050df512a62edb19279d169c0e416a6674ed036d046f5026c6a6512dafe7760`
- `libMapViewerDll.so` — SHA-256 `f4c97c1a7260a1b3109f9953d00d5c6f70faaf1a4f433ce1a25491d3d1db4198`
- `libnative-lib.so` — SHA-256 `be2e8af6cbe518e5b77928620ce6a3fba2318d437a878ab2b4e74e06d74d559d`

Die Hashes stimmen mit `SOURCE_INVENTORY_2026-08-05.md` überein. Die Funde unten stammen aus DEX-Strings, Klassendeskriptoren, Ressourcen-Strings und exportierten/demangelten nativen Symbolen. Ein String oder SDK-Symbol belegt Implementierung im ausgelieferten App-/SDK-Paket, aber **nicht automatisch Verfügbarkeit auf dem BT638**.

## 1. Neue Hyena-spezifische Batterie-/Ladefunktionen

Die Klasse `com.gpstuner.mapviewer.androidbase.bluetooth.manager.HyenaSDKManager` enthält konkrete Coroutine-/Listenerpfade für:

- `getExtendedLifeModeState`
- `getExtenderBatteryLifeSettings`
- `getExtenderBatteryPart`
- `getLongTermStorageModeState`
- `getOptimizedChargingModeState`
- `setOptimizedChargingMode`
- `turnOnLongStorageMode`
- `setExtendedLifeMode`
- `onELMPauseStateChanged`

Zusätzlich sind in `io.hylink.hbp.part.battery`/`expose.hbp.battery` echte Typen vorhanden:

- `IELMCharging`, `IELMControl`, `IELMHistory`, `IELMLogged`
- `ELMDateTime`
- `OptimizedChargingStatus`
- `ExtenderPercentRange`, `PercentRange`
- `IExtendLifeBattery`
- `ChargingSetting`

Passende Hyena-Logtexte nennen ausdrücklich:

- Optimized Charging enabled/disabled
- Long Storage Mode
- Extended Life Mode
- Extender Battery Life Settings
- Lade-Start-/Endzeit
- Charge current
- ELM Pause State

**Bewertung:** Das ist deutlich konkreter als der alte generische Hinweis auf „soft-charge/storage“. Die Original-App besitzt einen echten Hyena-Batterie-ELM-Unterbau. Für VMAXDashboard ist zuerst nur eine READ-ONLY-Erkennung sinnvoll: unterstützt/nicht unterstützt, aktueller Zustand, vorhandene Konfiguration. Keine Ladeparameter automatisch schreiben.

## 2. Neue Hyena-spezifische Diagnose-/Komfortpfade

`HyenaSDKManager` enthält außerdem konkrete Pfade für:

- `getPedalResponseValues` und `setPedalResponse`
- `getThrottleFlag`; Logik nennt `isThrottleBike` und `isThrottleEnabled`
- `getBatteryArticle14`
- `getCurrentAssistLevel`
- `getExtendedSettings`
- `getMCUInBootloaderMode`
- `readSerial`
- RTC/Zeit auf dem Bike setzen/lesen
- Fehlercode-Listener

Motor-Tuning war bereits bekannt, ist aber im Manager klar als eigenes Profil-/Readback-System implementiert (`getMotorTuningValues`, `setMotorTuning`, `setMotorTuningDefault`). Die Base enthält außerdem konkrete Motor-Tuning-UI-/Analytics-Begriffe für Assist, Max Power, Max Assist Speed und Pedal Response sowie `MAX_PEDAL_RESPONSE_HYENA`.

**Bewertung:** Für den BT638 sind `PedalResponse`, Throttle-Capability, BatteryArticle14, ExtendedSettings und MCU-Bootloaderstatus neue interessante READ-ONLY-Ziele. Vorhandensein in Hyena-Code ist noch kein BT638-Nachweis. Motor-Tuning-UI belegt eine Produktfunktion im App-Baukasten, aber keine Freigabe für automatische Änderungen.

## 3. Native libble-Funktionen mit hohem Read-only-Wert

Direkte demangelte Symbole in `libble-sdk-native-lib.so` belegen:

### Batterie

- `ReadCharacteristicBatteryInfo`
- `ReadCharacteristicBatteryCell`
- `ReadCharacteristicBatteryChange`
- `ReplaceBatteryMaxCapacity`
- Callback-Objekte für `BatteryInfo`, `BatteryCellUpdate`, `BatteryChange`

### Identität / Diagnose

- `GetSerials`
- `ReadCharacteristicSerialNumbers`
- `ReadCharacteristicError`
- `ReadCharacteristicErrorString`
- Firmware-ID, Firmware-Version, Hardware-/Software-Infos

### Zubehör / Status

- `ReadCharacteristicWirelessRemote`
- `ReadCharacteristicWirelessRemoteAction`
- Lock-Status lesen/fordern (`RequireLockStatus`) und SDK-seitig setzen

### OTA

Ein kompletter OTA-Manager ist vorhanden: Firmware-Info, lokale Pakete, Header/Chunks, Pre-/Postprocess, Restart, Recovery und Status.

**Bewertung:** Für unser Dashboard sind Serial-/Firmware-/Error-/BatteryCell-/Remote-/Lock-READs interessant. OTA bleibt zunächst reine Inventarisierung/Kompatibilitätsanalyse; keine Firmware-Änderung aus KI-Ergebnissen.

## 4. Wichtiger Fund: Multi-Vendor-Code darf nicht mit Hyena/BT638 vermischt werden

Die Base-App enthält viele weitere Protokollfamilien. Zwei Beispiele sind ausdrücklich **nicht automatisch Hyena**:

### Brose-Datenbank

`TelemetryDataRecord` / `TelemetrySnapshotRecord` gehören anhand der Klassendeskriptoren zu `com.brose.ebike.sdk...`. Dort existieren Felder wie:

- wheelCircumference
- stateOfHealth
- loadCycles
- fullChargeCapacity
- battery/drive-unit/HMI serial + firmware
- thrustFactors

Diese Felder sind interessant als Vergleich, aber **kein BT638-/Hyena-Beweis**.

### Generische EBox/BMS-Kommandoliste

In der Base liegen Strings wie:

- `BMSFullCapacity`, `BMSRemainCapacity`, `SOH`, `BMSCycleCount`
- `BatteryCell_1` bis `BatteryCell_32`
- `ResWheelDiameter`, `ResWheelPerimeter`, `ResSpeedLimit`
- `CurrentLimit_1..9`, `SpeedLimit_1..9`
- `System AutoOff`, `SetAutoOff`

Diese stammen aus dem Multi-Protokoll-Unterbau der App und dürfen nicht auf 15xx-Hyena-Kanäle übertragen werden, solange kein Hyena-/BT638-spezifischer Pfad oder Live-Beweis existiert.

## 5. IoT/Cloud-Funktionen in der Base

Die App enthält GPSTuner-IoT-Typen wie:

- `IoTDeviceStatus(isTheftReported=...)`
- `IoTLockStatus`
- `IoTBikePosition`
- `IoTSetting`
- `IoTUniqueIdentifiers`
- UI/Modellbegriff `TheftMode`

**Bewertung:** Das belegt Cloud-/IoT-Unterstützung in der Gesamt-App, aber nicht, dass der BT638 selbst diese Daten lokal per BLE liefert. Diese Schicht getrennt von BLE-Dekodierung halten.

## 6. VMAX-UI enthält Cruise-Control- und Lock/Unlock-Pfade

Im VMAX-eigenen `com.gpstuner.rangerapp.fragment.DashboardContentFragment` existieren konkrete Klassenpfade:

- `startCruiseControlTimeOut`
- `startLockUnlockTimeOut`

Dazu kommen Ressourcen-/UI-Schlüssel wie:

- `cruise_control_switch`
- `tv_cruise_control`
- `lockUnlockSwitch`
- `tvLockUnlock`

Im Gesamt-SDK sind außerdem Getter/Setter-Namen für Cruise-Control vorhanden. Ein expliziter Logpfad `HobbywingSDK: SetCruiseControl` zeigt jedoch, dass mindestens ein Teil davon provider-/herstellerabhängig ist.

**Bewertung:** Cruise-Control und Lock/Unlock sind echte VMAX-Dashboard-UI-Funktionen im ausgelieferten App-Code. Das beweist noch nicht, dass der BT638/Hyena-Stack sie unterstützt oder auf welchen Kanal sie gehören. Für unser Dashboard zuerst nur Capability-/Status-Erkennung; keine Cruise-/Lock-Schreibfunktion aus Strings ableiten.

## 7. Bereits bekannte Start-/Fahrmodi werden erneut gestützt

Die Base enthält explizit:

- `KICK_START`
- `ZERO_START`
- UI-Ressourcen `start_mode_kick_start`, `start_mode_zero_start`

Das passt zu dem bereits live beobachteten Startmodus. Kein neuer Schreibpfad wird aus diesem Scan abgeleitet.

## 8. Priorisierte sichere nächste Schritte

1. Hyena ELM/Charging Capability **nur lesen**: Supported, OptimizedChargingStatus, LongStorageMode, ExtendedLifeMode, ExtenderBattery presence/settings.
2. Hyena Battery/Health **nur lesen**: BatteryArticle14, BatteryInfo, BatteryCellUpdate, BatteryChange, MaxCapacity-bezogene Felder.
3. Diagnose **nur lesen**: SerialNumbers, Error, ErrorString, Firmware-/Hardwaredetails, MCU bootloader status.
4. Komfort **nur lesen**: PedalResponse current values, throttle capability flag, wireless remote presence/actions, lock status sowie Cruise-/Lock-Capability ohne Write.
5. Ergebnisse immer gegen BT638-Live-/READ-Daten validieren; keine Multi-Vendor-EBox-/Brose-Felder als Hyena-Semantik übernehmen.

## Evidenz- und Sicherheitsregel

Gemini/GLM dürfen diese Funde nur als zweite Prüfinstanz bewerten. KI-Konsens ist kein Beweis. Aktivierbare Decoder-Zuordnungen benötigen weiterhin unabhängige BT638-Evidenz und deterministische Guards. Der Scan erzeugt keine BLE-Schreibframes, keine Tuningwerte und keine Firmware-Patches.

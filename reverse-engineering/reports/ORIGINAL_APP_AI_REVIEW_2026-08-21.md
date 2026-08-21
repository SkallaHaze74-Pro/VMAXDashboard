# Gemini + GLM – Original-App Deep Review

> Advisory only / READ-ONLY. KI-Aussagen sind keine Ground Truth und aktivieren nichts automatisch.

## Gemini

Status: `ok`
Modell: `gemini-3.7-flash`
Provider: `Gemini`

### Belastbare Evidenz

Die Provenienz der Binärdateien (`base.apk`, `libble-sdk-native-lib.so`, `libMapViewerDll.so`, `libnative-lib.so`) ist über SHA-256 verifiziert. Der Codebestand teilt sich strikt in drei Klassen:

- **Klasse A (Hyena-spezifisch / io.hylink / libble):**
  - Vorhandensein von Typen und Methoden für *Extended Life Mode* (ELM), *Long Storage Mode*, *Optimized Charging* (`IELMCharging`, `OptimizedChargingStatus`, `ExtenderPercentRange` in `io.hylink.hbp.part.battery`).
  - Diagnose-/Konfigurationspfade in `HyenaSDKManager`: `getPedalResponseValues`, `getThrottleFlag`, `getBatteryArticle14`, `getExtendedSettings`, `getMCUInBootloaderMode`, `readSerial`.
  - Demangelte native Symbole in `libble-sdk-native-lib.so`: `ReadCharacteristicBatteryInfo`, `ReadCharacteristicBatteryCell`, `ReadCharacteristicBatteryChange`, `ReadCharacteristicSerialNumbers`, `ReadCharacteristicErrorString`, `ReadCharacteristicWirelessRemote`, `RequireLockStatus`.
- **Klasse B (Generischer Multi-Vendor-Code / App-Plattform):**
  - Generische EBox-/BMS-Kommandos (`BMSFullCapacity`, `SOH`, `BatteryCell_1..32`, `ResWheelDiameter`, `SetAutoOff`).
  - GPSTuner-IoT/Cloud-Datenstrukturen (`IoTDeviceStatus`, `IoTLockStatus`, `TheftMode`).
  - VMAX-UI-Pfade für Cruise-Control und Lock/Unlock im `DashboardContentFragment` sowie Startmodi (`KICK_START`, `ZERO_START`).
- **Klasse C (Fremdhersteller-SDKs):**
  - Brose-spezifisches SDK (`com.brose.ebike.sdk...`) mit `TelemetryDataRecord`, `TelemetrySnapshotRecord`, `thrustFactors`, `wheelCircumference`.
  - Hobbywing-spezifische SDK-Reste (z. B. `HobbywingSDK: SetCruiseControl`).

*Grenze der Evidenz:* Das Vorhandensein von Symbolen, Typen oder UI-Strings belegt ausschließlich den Funktionsumfang des App-Baukastens, **nicht** die Verfügbarkeit oder Unterstützung auf der konkreten BT638-Hardware.

---

### Konflikte / mögliche Bugs

1. **Fehlzuordnungsgefahr durch Multi-Vendor-Code:** Die EBox-/BMS-Befehle (Klasse B) und Brose-Felder (Klasse C) dürfen nicht fälschlich als Hyena-GATT-Kanäle oder BT638-Offsets interpretiert werden.
2. **Cruise Control Provider-Konflikt:** UI-Elemente existieren in der VMAX-App, die Logik verweist jedoch teilweise auf Fremd-Stacks (z. B. Hobbywing). Es gibt bisher keinen Hyena-/BT638-Beweis für einen lokalen Cruise-Control-GATT-Kanal.
3. **Status von 1505 Offset 10 (`remainingDistanceKm`):** Im SDK-Layout als Restreichweite definiert, live auf dem BT638 jedoch durchgängig `0xFFFF` (nicht verfügbar/nicht implementiert). Layout-Vorhandensein entspricht nicht Live-Verfügbarkeit.
4. **Leistungsaufteilung (1505 Offset 0 vs. 2):** SDK unterscheidet Motor- und Tretleistung (`motorPower` / `treadlePower`), die konkrete Zuordnung auf die BT638-Offsets bleibt ohne dynamische Messreihe ungelöst.

---

### Hypothesen (nicht bestätigt)

1. Der BT638 unterstützt Hyena-ELM-/Ladestatus-Funktionen über proprietäre GATT-READ-Kanäle.
2. Einzelne Zellspannungen (`BatteryCellUpdate`) oder BMS-Gesundheitsdaten (`BatteryArticle14`) können vom BT638 per GATT gelesen werden.
3. Der Lock/Unlock-Status in der UI interagiert mit dem nativen `RequireLockStatus`-Pfad des Hyena-Stacks und nicht rein über Cloud/IoT.
4. `PedalResponse`-Werte und Throttle-Flags sind auf dem BT638 als READ-Parameter abfragbar.

---

### Nächste sichere READ-ONLY-Tests (max. 5)

1. **GATT-Vollinventar (READ-only):** Systematischer Export aller lesbaren GATT-Characteristics (Standard + proprietär) mit UUID, Status und RAW-Bytes im Stillstand.
2. **Hyena-Diagnose-Abfrage:** Leseversuch auf bekannte Hyena-GATT-Deskriptoren/Handles für Seriennummern, Firmware-Version, Bootloader-Status und Fehler-Strings.
3. **Batterie-Detailprüfung:** READ-Abfrage potenzieller Batterie-/BMS-Kanäle zur Prüfung auf Einzelzellspannungen, SOH oder Article-14-Metadaten im Vergleich zu Frame 1509.
4. **Ladezustands-Differenzmessung:** Passiver Lese-Snapshot (READ-only) vor dem Anstecken, während des Ladens und nach dem Abstecken zur Identifikation von ELM-/Charging-Statusflags.
5. **Passives UI-Sniffing (Lock/Cruise):** Beobachtung der BLE-Benachrichtigungen beim reinen Umschalten von Lock/Cruise in der Original-App (ohne eigene Schreibversuche).

---

### Automatische Änderungen: KEINE

Freigabe: keine automatische Änderung.

## GLM / Z.ai

Status: `ok`
Modell: `glm-4.7-flash`
Provider: `Z.ai`
Fallback: `true`

- **Belastbare Evidenz**
    - **Hyena-SDK Implementierung:** Der Deep-Scan bestätigt die Existenz der Klasse `com.gpstuner.mapviewer.androidbase.bluetooth.manager.HyenaSDKManager` und nativer Typen wie `IELMControl`, `IELMHistory` und `OptimizedChargingStatus`. Dies belegt, dass die App-Logik für Hyena-spezifische Lade- und Lebensdauer-Management-Funktionen (ELM) vollständig implementiert ist, unabhängig davon, ob der BT638 diese bereits ausliefert.
    - **Native Read-Only-Funktionen:** In `libble-sdk-native-lib.so` sind Symbole wie `ReadCharacteristicBatteryCell`, `ReadCharacteristicBatteryChange`, `GetSerials` und `ReadCharacteristicError` demangelten vorhanden. Dies ist der technische Nachweis dafür, dass der SDK-Baustein die Fähigkeit zum Lesen dieser spezifischen Datenpunkte besitzt, falls der Controller sie bereitstellt.
    - **Saubere Trennung Brose:** Der Code enthält explizite `com.brose.ebike.sdk`-Referenzen (z. B. `TelemetryDataRecord`). Dies ist wichtig, um zu verhindern, dass generische BMS-Daten (wie `stateOfHealth` oder `loadCycles`) versehentlich Hyena-spezifischen Daten zugeordnet werden.
    - **VMAX-Dashboard-Funktionalität:** Die UI-Referenzen (`startCruiseControlTimeOut`, `startLockUnlockTimeOut`) belegen, dass der Dashboard-Stack für Cruise- und Lock-Funktionen vorgesehen ist, auch wenn die BLE-Unterstützung durch den Controller noch zu prüfen ist.

- **Konflikte / mögliche Bugs**
    - **Discrepanz bei verbleibender Reichweite:** Die JSON-Semantik zeigt, dass `remainingDistanceKm` (Command 260, Channel 1505, Offset 10) auf dem BT638 derzeit immer `0xFFFF` zurückliefert. Der Deep-Scan bestätigt jedoch, dass die SDK-Klasse `ControllerPartListener.onRemainDistanceInKm` existiert. Dies deutet darauf hin, dass entweder die Firmware den Wert nicht unterstützt oder der Kanal/Offset falsch ist.
    - **Ambiguität Motor-/Tretleistung:** Die JSON-Semantik listet `motorPower` und `treadlePower` mit zwei Kandidaten für Channel 1505 (Offset 0 und 2). Der Deep-Scan bestätigt die Implementierung dieser Callbacks im SDK, aber die exakte Zuordnung zum Raw-Value bleibt unklar und verursacht eine Datenqualitätsproblematik bei der Darstellung.
    - **Mischverwirrung Risikopotenzial:** Da sowohl generische EBox/BMS-Strings als auch Hyena-spezifische Strings im Code vorhanden sind, besteht das Risiko, dass ein standardmäßiger Lesevorgang eines neuen Services (z. B. 1509 oder unbekannt) versehentlich mit generischen Feldern (wie `BatteryCell_1`) verwechselt wird, anstatt Hyena-spezifische Felder (wie `BatteryArticle14`) zu erkennen.

- **Hypothesen (nicht bestätigt)**
    - **Hyena ELM Verfügbarkeit:** Da die Methoden `getOptimizedChargingModeState` und `getExtendedLifeModeState` existieren, wird vermutet, dass der BT638 einen separaten Service oder Kanal für diese Ladezustände offenlegt, der aktuell nicht in den Standard-Datenstrom (1505/1509) integriert ist.
    - **Zellspannungserkennung:** Die Existenz von `ReadCharacteristicBatteryCell` impliziert die Hypothese, dass der Controller Zellspannungen bereitstellt, diese aber nur bei explizitem "Get" oder in einem separaten Zyklen-Update verfügbar sind und nicht im ständigen 1509-Stream.
    - **OTA-Fähigkeit:** Das Vorhandensein eines kompletten OTA-Managers im SDK deutet darauf hin, dass der BT638 eine OTA-Funktion unterstützt, deren Protokollheader und Chunk-Format jedoch noch nicht im Deep-Scan spezifiziert wurden.

- **Nächste sichere READ-ONLY-Tests (max. 5)**
    1.  **Hyena ELM Status abfragen:** Überprüfung der Methoden `getOptimizedChargingStatus` und `getExtendedLifeModeState` (falls SDK-Wrapper verfügbar) oder Suche nach neuen Services/Chars, die diese Werte zurückgeben, um zu bestätigen, ob der Controller diese Daten liefert.
    2.  **Battery Article 14 Lesen:** Spezifischer Lesevorgang für das Feld `BatteryArticle14`, um zu prüfen, ob es einen eindeutigen Hersteller-Identifikator liefert, der Hyena vom Rest unterscheidet.
    3.  **Zellspannungen extrahieren:** Versuch, `ReadCharacteristicBatteryCell` zu triggeren, um zu sehen, ob der Controller Zellspannungen in einem neuen Update liefert oder ob dies nur ein Pseudonym für die Standard-Zustandsdaten ist.
    4.  **Bootloader-Status prüfen:** Lesen von `getMCUInBootloaderMode`, um zu bestimmen, ob der Bootloader-Status über GATT auslesbar ist (wichtig für Diagnose).
    5.  **Throttle-Feld validieren:** Abfrage von `getThrottleFlag` oder Lesen eines Flags in den Standard-Streams, um die Unterscheidung zwischen Pedal-Assist (Kick/Zero) und Throttle-Bikes zu validieren.

- **Automatische Änderungen: KEINE**

Freigabe: keine automatische Änderung.

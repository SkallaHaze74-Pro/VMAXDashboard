# Gemini + GLM – Original-App Deep Review

> Advisory only / READ-ONLY. KI-Aussagen sind keine Ground Truth und aktivieren nichts automatisch.
> VMAX/V-Core, BT638/GPST-DA1A, Hyena/Hylink und andere Vendor-SDKs werden getrennt bewertet.
> Fremde Vendor-Authentifizierung oder API-Key-Symbole gelten nicht als BT638-Secret-Key-Beweis.
> Deterministischer Lade-Guard: Kein Live-BLE während des Ladens voraussetzen; nur Zustand davor, mögliches kurzes POWER-Fenster und Zustand nach Abziehen/Reconnect vergleichen. Ein Reconnect allein beweist keinen Ladezustand.

## Gemini

Status: `ok`
Modell: `gemini-3.5-flash-lite`
Provider: `Gemini`
Fallback: `true`
Frische: `aktuell`

- Belastbare Evidenz:
  - VMAX VX2 Gear verwendet laut Herstellerangabe den V-Core Controller / V-Torque Gear.
  - Der Live-Pfad des BT638 nutzt die proprietäre GATT-UUID-Familie DA1A (u. a. `1505`, `1506`, `1508`, `1509`) und liefert Telemetriedaten wie Geschwindigkeit, Odometer und Akkuspannung ohne App-seitigen Auth-Schritt.
  - Die originale `base.apk` ist ein Multi-Vendor-Baukasten mit Hyena-, Brose- und Hobbywing-SDKs; Hyena-spezifische Features sind im App-Code vorhanden, aber nicht als BT638-Runtime bewiesen.
  - Native Symbole in `libble-sdk-native-lib.so` (z. B. `GPSTProtocolHandler`) belegen generische Parser- und Auth-Strukturen, wobei spezifische Key-Routinen explizit Sachs-zugeordnet sind.

- Konflikte / mögliche Bugs:
  - Unberechtigte Gleichsetzung von Hyena/Hylink-SDK-Code mit dem tatsächlichen BT638/DA1A-Hardwareverhalten.
  - Mögliche Verwechslung von generischen SDK-Authentication-Methoden (z. B. `SetApiKey`, Sachs-Key-Routinen) mit einem existierenden BT638-Application-Layer-Handshake.
  - Fehlende Live-Evidenz für fortgeschrittene Hyena-Batteriedaten (SOH, Charge Cycles, ELM) auf dem BT638.
  - Unbestätigter Controller-Ausleseschutz (Read Protection), der fälschlicherweise als BLE-Handshake interpretiert werden könnte.

- Hypothesen (nicht bestätigt):
  - Der BT638-Controller besitzt einen geheimen BLE-Application-Layer-Handshake zur Freischaltung erweiterter DA1A-Daten.
  - Die in der APK gefundenen Hyena-spezifischen Batterie- und Tuning-Funktionen lassen sich direkt auf den V-Core-Controller übertragen.
  - Feld `1505` Offset 0 und 2 entsprechen exakt den Callback-Werten für Motor- und Treadle-Power in mW.

- Nächste sichere READ-ONLY-Tests (max. 5):
  - Vollständige Inventarisierung aller verfügbaren `PROPERTY_READ`-Characteristics des BT638 inklusive Service-UUID, Status und Rohbytes.
  - Erfassung des Vor- und Nach-Ladezustands (Zustand vor dem Einstecken des Ladegeräts vs. erster Zustand nach Reconnect) ohne Live-Polling während des Ladens.
  - Auswerten des GATT-Antwortstatus für optionale Diagnose-Characteristics (SerialNumbers, Error, ErrorString) im Stillstand.
  - Prüfen, ob `1505` Offset 10 (`remainingDistanceKm`) jemals gültige Werte ungleich `0xFFFF` liefert.
  - Protokollieren von Verbindungsparametern und MTU-Größen nach standardmäßigem GATT-Connect ohne eigene Schreibbefehle.

- Automatische Änderungen: KEINE

Freigabe: keine automatische Änderung.

## GLM / Z.ai

Status: `ok`
Modell: `glm-4.7-flash`
Provider: `Z.ai`
Fallback: `true`
Frische: `aktuell`

- **Belastbare Evidenz**
    - Hardware ist ein **V-Core Gear / V-Torque** (VMAX-Doku), nicht zwingend Hyena. Live-BLE nutzt die DA1A/15xx-Familie (Speed, Odo, Batt, Current sind bestätigt).
    - Die `base.apk` enthält Hyena, Brose, Hobbywing SDK-Code, ist aber ein **Multi-Vendor-Baukasten**. SDK-Code beweist keine Hardware-Funktion auf dem aktuellen Gerät.
    - Kein BT638-spezifischer geheimer Handshake im analysierten Code (Sachs-Routinen sind getrennt dokumentiert). Live-Verbindung funktioniert ohne diese.
    - Native `libble-sdk-native-lib.so` belegt Implementierungsfähigkeiten (OTA, Battery, Serial), aber keine tatsächliche Verfügbarkeit auf dem verbundenen Controller.

- **Konflikte / mögliche Bugs**
    - **Multi-Vendor-Diskrepanz:** Der App-Baukasten ist Multi-Vendor (Hyena/Brose), das Live-Gerät nutzt aber nur einen festen DA1A-Pfad. Es ist unklar, ob der Hyena-Code "Dead Code" ist oder für andere VMAX-Modelle gedacht.
    - **Falsche Mapping:** `1505` offset 10 (Remaining Distance) ist im Original-App-Modell belegt, alle Live-Messungen zeigen jedoch 0xFFFF (unbekannt). Die semantische Zuordnung ist fehlerhaft oder das Feature wird nicht unterstützt.
    - **SDK vs. Hardware:** `BatteryPartImpl.getChargeCycles`, `SOH` und `Production Date` existieren als Java-Methoden im SDK, aber es gibt keine Evidenz, dass der BT638 diese Daten bereitstellt. Es besteht die Gefahr der Fehlinterpretation von Code-Existenz als Datenverfügbarkeit.

- **Hypothesen (nicht bestätigt)**
    - Der "Read Protection"-Hinweis des Nutzers bezieht sich möglicherweise auf eine physische Controller-Sperre (Hardware), nicht auf einen verschlüsselten BLE-Application-Layer-Handshake.
    - Die `HyenaSDKManager`-Methoden (z.B. `getExtenderBatteryLifeSettings`, `setOptimizedChargingMode`) könnten aktiv sein, wenn die Firmware Hyena unterstützt, sind aber aktuell nicht belegt.
    - OTA-Funktionen im SDK könnten für interne VMAX-Updates reserviert sein und über den Standard-BT638-GATT-Zugriff nicht erreichbar sein.
    - Die `1505` offset 10 (Remaining Distance) könnte ein Zustandsflag sein (z.B. "Navigation gestartet"), das nur selten gültige Werte liefert.

- **Nächste sichere READ-ONLY-Tests (max. 5)**
    - Vollständige GATT-Inventarisierung aller `PROPERTY_READ`-Charakteristika auf dem V-Core, gezielt nach nicht-DA1A UUIDs (Suche nach Service-Unterscheidung).
    - Wiederholte Messung von `1505` offset 10 unter Last, um zu prüfen, ob 0xFFFF ein konstanter Fehler oder ein abhängiger Zustand ist.
    - Versuch, `ReadCharacteristicSerialNumbers` und `ReadCharacteristicError` via GATT Read auszulesen (Native-SDK-Pfad simulieren).
    - Überprüfung, ob sich die Service-UUIDs bei anderen VMAX-Modellen unterscheiden, um die Theorie des Multi-Vendor-App-Baukastens zu validieren.
    - Bestätigung der DA1A/15xx-Datenpunkte bei niedrigen Temperaturen, um sicherzustellen, dass keine Sensor-Glitches die "unbekannt"-Werte verursachen.

- **Automatische Änderungen: KEINE**

Freigabe: keine automatische Änderung.

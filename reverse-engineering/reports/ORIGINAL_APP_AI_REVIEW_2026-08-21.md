# Gemini + GLM – Original-App Deep Review

> Advisory only / READ-ONLY. KI-Aussagen sind keine Ground Truth und aktivieren nichts automatisch.
> VMAX/V-Core, BT638/GPST-DA1A, Hyena/Hylink und andere Vendor-SDKs werden getrennt bewertet.
> Fremde Vendor-Authentifizierung oder API-Key-Symbole gelten nicht als BT638-Secret-Key-Beweis.
> Deterministischer Lade-Guard: Die unten noch enthaltenen Vorschläge für Live-Monitoring „während des Ladens“ sind ungültig. Der BT638 schaltet dabei nach Nutzerbeobachtung ab; zulässig sind Zustand davor, ein mögliches kurzes POWER-Fenster und der Zustand nach Abziehen/Reconnect. Ein Reconnect allein beweist keinen Ladezustand.

## Gemini

Status: `ok`
Modell: `gemini-3.5-flash-lite`
Provider: `Gemini`
Fallback: `true`
Frische: `aktuell`

- Belastbare Evidenz:
  * Der New VMAX VX2 Gear ist hardwareseitig als V-Core Gear / V-Torque ausgewiesen.
  * Live-Telemetrie auf DA1A/15xx (z. B. Geschwindigkeit, Odometer, Spannung, Strom, Akkustand) ist auf dem BT638 ohne spezifischen Auth-Key bestätigt.
  * Die Original-App enthält einen Multi-Vendor-Baukasten mit Hyena-, Brose- und Hobbywing-SDKs; Hyena-spezifische Code-Pfade sind darin implementiert, aber noch nicht als BT638-Hardwarefunktion bewiesen.
  * Authentifizierungsroutinen im generischen SDK (wie `authSachsBike`) sind nachweislich Sachs-spezifisch und gelten nicht als BT638-Handshake.

- Konflikte / mögliche Bugs:
  * Die frühere Annahme, das gesamte Gerät sei ein Hyena-Antriebssystem, steht im Widerspruch zur VMAX-Hardwarebezeichnung (V-Core) und der Multi-Vendor-Natur der APK.
  * Das ungesicherte Übertragen von SDK-Funktionen (z. B. ELM, SOH, Charge Cycles) auf den BT638 ohne Live-GATT-Nachweis führt zu Fehlinterpretationen.
  * Der mit 0xFFFF belegte Reststrecken-Wert (1505 offset 10) zeigt eine Diskrepanz zwischen nativem Parser-Layout und tatsächlich beobachteten Live-Daten.

- Hypothesen (nicht bestätigt):
  * Ob erweiterte Hyena-Batteriediagnosedaten (SOH, Ladezyklen, Produktionsdatum) vom BT638 über die DA1A-Familie bereitgestellt werden, ist offen.
  * Ob zusätzliche native READ-Routinen (z. B. `ReadCharacteristicBatteryCell`, `ReadCharacteristicErrorString`) auf dem BT638 antworten, ist unbestätigt.
  * Ob ein echter, gerätespezifischer Handshake für bestimmte bislang ungelöste Characteristics existiert, ist mangels Live-Evidenz spekulativ.

- Nächste sichere READ-ONLY-Tests (max. 5):
  1. Vollständige Inventarisierung aller verfügbaren `PROPERTY_READ`-Characteristics des BT638 mitsamt GATT-Status und Rohdaten.
  2. Gezieltes Auslesen bekannter Diagnose-Handles (Seriennummern, Firmware- und Hardware-IDs) über sichere READ-Requests im Stillstand.
  3. Protokollierung von Status und Payload bei Abfragen von Batterie-Zellspannungen und Fehlerstatus (READ-only).
  4. Überprüfung der verbleibenden Offen-Felder (z. B. Motorleistung-Offsets) durch reproduzierbare Messungen im Stand.
  5. Protokollierung von GATT-Antworten vor und während des Ladevorgangs zur Verifizierung von Ladezustands-Flags.

- Automatische Änderungen: KEINE

Freigabe: keine automatische Änderung.

## GLM / Z.ai

Status: `ok`
Modell: `glm-4.7-flash`
Provider: `Z.ai`
Fallback: `true`
Frische: `aktuell`

- **Belastbare Evidenz**
    - Hardware-Identität: VMAX-VX2-Gerät ist offiziell als **V-Core Gear / V-Torque** klassifiziert; Hyena ist ein SDK-Modul in der Multi-Vendor-APK, nicht zwangsläufig die Hardware-Infrastruktur.
    - Live-Daten: DA1A/15xx-Characteristics (1505, 1506, 1509) liefern Geschwindigkeit, Odometer, Akku % und Strom ohne spezifischen „Secret Key“ oder Hyena-Auth-Handshake.
    - SDK-Fähigkeiten: `libble-sdk-native-lib.so` belegt explizite READ-Funktionen (`ReadCharacteristicBatteryInfo`, `ReadCharacteristicSerialNumbers`), die Implementierung sind vorhanden, auch wenn nicht alle auf dem BT638 aktiv sind.
    - Vendor-Trennung: Die APK enthält Brose-, Hobbywing- und Sachs-Code. Das Vorhandensein eines SDKs (Hyena) beweist nicht, dass es auf dem konkreten VX2-Gerät genutzt wird.

- **Konflikte / mögliche Bugs**
    - **Power-Wert-Ambiguität:** Die JSON-Mapping für `motorPower` und `treadlePower` nutzt dieselben Kandidaten (Offsets 0 und 2 von 1505). Da beide Felder als `role_assignment_open` markiert sind, besteht hohe Wahrscheinlichkeit für falsche Zuordnung oder falsche Bezeichnung, falls die Werte nicht separat verfügbar sind.
    - **Verbleibende Reichweite (0xFFFF):** `remainingDistanceKm` ist als `original_app_known_mapping_open` markiert, aber Live-Daten zeigen nur `0xFFFF` (unbekannt). Dies deutet auf eine fehlende Implementierung im BT638 oder eine falsche Decoder-Logik hin.
    - **Hyena-Feature-Existenz:** Die Dokumentation listet umfangreiche Hyena-Features (ELM, Extender Battery, Optimized Charging) als „Kandidaten“. Da diese nicht auf dem V-Core bestätigt sind, besteht ein Risiko, dass bestehende Decoder-Konfigurationen versucht, diese Werte zu lesen und Fehler generieren.

- **Hypothesen (nicht bestätigt)**
    - **Motor- vs. Pedal-Power:** Die Werte bei Offset 0 und 2 von 1505 könnten nicht Motorleistung und Pedalleistung sein, sondern z. B. „Gesamtleistung“ und „Regenerationsleistung“ oder unterschiedliche Phasen des gleichen Signals.
    - **Extender-Support:** Der VX2 Gear (V-Core) könnte die Hyena-Logik für den „Extender Battery“ nicht unterstützen, obwohl der SDK-Code dies vorbereitet.
    - **Sachs-Auth-Veraltung:** Die `AuthSachsBike`-Funktionen könnten nur für Sachs-Motor-Varianten innerhalb des VMAX-Ökosystems relevant sein und keinem Sicherheits-Schutz für den BT638-VX2 Gear darstellen.

- **Nächste sichere READ-ONLY-Tests (max. 5)**
    - **Battery Cell & Serial:** Lesen von `ReadCharacteristicBatteryCell` und `ReadCharacteristicSerialNumbers` über den DA1A-Pfad, um zu prüfen, ob Hardware-Support für Zellvoltagespannungen und Seriennummern existiert.
    - **Motor Info/Temp:** Lesen von Motor-Informationen und Temperaturwerten, um die Ambiguität bei `motorPower` und `treadlePower` aufzulösen und die korrekten Felder zu identifizieren.
    - **Charging State Mapping:** Live-Monitoring von `1509` während des Ladens, um die offenen Felder `chargingRemainSeconds` und `stateOfHealth` exakt auf Bytes zu mappen.
    - **ELM Features:** Lesen von `OptimizedChargingStatus` und `ExtendedLifeModeState` (falls entsprechende Characteristics existieren), um Hyena-Unterstützung auf dem V-Core zu validieren.
    - **OTA/Bootloader:** Lesen von Firmware-/Bootloader-Status-Flags, um die `MCUInBootloaderMode`-Evidenz zu bestätigen, ohne Firmware-Änderungen vorzunehmen.

Freigabe: keine automatische Änderung.

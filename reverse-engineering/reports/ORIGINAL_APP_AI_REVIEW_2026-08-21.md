# Gemini + GLM – Original-App Deep Review

> Advisory only / READ-ONLY. KI-Aussagen sind keine Ground Truth und aktivieren nichts automatisch.

## Gemini

Status: `error`
Modell: `gemini-3.6-flash`
Provider: `Gemini`
Fallback: `true`

Fehler: Unvollständige Reviewer-Antwort verworfen: Abschlussmarker fehlt. Letzter Ausschnitt: echende GATT-Read-Handles auf der Hardware existieren.
5. **Prüfung auf Einzelzell-Characteristics:**
   Lesendes Anfragen der in `libble-sdk-native-lib.so` genannten `ReadCharacteristicBatteryCell`-Pfade zur Feststellung, ob der BT638 Zell

## GLM / Z.ai

Status: `ok`
Modell: `glm-4.7-flash`
Provider: `Z.ai`
Fallback: `true`

- Belastbare Evidenz
- Konflikte / mögliche Bugs
- Hypothesen (nicht bestätigt)
- Nächste sichere READ-ONLY-Tests (max. 5)
- Automatische Änderungen: KEINE

Belastbare Evidenz

- Die Klasse `HyenaSDKManager` und die Pakete `io.hylink.hbp` sind in der Base-App eindeutig belegt, was die Implementierung der Hyena-Batterie-ELM-Funktionen (OptimizedChargingMode, LongStorageMode, ExtendedLifeMode) bestätigt.
- Native Symbole in `libble-sdk-native-lib.so` belegen die Implementierung von `ReadCharacteristicBatteryCell`, `ReadCharacteristicError` und `GetSerials`, was eine technische Voraussetzung für die geplanten Diagnosewerte darstellt.
- Die `BatteryPartImpl`-Klasse bietet Getter für `ChargeCycles`, `ProductionDate`, `ArticleInformation` und `CapacityThroughputInAh`, was diese Felder als wahrscheinliche SDK-Ziele identifiziert.
- Der explizite Hinweis auf Brose- und EBox-Typen in der Base-App schützt vor der fehlerhaften Zuordnung generischer Protokolle zu Hyena-spezifischen Daten.

Konflikte / mögliche Bugs

- **Semantische Verwechslungsgefahr:** Der neue Scan ordnet `getPedalResponseValues` dem `HyenaSDKManager` zu, während das alte Semantik-Dokument `treadlePower` dem `ControllerPartListener` zuordnet. Es ist unklar, ob dies derselbe Wert ist oder ob es sich um zwei unterschiedliche Signale handelt (z. B. aktive Pedal-Antwort vs. Tretleistung). Ein falscher Mix könnte zu inkonsistenten Motor-Daten führen.
- **Kanal-Kollision:** Die neuen Akku-Details (Charge Cycles, Production Date) könnten theoretisch im gleichen Kanal (1509) liegen wie die bestehenden Werte, was zu Überlagerungen führen würde, wenn nicht sauber dedupliziert wird.
- **Datenquellen-Verschlachtung:** Die Base-App enthält viele generische BMS/Sachs-Strings. Ohne strikte Filterung könnte der Decoder versehentlich Daten aus diesen generischen Pfaden in die Hyena-Struktur schreiben, wenn die BT638-Daten nicht sauber zugeordnet werden.

Hypothesen (nicht bestätigt)

- Die neuen Akku-Felder (`ChargeCycles`, `ProductionDate`, `CapacityThroughputInAh`) werden über denselben GATT-Dienst/Kanal (vermutlich 1509) übertragen wie die aktuellen Batteriewerte, basierend auf der Struktur des `BatteryPartImpl`.
- Die `OptimizedChargingModeState` und `ExtendedLifeModeState` sind nicht persistente Einstellungen, sondern reine Lese-Statuswerte, die vom BT638 zurückgemeldet werden.
- Der `MCUInBootloaderMode`-Status ist über einen separaten READ-Befehl oder eine andere Kennung im GATT-Service verfügbar und nicht nur über die Motor-Tuning-UI.

Nächste sichere READ-ONLY-Tests (max. 5)

1.  **Read Battery Cycles & Production Date:** Versuche, `ChargeCycles` und `ProductionDate` aus dem GATT-Datenstrom (Kanal 1509 oder alternativer Dienst) zu isolieren und gegen die SDK-Definitionen zu validieren.
2.  **Read OptimizedChargingMode State:** Prüfe, ob der Status für "OptimizedChargingMode" und "ExtendedLifeMode" als separater READ-Characteristic (z. B. 1509 Offset oder neuer Service) verfügbar ist.
3.  **Read Battery Cell Updates:** Teste die Native-Funktion `ReadCharacteristicBatteryCell`, um zu bestätigen, ob Zellenspannungen über einen separaten Kanal oder eine Antwort auf einen Schreibbefehl gesendet werden.
4.  **Read MCU Bootloader Status:** Versuche, den Bootloader-Status über `getMCUInBootloaderMode` abzurufen und validiere, ob dies ein gültiger READ-Characteristic ist.
5.  **Error String Extraction:** Teste `ReadCharacteristicErrorString`, um zu bestätigen, ob Fehlercodes auch als lesbare Strings zurückgegeben werden.

Automatische Änderungen: KEINE

Freigabe: keine automatische Änderung.

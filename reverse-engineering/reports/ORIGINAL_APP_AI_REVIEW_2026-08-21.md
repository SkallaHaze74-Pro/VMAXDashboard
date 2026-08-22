# Gemini + GLM – Original-App Deep Review

> Advisory only / READ-ONLY. KI-Aussagen sind keine Ground Truth und aktivieren nichts automatisch.
> VMAX/V-Core, BT638/GPST-DA1A, Hyena/Hylink und andere Vendor-SDKs werden getrennt bewertet.
> Fremde Vendor-Authentifizierung oder API-Key-Symbole gelten nicht als BT638-Secret-Key-Beweis.
> Deterministischer Lade-Guard: Kein Live-BLE während des Ladens voraussetzen; nur Zustand davor, mögliches kurzes POWER-Fenster und Zustand nach Abziehen/Reconnect vergleichen. Ein Reconnect allein beweist keinen Ladezustand.

## Gemini

Status: `ok`
Modell: `gemini-3.7-flash`
Provider: `Gemini`
Frische: `aktuell`

### Belastbare Evidenz
- **Hardware-Klassifikation:** Das Zielgerät ist herstellerseitig als *VMAX V-Core Gear* (25A Controller / V-Torque) ausgewiesen; die `base.apk` ist ein Multi-Vendor-Baukasten (u. a. Hyena, Brose, Hobbywing, Sachs, GPST).
- **BT638 Live-Telemetrie:** Daten auf der DA1A-/15xx-Familie (`1505`, `1506`, `1508`, `1509` für Speed, Odo, Licht, Fahrstufe, Spannung, Strom, SoC) werden nach Standard-GATT-Connect empfangen, ohne dass ein anwendungsspezifischer BT638-Handshake aktiv ist.
- **Native libble-Symbole:** In `libble-sdk-native-lib.so` existieren READ- und Parser-Definitionen des `GPSTProtocolHandler` (u. a. `ReadCharacteristicBatteryInfo`, `ReadCharacteristicBatteryCell`, `ReadCharacteristicSerialNumbers`, `ReadCharacteristicError`).
- **Auth-Abgrenzung:** Enthaltene Handshake-Routinen (`authSachsBike`, `changeSachsConnectionKey`) sind namentlich Sachs-spezifisch; `GPSTLib::SetApiKey` ist eine generische SDK-Schnittstelle ohne belegten BT638-Runtime-Pfad.

### Konflikte / mögliche Bugs
- **Falsche Vendor-Zuweisung:** SDK-Pfade (`io.hylink.*`, `com.brose.*`, `HobbywingSDK`) wurden voreilig BT638 zugeordnet; eine Laufzeitverknüpfung zwischen Hyena HBP/HAP und dem DA1A-Protokoll ist unbewiesen.
- **Handshake-Fehlschluss:** Das Vorhandensein generischer bzw. Sachs-spezifischer Auth-Symbole wurde fälschlich als notwendiger Controller-Unlock für BT638 interpretiert; ein physischer MCU-Ausleseschutz ist kein BLE-GATT-Handshake.
- **Offene Signalrollen auf 1505:** Die Zuordnung von Motor- vs. Tretleistung (`performancePowerA/B` auf Offset 0 und 2) ist ungelöst; Offset 10 liefert bisher nur `0xFFFF` (nicht verfügbar).
- **Lade-Monitoring im Betrieb nicht möglich:** Der Controller schaltet beim Einstecken des Ladekabels hardwareseitig ab und trennt BLE; ein kontinuierliches Live-GATT-Monitoring während des Ladens ist technisch nicht durchführbar.

### Hypothesen (nicht bestätigt)
- **BT638-Verfügbarkeit nativer READs:** Die in `libble-sdk-native-lib.so` deklarierten READ-Funktionen (Serials, ErrorString, BatteryCell) existieren als echte, lesbare Characteristics auf dem Scooter.
- **Hyena-ELM-/SOH-Relevanz:** Erweiterte Akku-Diagnosewerte (Zyklen, SOH, Produktionsdatum, Ah-Durchsatz) könnten im V-Core-System existieren, sind aber aktuell reine SDK-Kandidaten ohne BT638-GATT-Nachweis.
- **Cruise-/Lock-Protokollpfad:** Im VMAX-UI sichtbare Cruise- und Lock-Pfade könnten über DA1A oder Hobbywing-Logik gesteuert werden (Kanal auf BT638 unbekannt).
- **Lade-Flags:** Die Signale `charging` (517) und `chargingRemainSeconds` (516) könnten in noch unkartierten BT638-Offsets kodiert sein.

### Nächste sichere READ-ONLY-Tests (max. 5)
1. **Sequenzieller GATT-Discovery- und READ-Scan:** Alle tatsächlich vorhandenen Characteristics mit `PROPERTY_READ` abfragen; Service-UUID, Characteristic-UUID, GATT-Statuscode und Rohbytes unverändert sichern.
2. **Lade-Differenzmessung (Offline/Reconnect):** Telemetrie-Snapshot direkt vor dem Anstecken erfassen; optional kurzen Read-/Notify-Dump nach manuellem Einschaltversuch prüfen; ersten Snapshot nach dem Abziehen/Reconnect vergleichen.
3. **Diagnose- und Fehler-READs prüfen:** Gezieltes Auslesen von Standard- und proprietären Handles für Seriennummern, Firmware-Versionen und Error-Strings nur dann, wenn `PROPERTY_READ` vom Server aktiv gemeldet wird.
4. **Leistungs- und RPM-Feldprüfung:** Rohwerte auf `1505` (Offset 0, 2, 8) im sicheren Stillstand bzw. Freilauf mitschneiden, um Leistungskandidaten und Drehzahl ohne Schreibzugriffe einzugrenzen.

### Automatische Änderungen: KEINE
- Keine Aktivierung oder Modifikation von Decoder-Regeln ohne unabhängige BT638-Messdaten.
- Keine Ausführung von BLE-Schreibbefehlen, Tuning-Profilen, Auth-Frames oder Bypass-Routinen.
- Keine automatische Übertragung von Hyena-, Brose- oder Sachs-Semantiken auf DA1A-Kanäle.
- Deterministischer Konsens und Evidence Guard behalten die alleinige Gültigkeit.

Freigabe: keine automatische Änderung.

## GLM / Z.ai

Status: `ok`
Modell: `glm-4.5-flash`
Provider: `Z.ai`
Fallback: `true`
Frische: `aktuell`

- Belastbare Evidenz
1. VMAX VX2 Gear ist offiziell als V-Core Gear / V-Torque bezeichnet, nicht als Hyena-System.
2. BT638 liefert live Daten auf DA1A/15xx-Familie ohne beobachteten Application-Layer-Handshake.
3. VMAX-App enthält Hyena-, Brose-, Hobbywing- und andere Multi-Vendor-Komponenten.
4. Native libble-Funktionen bestätigen Verfügbarkeit von Batterieinfo, Seriennummern, Fehlercodes, Firmware-Infos, Remote- und Lock-Status.

- Konflikte / mögliche Bugs
1. Widerspruch zwischen Multi-Vendor-SDK-Vorhandensein in App und tatsächlicher BT638-Nutzung.
2. Sachs-spezifische Auth-Routinen werden fälschlicherweise mit BT638 in Verbindung gebracht.
3. Hyena-spezifische Funktionen in App werden möglicherweise fälschlich als BT638-Funktionen interpretiert.
4. Annahme, generisches SDK biete automatisch gleiche Funktionalität wie BT638.

- Hypothesen (nicht bestätigt)
1. Hyena-spezifische Funktionen in App könnten tatsächlich vom BT638 unterstützt werden.
2. Sachs-spezifische Auth-Routinen könnten auf BT638 funktionieren, obwohl nicht als solche dokumentiert.
3. "Read Protection" des Controllers könnte BLE-Datenzugriff einschränken, obwohl nicht durch GATT-Characteristics belegt.
4. Multi-Vendor-Komponenten in App könnten tatsächlich vom BT638 genutzt werden.

- Nächste sichere READ-ONLY-Tests (max. 5)
1. Inventarisierung aller PROPERTY_READ-Characteristics des BT638 mit Antwortstatus/Payload.
2. Prüfung von Diagnosekandidaten: SerialNumbers, Error, ErrorString, Firmware-/Hardwaredetails.
3. Vergleich von Live-BT638-Daten mit Hyena-, Brose- und Hobbywing-SDK-Definitionen.
4. Analyse von GATT-Charakteristiken vor/nach Ladevorgang.
5. Untersuchung noch nicht getesteter DA1A/15xx-Charakteristiken.

- Automatische Änderungen: KEINE

Freigabe: keine automatische Änderung.

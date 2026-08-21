# Gemini + GLM – Original-App Deep Review

> Advisory only / READ-ONLY. KI-Aussagen sind keine Ground Truth und aktivieren nichts automatisch.
> VMAX/V-Core, BT638/GPST-DA1A, Hyena/Hylink und andere Vendor-SDKs werden getrennt bewertet.

## Gemini

Status: `ok`
Modell: `gemini-3.7-flash`
Provider: `Gemini`
Frische: `aktuell`

### Belastbare Evidenz
- **Hardware-Zuordnung:** Laut VMAX-Spezifikation basiert der New VX2 Gear auf einem V-Core Gear Controller (25A) / V-Torque-Antrieb, nicht auf einem nachgewiesenen Hyena-Antriebssystem.
- **BT638 Live-BLE:** Bestätigte Telemetriewerte liegen ausschließlich auf den DA1A/15xx-GATT-Kanälen vor (1505: Speed; 1506: Odo; 1508: Licht/Fahrstufe; 1509: SoC, Spannung, Strom).
- **Multi-Vendor-Baukasten:** Die `base.apk` enthält parallel getrennte SDKs für GPSTuner, Hyena (`io.hylink`), Brose (`com.brose.ebike.sdk`) und Hobbywing; das Vorhandensein im DEX beweist keine Hardware-Anbindung am BT638.
- **Native Parser-Präsenz:** `libble-sdk-native-lib.so` enthält Symbole für `GPSTProtocolHandler` und GATT-READ-Routinen (z. B. Serials, Fehlercodes, Zellspannungen), was reine SDK-Parsingfähigkeiten, jedoch keine gesicherte BT638-Laufzeitfunktion belegt.

### Konflikte / mögliche Bugs
- **Falsche Protokoll-Etikettierung:** Pauschale Benennung von DA1A/15xx als „Hyena“ ist unzulässig, da bisher keine direkte Laufzeitverknüpfung zwischen `io.hylink` und der BT638-Verbindung nachgewiesen ist.
- **Vendor-Cross-Pollination:** Übertragung von Brose-Parametern (`TelemetryDataRecord`) oder generischen EBox-/Hobbywing-BMS-Strings auf 15xx-Kanäle führt zu Phantom-Definitionen.
- **Ungültige Kanalbelegungen:** 150D wurde live als Geschwindigkeit widerlegt; 1505 Offset 10 liefert auf dem BT638 durchgehend `0xFFFF` (nicht verfügbar).
- **Unklare Leistungskanäle:** 1505 Offset 0 vs. Offset 2 (`performancePowerA`/`B`) ist ungelöst und darf nicht ohne Lastmessung als Motor- oder Tretleistung deklariert werden.

### Hypothesen (nicht bestätigt)
- Erweiterte Hyena-Batteriedaten (ELM, Optimized Charging, SOH in mWh, Ladezyklen) sind auf dem BT638 aktiv implementiert und lesbar.
- Tempomat- und Sperrfunktionen (`DashboardContentFragment`) werden beim BT638 über dieselben GATT-Pfade bedient wie im Hobbywing-SDK-Pfad.
- 1505 Offset 8 liefert im Fahrbetrieb verlässliche RPM-Werte des V-Core-Motors.
- `ReadCharacteristicBatteryCell` spiegelt reale Einzelzellspannungen über proprietäre DA1A-Sub-UUIDs wider.

### Nächste sichere READ-ONLY-Tests (max. 5)
1. **GATT-Vollinventur (Stillstand):** Export aller lesbaren Standard- und DA1A-UUIDs mit Rohdaten im Stillstand (Fokus: SerialNumbers, ErrorString, Firmware-Revision).
2. **Kanal-Validierung 1505 (Stillstand/Freilauf):** Prüfung von Offset 8 (RPM) und Offset 10 (Restreichweite) bei drehendem Rad ohne Last.
3. **Leistungs-Gegenprobe:** Vergleich von 1505 (Offset 0 und 2) mit der elektrischen Leistung aus 1509 (Strom × Spannung) unter kontrollierter Last.
4. **Batterie-Read-Snapshot:** Leseabfrage der nativ erwähnten Batterie- und Diagnose-UUIDs vor und während des standardmäßigen Ladevorgangs.

### Automatische Änderungen: KEINE
- Es werden keine Decoder-Regeln aktiviert, modifiziert oder in Konfigurationsdateien übernommen.
- Keine Ausgabe von BLE-Schreibbefehlen, Tuningparametern oder Firmwaremodifikationen.

Freigabe: keine automatische Änderung.

## GLM / Z.ai

Status: `ok`
Modell: `glm-4.7-flash`
Provider: `Z.ai`
Fallback: `true`
Frische: `aktuell`

- **Belastbare Evidenz**
    - **Hardware-Identität:** Offizielle VMAX-Dokumentation identifiziert das New VX2 Gear als V-Core Gear / V-Torque. Hyena ist im App-Baukasten enthalten, aber nicht hardware-verifiziert zugeordnet.
    - **Live-BT638-Bestätigung:** Konkrete GATT-Daten (1505: Speed/RPM, 1506: Odo, 1508: Assist/Light, 1509: Battery %/Volt/Current) sind live auf dem V-Core belegt.
    - **Native Library Capabilities:** `libble-sdk-native-lib.so` implementiert definitiv READ-Funktionen für Battery, Serial, Error, OTA und Remote.
    - **SDK-Code-Präsenz:** Klassen wie `HyenaSDKManager` und `io.hylink.hbp` existieren im APK-Code, beweisen aber nicht die aktive Nutzung auf dem V-Core.

- **Konflikte / mögliche Bugs**
    - **Hyena-Code vs. V-Core Hardware:** Deep Scan findet Hyena-spezifische Batterie-ELM-Klassen, aber die Hardware ist offiziell V-Core. Es ist ein logischer Konflikt: Existiert Hyena-Code für eine Hardware, die ihn laut Hersteller nicht nutzt (oder nutzt er nur einen Teil?).
    - **150D Speed Source Widerspruch:** Deep Scan behauptet "viele weitere GATT READ-Characteristics" seien sichtbar. Jedoch bewies ein Live-Ride (2026-08-13) in den Semantik-Daten, dass 150D *nicht* der zweite Geschwindigkeitsquellencode ist. Deep Scan muss spezifizieren, welche weiteren Characteristics er meint.
    - **1505 Power Offset Ambiguität:** JSON listet `motorPower` und `treadlePower` auf denselben Offsets (0 & 2) von 1505. Keine Evidenz trennt diese oder validiert sie. Das ist ein Mapping-Konflikt.
    - **Charging Status Mapping:** Deep Scan findet Hyena-Charging-Logik, JSON zeigt "open" für Charging-Boolean/Status auf 1509. Keine Live-Verknüpfung zwischen SDK-Code und BT638-Daten (1509) hergestellt.

- **Hypothesen (nicht bestätigt)**
    - **Hyena-Sub-Stack:** V-Core nutzt möglicherweise nur eine Untermenge des Hyena-SDKs (z.B. DA1A/15xx Basis) und nicht das volle HAP/HBP-Ökosystem, oder der Hyena-Code ist eine Legacy-/Reserve-Funktion.
    - **1505 Power-Dualität:** Offset 0 und 2 von 1505 könnten dynamisch wechseln (z.B. Motorleistung bei Tritt, Tretkraft bei Motorlauf) oder beide Power-Werte enthalten, ohne dass ein eindeutiger Single-Source-Parser existiert.
    - **OTA-Unterstützung:** Die Native Library implementiert einen OTA-Manager, aber es ist unbekannt, ob der BT638 Hardware-seitig OTA-Updates akzeptiert oder nur Read-Only unterstützt.

- **Nächste sichere READ-ONLY-Tests**
    - **Hyena Service UUID Scan:** Lesend nach spezifischen DA1A/ELM Service-UUIDs auf dem V-Core (nicht nur GATT Scan) um zu prüfen, ob der Hyena-Stack überhaupt aktiv ist.
    - **1505 Power Differentiation:** Verschiedene Lastzustände (Motor allein, Tritt allein, Lastwechsel) aufzeichnen, um die Zuordnung von Offset 0/2 zu `motorPower` vs. `treadlePower` zu validieren.
    - **Charging State Verification:** 1509 während des Ladens lesen, um zu prüfen, ob `charging`, `remainSeconds` oder `SOH` dort verfügbar sind (statt nur % und Spannung).
    - **Serial/OTA Check:** READ auf `GetSerials` oder Firmware-Dienst, um zu validieren, ob die native Lib-Funktionen tatsächlich auf dem BT638 hardwareseitig verfügbar sind.
    - **150D Re-Analysis:** Statischer Test, ob 150D Daten liefert (nicht Speed), um den Widerspruch zu den 2026-08-13 Daten zu klären.

Freigabe: keine automatische Änderung.

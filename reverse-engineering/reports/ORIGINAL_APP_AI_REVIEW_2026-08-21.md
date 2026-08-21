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
  - VMAX VX2 Gear verwendet offiziell die V-Core-Steuerung/Hardware (V-Torque).
  - BT638 liefert Live-Daten über die proprietäre GATT-DA1A/15xx-Familie (z. B. 1505, 1506, 1508, 1509) ohne expliziten Application-Layer-Handshake von VMAXDashboard.
  - Die originale App (`base.apk`) enthält Hyena/Hylink-, Brose-, Hobbywing- und GPST-SDK-Komponenten als Multi-Vendor-Baukasten.
  - Native Symbole in `libble-sdk-native-lib.so` belegen GPST-Protokollhandler und spezifische Sachs-Auth-Funktionen (`AuthSachsBike`).

- Konflikte / mögliche Bugs:
  - Fälschliche Gleichsetzung von in der APK vorhandenen Hyena-SDKs oder Sachs-Auth-Routinen mit dem tatsächlichen BT638-Laufzeitverhalten.
  - Ungeklärte Zuordnung von Leistungswerten (Motor Power vs. Treadle Power bei 1505).
  - Fehlende Live-Verfügbarkeit des remainingDistanceKm-Wertes (Offset 10 liefert konstant 0xFFFF).
  - Unbelegte Behauptung eines geheimen BT638-App-Layer-Handshakes oder Controller-Read-Protection-Unlock-Zwangs.

- Hypothesen (nicht bestätigt):
  - Ob Hyena-spezifische Batterie-ELM- oder Motor-Tuning-Funktionen jemals auf dem BT638 ausgeführt werden.
  - Ob der Ladezustand über bestimmte Register der DA1A-Familie direkt vor dem Abschalten abgreifbar ist.
  - Ob Offset 0 und 2 bei 1505 exakt den Motor- und Treadle-Power-Metriken entsprechen.

- Nächste sichere READ-ONLY-Tests (max. 5):
  1. Sequenzielles Auslesen aller verfügbaren `PROPERTY_READ`-Characteristics des BT638 mit Protokollierung von UUID, Status und Payload.
  2. Snapshot-Erfassung des letzten BLE-Zustands unmittelbar vor dem Einstecken des Ladegeräts (ohne Live-Polling während des Ladens).
  3. Erster GATT-Read-Dump direkt nach dem Abziehen des Ladegeräts und erfolgreichem Reconnect.
  4. Validierung der Offsets 0 und 2 auf Kanal 1505 gegenüber Last- und Stromwerten im Stillstand.
  5. Protokollierung von GATT-Statuscodes bei absichtlich nicht unterstützten oder leeren Characteristics.

- Automatische Änderungen: KEINE

Freigabe: keine automatische Änderung.

## GLM / Z.ai

Status: `ok`
Modell: `glm-4.7-flash`
Provider: `Z.ai`
Fallback: `true`
Frische: `aktuell`

- **Belastbare Evidenz**
    - Hardware-Identität: VMAX V-Core Gear / V-Torque ist die offizielle Hardwarebezeichnung; Hyena ist nicht nachgewiesen.
    - Protokollbestätigung: Live-Daten bestätigen DA1A/15xx-Familie (z.B. 1505 für Speed/RPM, 1506 für Odometer, 1508 für Assist, 1509 für Akku).
    - App-Architektur: `base.apk` ist ein Multi-Vendor-Baukasten (Hyena/Hylink, Brose, Hobbywing, Sachs). Vorhandensein eines SDKs beweist keine Nutzung durch das spezifische BT638-Gerät.
    - Native SDK-Funktionen: `GPSTProtocolHandler` in `libble-sdk-native-lib.so` implementiert Lesefunktionen für Batterie, Seriennummern, Fehler, Firmware und OTA, deren Laufzeiteinsatz am VX2 Gear jedoch offen ist.

- **Konflikte / mögliche Bugs**
    - Vendor-Mixing: Code für Hyena, Sachs und Brose liegt im selben Paket. Ein Fehler in der Zuordnung könnte entstehen, wenn DA1A-Daten fälschlicherweise Hyena-Semantik zugewiesen werden.
    - Fehlende Handshake-Evidenz: Es gibt keine belegten GATT-Handshake-Requests spezifisch für den VX2 Gear, obwohl der Nutzer einen „Secret Key“ erwartet. Der Verdacht auf einen Application-Layer-Schutz ist nicht durch Messdaten gestützt.
    - Symbole vs. Runtime: `SetApiKey` und `AuthSachsBike` existieren im SDK. Sie sind **nicht** beweisbar für einen VX2-Gear-Handshake, da sie Sachs-spezifisch sind. Ein falscher Einsatz dieser Symbole könnte zu Fehlern führen.
    - Laden vs. Live-Monitoring: Das Verschwinden der BLE-Verbindung beim Einstecken des Ladegeräts macht ein Live-Monitoring unmöglich. Das Testen von Ladezuständen während des Ladevorgangs ist technisch nicht möglich; ein Reconnect allein ist kein Ladebeweis.

- **Hypothesen (nicht bestätigt)**
    - **Hyena-Nutzung:** Es ist hypothetisch, dass der VX2 Gear das Hyena SDK nutzt, obwohl die Live-Evidenz (DA1A) noch keine direkte Verknüpfung zum Hyena-Codepfad zeigt.
    - **Motor-Leistung:** Die Felder `performancePowerA` und `performancePowerB` im Datenstrom `1505` könnten Motor- oder Tretleistungsdaten sein, dies ist jedoch noch eine offene Zuordnung.
    - **Verborgene Felder:** Es könnte zusätzliche `PROPERTY_READ`-Characteristika geben, die vom generischen SDK unterstützt werden, aber noch nicht im Live-Scan entdeckt wurden (z.B. Battery Cells).
    - **Hardware-Lese-Schutz:** Der Controller könnte Hardware-Schutzmechanismen haben, die GATT-READs blockieren, die nicht durch einen einfachen BLE-Handshake umgangen werden, auch wenn der App-SDK entsprechende Funktionen bereitstellt.

- **Nächste sichere READ-ONLY-Tests (max. 5)**
    - **Vollständige Charakteristik-Inventur:** Scan alle GATT-Characteristics und deren `PROPERTY_READ`-Flags, unabhängig von bekannten Listen, um neue Datenquellen zu finden (z.B. Serial, Error, OTA-Info).
    - **Fehler-/Status-Werte prüfen:** Lesen von `ReadCharacteristicError` und `ReadCharacteristicErrorString`, um Debug-Informationen zu erhalten.
    - **Lade-Zustandslogik (Pre/Post):** Vergleiche den Zustand *vor* dem Einstecken des Ladegeräts und den Zustand *nach* dem Abziehen/Reconnect. Kein Live-Dump während des Ladens.
    - **Battery-Cell-Daten:** Versuchen, Battery-Cell-Werte zu lesen, falls diese als standardmäßige oder proprietäre READ-Charakteristika auftauchen.
    - **OTA-Header-Analyse:** Lesen (via App-Schnittstelle oder Cache) der Firmware-Header-Informationen, um die tatsächliche Struktur zu verstehen und nicht zu raten.

- **Automatische Änderungen: KEINE**
Freigabe: keine automatische Änderung.

# VMAX VX2 Gear: V-Core vs. Hyena – Evidenztrennung 2026-08-21

## Ergebnis

Für den **New VMAX VX2 Gear** ist die Geräte-/Hardwareseite derzeit als **VMAX V-Core Gear** zu behandeln, nicht als nachgewiesenes Hyena-Antriebssystem.

Die originale VMAX-App enthält zwar umfangreichen **Hyena/Hylink HBP/HAP-Code**, aber dieselbe APK enthält zusätzlich **Brose-, Hobbywing- und weitere Multi-Vendor-Komponenten**. Das Vorhandensein eines SDKs in `base.apk` beweist deshalb nur, dass der App-Baukasten dieses System unterstützt – nicht, dass der konkrete BT638/VX2 Gear dieses SDK verwendet.

## Unabhängige Produkt-Evidenz

Die offizielle VMAX-Produktbeschreibung des New VX2 Gear bezeichnet die Steuerung als **25A V-Core Controller / V-Core Gear Steuerung** und den Antrieb als V-Torque Gear. Diese Herstellerangabe ist die maßgebliche Hardwarebezeichnung des Modells.

Hyena beschreibt sein öffentliches Digi-Ecosystem dagegen als E-Bike-Systemplattform mit HAP-/HBP-Komponenten, Controllern, HMI und Rider-/Service-Tools.

## Was in der originalen VMAX-App wirklich vorhanden ist

Aus `base.apk` sind direkt belegt:

- `io.hylink.hbp`, `io.hylink.hap` und Hyena-Drive-/Battery-/ELM-Klassen,
- `HyenaSDKManager`-Pfade,
- Brose-SDK-Klassen (`com.brose.ebike.sdk...`),
- Hobbywing-SDK-Pfade einschließlich Cruise-/Lock-Logik,
- weitere generische Multi-Vendor-/EBox-/BMS-Komponenten.

**Schlussfolgerung:** `base.apk` ist ein Multi-Vendor-App-Baukasten. Ein enthaltenes Hyena-Modul darf nicht automatisch dem VX2 Gear zugeordnet werden.

## Separater nativer GPST/DA1A-Pfad

`libble-sdk-native-lib.so` enthält demangelte Symbole des `GPSTProtocolHandler` sowie die proprietären UUID-Familien u. a. `DA1A1500`, `DA1A1600`, `DA1A1800`, `DA1A1A00`, `DA1A1E00`, `DA1A1F00`.

Der Nutzer-BT638 liefert live bereits Daten auf der DA1A/15xx-Familie. Das beweist einen **BT638/GPST-DA1A-Protokollpfad**, aber noch keine Identität mit Hyena HAP/HBP.

Bis eine direkte Code-/Runtime-Verknüpfung zwischen `io.hylink` und genau dem BT638/DA1A-Verbindungsweg nachgewiesen ist, werden diese Quellen getrennt geführt.

## Neue verbindliche Quellenklassen

1. **VMAX-Hardware / VX2 Gear:** V-Core Gear / V-Torque – vom Hersteller für das konkrete Modell ausgewiesen.
2. **BT638 Live-BLE:** tatsächlich beobachtete DA1A-/15xx-/weitere GATT-Daten des Scooters.
3. **GPST native libble:** `GPSTProtocolHandler` und dessen READ-/Parser-Funktionen; SDK-Fähigkeit, nicht automatisch Hardwarefunktion.
4. **Hyena/Hylink HBP/HAP:** in der VMAX-APK gebündelter Vendor-SDK-Pfad; nur als BT638-Funktion werten, wenn eine direkte Geräte-/Runtime-Evidenz vorliegt.
5. **Andere Vendor-SDKs:** Brose, Hobbywing usw.; niemals auf BT638 übertragen, solange keine konkrete Modell-/Runtime-Evidenz vorliegt.

## Konsequenz für Decoder AI

- Bereits **live bestätigte BT638-Felder** (z. B. Geschwindigkeit, Odometer, Akku %, Spannung, Strom) bleiben bestätigt; ihre Bestätigung hängt nicht am Namen Hyena.
- Offene Hyena-spezifische Features wie ELM, Pedal Response, Throttle, Charge Cycles, SOH, Production Date oder Extender-Battery bleiben **nur App-/SDK-Kandidaten**.
- DA1A/15xx wird künftig nicht mehr pauschal als „Hyena“ bezeichnet, sondern als **BT638/GPST-DA1A** bzw. „Original-VMAX-native Parserlayout“, bis ein direkter Link bewiesen ist.
- KI-Konsens darf diese Trennung nicht überschreiben.

## Prüfregel

Ein neues Feature wird erst als VX2-Gear-/BT638-Funktion bezeichnet, wenn mindestens eine der folgenden unabhängigen Evidenzen vorliegt:

- echte BT638-GATT-Existenz plus reproduzierbarer READ-/Notify-Wert,
- eindeutiger gerätespezifischer VMAX-Codepfad, der auf den BT638/VX2 Gear verweist,
- offizielle VMAX-Dokumentation für genau dieses Modell.

Nur das Vorhandensein eines SDKs, Strings oder einer UI-Komponente in `base.apk` reicht nicht.

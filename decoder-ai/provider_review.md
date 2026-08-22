# Gemini + GLM Decoder-Zweitprüfung mit optionaler GPT-Synthese

> Advisory only • STRICT READ-ONLY: Gemini und GLM sind unabhängige Prüfer. GPT ordnet ihre Entwürfe optional nur als Synthese und zählt nicht als dritter Evidenzbeleg.
> Eigene oder fremde KI-Antworten zählen niemals als unabhängige Bestätigung; maßgeblich bleiben Mess-Evidenz, deterministische Konsenslogik und Evidence Guard.

## Gemini 3.7 Flash

Status: `ok`

Modell: `gemini-3.5-flash-lite`

Fallbackmodell aktiv: `gemini-3.5-flash-lite`.

- Belastbare Evidenz
  * Es liegen 280 erfolgreiche GATT-Read-Callbacks aus 14 Diagnose-Bundles vor (BT638 GATT Deep-Read).
  * Fünf Decoder-Regeln (`batteryPercent`, `currentA`, `odometerKm`, `speedKmh`, `voltageV`) basieren laut Profil `b41494223a42174f` auf konsistenten SDK-Layout- und App-Extraktionsvergleichen über bis zu 9 Fahrten.
  * Für das Leistungssignal (`powerW`) existiert ein interner Kreuzvergleich mit einer Korrelation von 0.984 und einer MAE von 4.01 W.

- Konflikte / mögliche Bugs
  * Der `powerW`-Kandidat stützt sich ausschließlich auf denselben RAW-Export und eine interne Berechnung (`Spannung × Strom`), wodurch es sich um einen Selbstreferenz-/Cross-Field-Effekt handelt (bestätigt durch `independentExternalConfirmation: false`).
  * Mehrere historische Messfahrten (z. B. `Messfahrt_2026-08-13_19-17-14`) weisen 0 akzeptierte Exportzeilen und unvollständige READ-/Hybrid-Zähler (`?`) auf, was auf Parser- oder Datenqualitätsprobleme hindeutet.
  * Diskrepanz zwischen reiner Layout-Konsistenz und echter physikalischer Semantik bei ungeprüften Kanälen (z. B. 1507, 150A).

- Hypothesen (nicht bestätigt)
  * Die Kandidaten-Regel `powerW` (1509/9) entspricht der tatsächlichen elektrischen Leistung am Motor (bisher nur mathematisch aus anderen 1509-Feldern abgeleitet).
  * Die Charakteristiken 1507 und 1508 enthalten verlässliche Status-Flags für Licht und Fahrmodi, basieren jedoch aktuell nur auf beobachteten Payload-Änderungen.

- Nächste sichere READ-ONLY-Tests (max. 5)
  1. Statischer READ-Abgleich der Charakteristik 1509 im ausgeschalteten Zustand zur Verifizierung von Offset-Nullpunkten.
  2. Lesender Kontroll-Scan von Charakteristik 1506 (Odometer) im Stillstand zur Prüfung auf statische Carry-forward-Artefakte.
  3. Validierung der Rohdaten-Parser für die fehlerhaften historischen Messfahrten mit 0 akzeptierten Zeilen (rein lesend).
  4. Strukturierte Protokollierung der dynamischen Bytes in Charakteristik 1503 im reinen Standby.
  5. Konsistenzprüfung der Endianness-Annahmen für Geschwindigkeits- und Spannungswerte anhand statischer Test-Payloads.

- Automatische Änderungen: KEINE

Freigabe: keine automatische Änderung.

## GLM

Status: `ok`

Modell: `glm-4.7-flash`

Fallbackmodell aktiv: `glm-4.7-flash`.

- Belastbare Evidenz
    - Layout-Konsens für Geschwindigkeit (1505/6), Strom (1509/0), SOC (1509/4) und Spannung (1509/5) ist gegen SDK-native-Lib (libble) mit 100% Trefferquote bestätigt.
    - Odometer-Layout (1506/0) ist gegen SDK bestätigt (Scale 0.1, u32be).
    - Die Cross-Field-Berechnung für Power (Spannung × Strom) zeigt hohe physikalische Plausibilität (Korrelation 0.98, MAE 4W).

- Konflikte / mögliche Bugs
    - Diskrepanz in den Datenqualitätszahlen: Der Konsensbericht listet "9 Fahrt(en)" für Strom/Spannung mit 1061 Samples. Die libble-Datenqualitätsanalyse zeigt jedoch für Ride 15 und 16 **0 akzeptierte Zeilen**, obwohl der Bericht diese Fahrten in der Gesamtbilanz auflistet. Dies deutet auf eine Filterungslogik, die gültige Samples verliert oder Fahrten inkonsistent zuordnet.
    - Power-Konfidenz: Das SDK zeigt `direct_power_W` (100% Layout-Konsistenz), das Decoder-Profil hält es als "candidate" (93%). Der Grund ist der Evidence-Guard: "Same-raw export consistency is not independent semantic proof". Dies ist ein korrekter Sicherheitsmechanismus, verhindert aber die automatische Freigabe durch reinen Datenabgleich.

- Hypothesen (nicht bestätigt)
    - Das Feld 1509/9 ist physikalisch als Leistung interpretierbar, da es sich nahtlos in die Rechenlogik `abs(voltage * current)` einfügt, benötigt aber einen externen Hardware-Nachweis (z.B. Vergleich mit einem wattigen Zähler am Akku).
    - 150D enthält keine Geschwindigkeitsdaten, sondern vermutlich Hilfsinformationen (z.B. Batterietemperatur oder Fehlercodes), wie im "Known Device Behavior" beschrieben.

- Nächste sichere READ-ONLY-Tests
    - **Static 1502 Check:** Ein READ-Versuch von 1502 im Stillstand, um zu prüfen, ob sich die Werte verändern oder nur ein statischer Header vorliegt.
    - **150D vs 1505 Korrelation:** Ein READ-Versuch von 150D parallel zu 1505, um zu bestätigen, dass 150D nicht als redundante Geschwindigkeitsquelle fungiert.
    - **150C Musteranalyse:** Ein READ-Versuch von 150C, um zu prüfen, ob es sich um einen Zell-Status-Block (Checksummen oder Pack-IDs) handelt und nicht um reine Zufallswerte.
    - **Odometer Monotonie:** Ein READ-Versuch von 1506 im Stillstand über mehrere Minuten, um sicherzustellen, dass der Kilometerstand nicht rückwärts zählt oder konstant bleibt (was auf einen falschen Zähler hinweisen würde).
    - **Endianness Power-Check:** Ein manuelles Byte-Check (Big-Endian) der `direct_power_W` SDK-Daten gegen die Decoder-Ausgabe von 1509/9, um sicherzustellen, dass keine Byte-Reihenfolge vorliegt, die die 4W MAE verursacht.

- Automatische Änderungen: KEINE
Freigabe: keine automatische Änderung.

## OpenAI GPT-5.6 Luna • Synthese, kein Evidenzvotum

Status: `not_configured`

Nicht konfiguriert.

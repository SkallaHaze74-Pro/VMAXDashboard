# Gemini + GLM Decoder-Zweitprüfung mit optionaler GPT-Synthese

> Advisory only • STRICT READ-ONLY: Gemini und GLM sind unabhängige Prüfer. GPT ordnet ihre Entwürfe optional nur als Synthese und zählt nicht als dritter Evidenzbeleg.
> Eigene oder fremde KI-Antworten zählen niemals als unabhängige Bestätigung; maßgeblich bleiben Mess-Evidenz, deterministische Konsenslogik und Evidence Guard.

## Gemini 3.7 Flash

Status: `ok`

Modell: `gemini-3.5-flash-lite`

Fallbackmodell aktiv: `gemini-3.5-flash-lite`.

- Belastbare Evidenz:
  - 5 Decoder-Regeln (`batteryPercent`, `currentA`, `odometerKm`, `speedKmh`, `voltageV`) sind mit 99% Konfidenz aus 9 bis 21 Fahrten validiert (`original-sdk-layout+app-extraction-check`).
  - `powerW` (1509/9) verbleibt explizit als Kandidat (93% Konfidenz), da die bisherige Bestätigung nur auf internem App-Export-Abgleich beruht und eine unabhängige physikalische Validierung fehlt.
  - Laut GATT-Deep-Read-Abgleich zeigen die Kanäle 1502, 1505, 1506, 1508 und 1509 stabile und erfolgreiche READ-/Notify-Callback-Raten bei 35 von 35 Versuchen.

- Konflikte / mögliche Bugs:
  - Selbstreferenz-Risiko: Mehrere App-Extraktionen vergleichen sich selbst mit dem statischen SDK-Layout, was fälschlicherweise als semantischer Beweis interpretiert werden kann, wenn keine unabhängige Gegenprüfung vorliegt.
  - Unvollständige Zähler in `Zusammenfassung.txt` führen zu unklaren READ-/Hybrid-Verwerfungen in einigen älteren Messfahrten.
  - Das Signal 150D zeigt veränderliche Bytes, bleibt aber nach den ersten echten Fahrten ohne Live-Geschwindigkeitszuordnung ungesichert.

- Hypothesen (nicht bestätigt):
  - 1509/9 (`powerW`) korreliert stark mit dem elektrischen Produkt aus Spannung und Strom, bleibt jedoch ohne externe Referenz (z. B. Leistungsmesskuhlschale oder Hardware-Prüfstand) ein reiner Layout-Kandidat.
  - Charakteristik 150C könnte nach Klärung der Byte-Offests zukünftig als BatteryCellUpdate-Kandidat dienen, enthält aktuell jedoch nur Sentinel-/Platzhalterbytes.

- Nächste sichere READ-ONLY-Tests (max. 5):
  1. Durchführung eines kurzen, statischen READ-/Notify-Mitschnitts unmittelbar nach einem erfolgreichen BLE-Reconnect im Stillstand (ohne aktiven Ladevorgang).
  2. Prüfung der Odometer-Fortschreibung (1506/0) über einen bekannten, manuell gemessenen Kontrollweg im Stillstand oder Schiebebetrieb.
  3. Verifikation der Rohdaten-Integrität von Kanal 1509 auf Konsistenz der Byte-Offsets bei unveränderter Firmware.
  4. Abgleich der Callback-Erfolgsraten für die charakteristischen Blöcke 1505 und 1509 über historische Diagnose-Bundles.
  5. Validierung der unveränderten Leseregeln gegen ein separates, isoliertes Logfile ohne Carry-forward-Effekte.

- Automatische Änderungen: KEINE

Freigabe: keine automatische Änderung.

## GLM

Status: `ok`

Modell: `glm-4.5-flash`

Fallbackmodell aktiv: `glm-4.5-flash`.

Belastbare Evidenz:
- batteryPercent, currentA, odometerKm, speedKmh und voltageV sind mit 99% Konfidenz bestätigt und zeigen hohe Korrelationen
- libble-Vergleich zeigt 100% Übereinstimmung für bestätigte Felder
- BT638 GATT-Deep-Read bestätigt Existenz und Lesbarkeit relevanter Characteristics

Konflikte / mögliche Bugs:
- powerW-Regel nur mit 93% Konfidenz, benötigt externe Validierung
- Diskrepanz zwischen 33 Messfahrten und vielen akzeptierten Exportzeilen bei 0
- Mehrere Felder in libble-Vergleich als "OBSERVED_NEEDS_MORE_PROOF" markiert

Hypothesen (nicht bestätigt):
- powerW könnte andere physikalische Bedeutung als berechnete Leistung haben
- Characteristics 1502 und 150A könnten wichtige, noch nicht zugeordnete Informationen enthalten
- "OBSERVED_NEEDS_MORE_PROOF"-Felder könnten wichtige Systemparameter enthalten

Nächste sichere READ-ONLY-Tests:
- Untersuchung von Characteristic 1502 (Battery/static candidate)
- Untersuchung von Characteristic 150A (Motor live layout)
- Validierung von powerW durch unabhängige physikalische Messungen
- Untersuchung dynamischer Characteristics (1503, 1507, 1508)
- Untersuchung der "OBSERVED_NEEDS_MORE_PROOF"-Felder

Automatische Änderungen: KEINE

Freigabe: keine automatische Änderung.

## OpenAI GPT-5.6 Luna • Synthese, kein Evidenzvotum

Status: `not_configured`

Nicht konfiguriert.

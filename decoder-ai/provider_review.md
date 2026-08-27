# Gemini + GLM Decoder-Zweitprüfung mit optionaler GPT-Synthese

> Advisory only • STRICT READ-ONLY: Gemini und GLM sind unabhängige Prüfer. GPT ordnet ihre Entwürfe optional nur als Synthese und zählt nicht als dritter Evidenzbeleg.
> Eigene oder fremde KI-Antworten zählen niemals als unabhängige Bestätigung; maßgeblich bleiben Mess-Evidenz, deterministische Konsenslogik und Evidence Guard.

## Gemini 3.7 Flash

Status: `ok`

Modell: `gemini-3.5-flash-lite`

Fallbackmodell aktiv: `gemini-3.5-flash-lite`.

- Belastbare Evidenz: 
  - 22 Messfahrten mit insgesamt 700 erfolgreichen READ-Callbacks (100% Callback-Erfolg bei allen 28 Characteristics gemäß BT638 Deep-READ).
  - Fünf bestätigte Decoder-Regeln (batteryPercent, currentA, odometerKm, speedKmh, voltageV) basieren auf konsistenten App-Extraktionen und SDK-Layouts (Konfidenz 99%).
  - Kanal `1509` (Feld 9, `powerW`) ist laut Evidence Guard als `candidate` (93% Konfidenz) markiert, da der Cross-Field-Abgleich (`abs(voltage_v * current_a)`) mit 95.03% Übereinstimmung zwar korreliert, aber keine unabhängige externe Validierung darstellt.

- Konflikte / mögliche Bugs:
  - Selbstreferenz bei Leistungsdaten: Der Kandidat `powerW` (1509/9) stützt sich primär auf interne Cross-Field-Vergleiche desselben RAW-Pakets, was keine echte semantische Fremdbestätigung ist.
  - Unklare Datenqualität in Einzelexporten: Mehrere frühe Fahrten (z.B. `Messfahrt_2026-08-13_19-17-14`) weisen 0 akzeptierte Exportzeilen auf.
  - Ladezustands-Ambivalenz: Da BLE beim Laden abschaltet, dürfen Live-Messungen während dieses Zustands nicht als Basis für Regeländerungen herangezogen werden.

- Hypothesen (nicht bestätigt):
  - `powerW` (1509/9) repräsentiert die tatsächliche elektrische Leistung, validiert durch Spannung und Strom.
  - `150C` entspricht einem verlässlichen `BatteryCellUpdate`-Kanal, besteht aktuell jedoch nur aus Platzhalterbytes.

- Nächste sichere READ-ONLY-Tests (max. 5):
  1. Statischer READ-Abgleich der Characteristics `1502` und `1509` im abgezogenen Zustand nach kurzem Reconnect.
  2. Prüfung der Payload-Stabilität von `1503` und `150A` im Stillstand ohne aktive Fahrt.
  3. Validierung der Odometer-Werte aus `1506` über mehrere aufeinanderfolgende statische Abfragen.
  4. Konsistenzprüfung der Rohdaten-Exportzeilen für Fahrten mit unvollständigen Zählern (z. B. `Zusammenfassung.txt`).
  5. Read-only-Mitschnitt eines einzelnen Notify-Frames unmittelbar nach einem Verbindungsaufbau (ohne Ladezustand).

- Automatische Änderungen: KEINE

Freigabe: keine automatische Änderung.

## GLM

Status: `ok`

Modell: `glm-4.5-flash`

Fallbackmodell aktiv: `glm-4.5-flash`.

Belastbare Evidenz
- Cross-Field-Validierung für powerW zeigt 95.03% Übereinstimmung mit berechneter Leistung (Spannung × Strom)
- Bestätigte Regeln (batteryPercent, currentA, odometerKm, speedKmh, voltageV) zeigen 99% Konfidenz und perfekte Korrelation (1.0)
- BT638 GATT-Deep-Read bestätigt Lesbarkeit aller relevanten Characteristics mit stabilen Callback-Raten
- App-Exporte sind konsistent mit SDK-Layout laut libble-Vergleich

Konflikte / mögliche Bugs
- powerW bleibt trotz starker Cross-Field-Übereinstimmung nur "Kandidat" - mögliche übermäßige Vorsicht
- Viele Messfahrten in libble-Vergleich zeigen 0 akzeptierte Exportzeilen - Datenaufnahmeproblem
- Unklare Zuordnungen für Ladezustandsinformationen (charging, chargingRemainSeconds) bleiben offen
- 150D wird nach erster Fahrt nicht mehr als Live-Geschwindigkeit gelernt - möglicher Verlust semantischer Bedeutung

Hypothesen (nicht bestätigt)
- powerW könnte tatsächlich die Leistung darstellen, benötigt aber unabhängige physikalische Validierung
- Variable Bytes in Characteristics 1505-150A könnten unentdeckte Zusatzinformationen enthalten
- 1502 (Battery/static candidate) könnte relevante Akkustatikdaten liefern, die noch nicht zugeordnet sind
- Niedrige Akzeptanzraten in libble-Vergleich deuten auf mögliche Exportprobleme hin

Nächste sichere READ-ONLY-Tests
- Vergleich von powerW mit extern gemessener Leistung aus unabhängigem Messgerät
- Analyse der variabten Bytes in Characteristics 1505-150A auf Muster und Korrelationen
- Gezielte Untersuchung von 1502 und 150C auf Akkuzustandsdaten
- Überprüfung der Datenaufnahmepipeline für Exportprobleme in libble-Vergleich
- Untersuchung von 150D über mehrere Verbindungszyklen auf semantische Stabilität

Automatische Änderungen: KEINE

Freigabe: keine automatische Änderung.

## OpenAI GPT-5.6 Luna • Synthese, kein Evidenzvotum

Status: `not_configured`

Nicht konfiguriert.

# Gemini + GLM Decoder-Zweitprüfung

> Advisory only • STRICT READ-ONLY: Diese Modelle sind ausschließlich Prüfer. Sie aktivieren keine Decoder-Regel, ändern keinen Code und erzeugen keine BLE-Schreibbefehle.
> Eigene oder fremde KI-Antworten zählen niemals als unabhängige Bestätigung; maßgeblich bleiben Mess-Evidenz, deterministische Konsenslogik und Evidence Guard.

## Gemini 3.7 Flash

Status: `error`

Modell: `gemini-3.7-flash`

Fehler: Provider vorübergehend nicht verfügbar (500) • gemini-3.7-flash is currently experiencing high demand, spikes in demand are usually temporary. Please try again later.

## GLM

Status: `ok`

Modell: `glm-4.7-flash`

Kostenloser GLM-Fallback aktiv.

- Belastbare Evidenz
    - Layout-Konsistenz für `speedKmh` (1505/6), `voltageV` (1509/5), `currentA` (1509/0) und `batteryPercent` (1509/4) ist durch die `libble`-Daten und die `Original App`-Extraktion belegt.
    - `powerW` (1509/9) ist korrekt als `candidate` klassifiziert, da `libble` nur Layout-Übereinstimmung (64.74%) zeigt, keine physische Validierung.

- Konflikte / mögliche Bugs
    - **Datenqualitäts-Artefakt:** In `Messfahrt_2026-08-13_19-17-14` sind `raw_export_rows: 3235` aber `live_rows: 3245`. Die `quality_counters` sind `null`. Dies deutet auf einen Fehler in der Aufzeichnungs-/Filterlogik hin (nicht im Decoder, aber in der Datenquelle), was die Datenqualität beeinträchtigt.
    - **Unverifizierte Skalierung:** `odometerKm` hat `scale: 0.1`. `libble` liefert keine numerischen Werte, um die Skalierung zu validieren (nur Paketzähler). Die Evidenz basiert ausschließlich auf Layout-Annahmen.

- Hypothesen (nicht bestätigt)
    - **Fehlende Regel 1505/8 (RPM):** Der `original app` Konsensbericht identifiziert `1505/8` als RPM (`ControllerPartListener.onRPM`), aber das aktuelle Decoder-Profil hat keine Regel dafür. Dies ist ein ungelöstes Feld.
    - **1509/9 Power-Quelle:** Die Korrelation liegt bei 64.74% (libble). Die physikalische Herleitung (`I * U`) muss bestätigt werden.
    - **1505/0 und 1505/2:** Der `original app` nennt `performancePowerA` (0) und `performancePowerB` (2), weist aber `motorPower` und `treadlePower` als "role_assignment_required" aus. Es ist unklar, ob diese Felder physikalisch unterschiedliche Quellen darstellen oder ob sie redundant sind.

- Nächste sichere READ-ONLY-Tests
    1.  **Power-Math-Check:** Berechne `1509.current_A * 1509.voltage_V` und vergleiche mit `1509/9 (powerW)` über Samples.
    2.  **RPM-Check:** Extrahiere `1505/8` und vergleiche mit `1505/6` (Speed) und `150A.motor_current_A`.
    3.  **Odometer-Check:** Extrahiere `1506/0` Werte über zwei Fahrten und prüfe Differenz vs. Skalierung (0.1).
    4.  **Power-Split-Check:** Vergleiche `1505/0` und `1505/2` mit `1509.direct_power_W` und `150A.motor_current_A`.
    5.  **150D-Check:** Extrahiere Rohdaten von 150D (falls noch vorhanden), um die disprobierte Hypothese der "zweiten Strom/Geschwindigkeitsquelle" endgültig zu schließen.

- Automatische Änderungen: KEINE

Freigabe: keine automatische Änderung.

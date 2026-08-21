# Gemini + GLM Decoder-Zweitprüfung

> Advisory only • STRICT READ-ONLY: Diese Modelle sind ausschließlich Prüfer. Sie aktivieren keine Decoder-Regel, ändern keinen Code und erzeugen keine BLE-Schreibbefehle.
> Eigene oder fremde KI-Antworten zählen niemals als unabhängige Bestätigung; maßgeblich bleiben Mess-Evidenz, deterministische Konsenslogik und Evidence Guard.

## Gemini 3.7 Flash

Status: `ok`

Modell: `gemini-3.5-flash-lite`

Kostenloser GLM-Fallback aktiv.

- Belastbare Evidenz:
  - 5 Regeln (`batteryPercent`, `currentA`, `odometerKm`, `speedKmh`, `voltageV`) sind mit Konfidenz 99% als `confirmed` markiert, basieren laut Profil jedoch auf `original-sdk-layout+app-extraction-check` (gleiche RAW-Extraktion, keine unabhängige physikalische Validierung).
  - Regel `powerW` (1509/9) ist als `candidate` (93%) eingestuft; der Evidence Guard bestätigt ausdrücklich: "same-raw export consistency is not independent semantic proof" (`independentExternalConfirmation: false`).
  - Laut libble-Vergleich und App-Vergleich sind die übereinstimmenden Extraktionen rein konsistent mit dem SDK-Layout (`APP_EXPORT_CONSISTENT_WITH_SDK_LAYOUT`), nicht unabhängig gemessen.
  - Datenqualität je Export zeigt unvollständige Zähler (`?`) für READ-/Hybrid-Werte in mehreren Messfahrten (z. B. `Messfahrt_2026-08-13_19-17-14`).

- Konflikte / mögliche Bugs:
  - Selbstreferenz / Zirkelschluss: Die Kennzeichnung als `confirmed` erfolgt trotz des Hinweises, dass App-Extraktionen gegen denselben RAW-Stream keine externe Ground Truth darstellen.
  - Fehlende Zählerdaten: In der Tabelle "Datenqualität je Export" sind READ- und Hybrid-Werte für frühere Messfahrten mit `?` markiert, was auf unvollständige Zusammenfassungen hinweist.
  - Unbekannte Felder wie 150C oder 150D sind nicht abschließend validiert; 150D wird nach der ersten Fahrt ignoriert.

- Hypothesen (nicht bestätigt):
  - 1509/9 (`powerW`) entspricht dem tatsächlichen elektrischen Leistungswert, obwohl bisher nur ein Cross-Field-Vergleich gegen `voltage_v * current_a` vorliegt (Korrelation 0.984642, MAE 4.13 W).
  - Weitere App-Listener (wie `batteryCapacityMwh`, `stateOfHealthPercent`) lassen sich direkt auf ungemappte BLE-Kanäle abbilden.

- Nächste sichere READ-ONLY-Tests (max. 5):
  1. Prüfen, ob die Zählerwerte in `Zusammenfassung.txt` für die älteren Messfahrten rekonstruiert oder als `unbekannt/offen` dokumentiert werden können.
  2. Überprüfen der Rohdaten von Messfahrt 1505 und 1509 im Stillstand auf statische Offset-Abweichungen bei Strom und Spannung.
  3. Validieren der Byte-Offsets für ungemappte Parameter (`150C`, `150D`) ausschließlich über rein lesende Log-Vergleiche im Stillstand.
  4. Dokumentieren fehlender `Zusammenfassung.txt`-Einträge als explizite Datenlücke statt Null-Imputation.

- Automatische Änderungen: KEINE

Freigabe: keine automatische Änderung.

## GLM

Status: `error`

Modell: `glm-5.3`

Fehler: The read operation timed out

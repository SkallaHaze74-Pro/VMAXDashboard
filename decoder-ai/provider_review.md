# Gemini + GLM Decoder-Zweitprüfung mit optionaler GPT-Synthese

> Advisory only • STRICT READ-ONLY: Gemini und GLM sind unabhängige Prüfer. GPT ordnet ihre Entwürfe optional nur als Synthese und zählt nicht als dritter Evidenzbeleg.
> Eigene oder fremde KI-Antworten zählen niemals als unabhängige Bestätigung; maßgeblich bleiben Mess-Evidenz, deterministische Konsenslogik und Evidence Guard.

## Gemini 3.7 Flash

Status: `ok`

Modell: `gemini-3.5-flash-lite`

Fallbackmodell aktiv: `gemini-3.5-flash-lite`.

- Belastbare Evidenz:
  * Die Kanäle 1505 (Geschwindigkeit), 1506 (Kilometerstand), und 1509 (Spannung, Strom, SOC) besitzen eine 99%ige Konfidenz basierend auf 13 bis 16 Fahrten und dem `original-sdk-layout+app-extraction-check`.
  * Kanal 1509 Offset 9 (`powerW`) ist laut Decoder-Profil als `candidate` (93% Konfidenz) markiert, da der Cross-Field-Vergleich (`abs(voltage_v * current_a)`) eine Übereinstimmung von 95.35% (MAE 3.77W) zeigt.
  * Laut libble-Vergleich und Evidenzguard ist der App-Export konsistent mit dem SDK-Layout, stellt jedoch keine unabhängige semantische Validierung dar (`independentExternalConfirmation: false`).

- Konflikte / mögliche Bugs:
  * Selbstreferenz bei Leistungsdaten: Der Kandidat `powerW` wird gegen interne Ableitungen derselben Rohdaten geprüft, was keinen externen physikalischen Beweis darstellt.
  * Unvollständige Zähler in der Datenqualitäts-Tabelle (viele Fragezeichen bei `Zusammenfassung.txt`-Werten in den frühen Messfahrten).

- Hypothesen (nicht bestätigt):
  * 1509/9 u16be entspricht der tatsächlichen elektrischen Leistung in Watt, unterstützt durch den starken Kreuzvergleich mit Spannung und Strom.
  * Die noch nicht zugeordneten Parameter wie `batteryCapacityMwh` oder `charging` lassen sich in den aktuell nur als Sentinel/Platzhalter erkannten Kanälen (z.B. 150C) auffinden.

- Nächste sichere READ-ONLY-Tests (max. 5):
  1. Live-READ-Mitschnitt im Stillstand bei getrenntem Ladegerät zur Verifizierung der statischen Kanäle (1502).
  2. Manueller Abgleich von Odometer-Werten (Kanal 1506) mit externen GPS-Referenzstrecken im Stand.
  3. Protokollierung von Kanal 1503 und 1508 während konstanter, langsamer Bewegung zur Identifizierung von Variantenwechseln.
  4. Konsistenzprüfung der Rohdaten-Exportzeilen ohne Übernahme von Null-Werten bei fehlenden Zusammenfassungen.
  5. Lesen von Charakteristik 1802 im getrennten Zustand zur Validierung der redigierten Payloads.

- Automatische Änderungen: KEINE

Freigabe: keine automatische Änderung.

## GLM

Status: `ok`

Modell: `glm-4.5-flash`

Fallbackmodell aktiv: `glm-4.5-flash`.

Belastbare Evidenz
- 5 Regeln mit 99% Konfidenz durch SDK-Layout und App-Extraktions-Check bestätigt
- powerW-Kandidat zeigt 95.35% Übereinstimmung mit |Spannung × Strom|-Berechnung
- Alle 28 GATT-Characteristics erfolgreich mit READ-Callback belegt
- Cross-Field-Vergleich basiert auf 2668 Sample-Paaren mit hoher Korrelation (0.992507)

Konflikte / mögliche Bugs
- powerW bleibt Kandidat trotz hoher Korrelation, da keine unabhängige semantische Bestätigung vorliegt
- Frühe Messfahrten zeigen viele ungültige Exportzeilen (0 akzeptierte), was auf Datenqualitätsprobleme hindeutet
- App-Verglich markiert einige Felder als "APP_EXPORT_LAYOUT_CONSISTENT_NON_INDEPENDENT", obwohl Decoder sie als "confirmed" listet
- 150C bisher nur als Sentinel-Bytes beobachtet, könnte aber ungenutzte Zellinformationen enthalten

Hypothesen (nicht bestätigt)
- powerW könnte eine systematische Skalierungsabweichung haben, trotz hoher Korrelationswerte
- 1502 (Battery/static candidate) könnte statische Akkudaten wie Seriennummer enthalten
- Verbesserte Datenqualität in späteren Fahrten könnte auf stabilisierte Extraktionsprozesse hindeuten
- Unterschiedliche Payload-Varianten in 1503,1507,1508 könnten noch nicht zugeordnete Betriebsmodi anzeigen

Nächste sichere READ-ONLY-Tests (max. 5)
- Unabhängige physikalische Validierung von powerW mit externem Referenzleistungsmesser
- Detaillierte Analyse von 1502-Payloads auf statische Akkudaten
- Untersuchung von 1503/1507/1508 unter verschiedenen Betriebsbedingungen
- Prüfung von 150C auf Zellinformationen bei verschiedenen Lade-/Entladezuständen
- Überprüfung der Skalierungsfaktoren durch Vergleich mit bekannten Referenzwerten

Automatische Änderungen: KEINE

Freigabe: keine automatische Änderung.

## OpenAI GPT-5.6 Luna • Synthese, kein Evidenzvotum

Status: `not_configured`

Nicht konfiguriert.

# Gemini + GLM Pro in VMAXDashboard

VMAXDashboard unterstützt optional zwei externe KI-Prüfer:

- **Gemini 3.7 Flash** – Standard für schnelle und tiefe Decoder-/Code-Prüfungen.
- **GLM-5.3** – unabhängige Zweitmeinung und Fallback.

Die bestehende deterministische Decoder-AI bleibt maßgeblich. Externe Modellantworten sind **nur advisory** und aktivieren keine Decoder-Regel und senden keine BLE-Schreibbefehle.

## Gemini-Anbindung

Gemini läuft über die **Interactions API** auf dem stabilen `v1`-Endpunkt:

`https://generativelanguage.googleapis.com/v1/interactions`

VMAXDashboard verwendet `gemini-3.7-flash`, eine Systemanweisung, `thinking_level=high` und liest die neue `steps`-Antwortstruktur aus. Für Decoder-Prüfungen wird derzeit `store=false` gesetzt, damit keine serverseitige Gesprächshistorie benötigt wird. Die Interactions-Basis erlaubt später trotzdem zustandsbehaftete Gespräche, Tools, strukturierte Ausgaben und weitere Agent-Funktionen.

Für neue Gemini-Schlüssel den von Google AI Studio standardmäßig erzeugten **Authorization API Key** verwenden. Die App sendet ihn ausschließlich im `x-goog-api-key`-Header und speichert ihn verschlüsselt im Android Keystore.

## Modi in der Android-App

Im Bildschirm **GitHub & Decoder AI** stehen vier Modi bereit:

1. **Gemini 3.7 Flash** – nur Gemini.
2. **GLM-5.3** – nur GLM.
3. **Auto • Gemini → GLM** – zuerst Gemini, bei Fehler automatisch GLM. Empfohlen für den Alltag und zum Sparen von Kontingent/Kosten.
4. **Pro Duo • Gemini + GLM** – beide Modelle prüfen unabhängig; anschließend wird eine gemeinsame Endanalyse erzeugt. Für schwierige Decoder-, Messfahrt- oder Codefragen.

## API-Keys auf dem Android-Gerät einrichten

1. VMAXDashboard öffnen.
2. **GitHub & Decoder AI** öffnen.
3. Gemini-Key in **Gemini API-Key** eintragen und **GEMINI SPEICHERN** drücken.
4. GLM-Key in **GLM-5.3 API-Key** eintragen und **GLM SPEICHERN** drücken.
5. Für den normalen Betrieb **Auto • Gemini → GLM** wählen.
6. Für eine besonders gründliche Gegenprüfung **Pro Duo • Gemini + GLM** wählen.

Die Keys werden mit **Android Keystore + AES/GCM** verschlüsselt auf dem Gerät gespeichert. Sie stehen nicht im Quellcode und werden nicht in das GitHub-Repository geschrieben.

> Für eine öffentlich verteilte APK ist ein eigener Backend-Proxy die stärkere Sicherheitsstufe, weil ein Provider-Key dann das Gerät gar nicht verlassen bzw. dort gar nicht gespeichert werden muss.

## Automatische Zweitprüfung in GitHub Actions

Damit neue Messfahrten im `telemetry-data`-Workflow zusätzlich durch Gemini und GLM geprüft werden, im Repository unter:

**Settings → Secrets and variables → Actions → New repository secret**

folgende Secrets anlegen:

- `GEMINI_API_KEY`
- `ZHIPU_API_KEY`

Sind keine der beiden Secrets gesetzt, läuft die vorhandene deterministische Decoder-AI unverändert weiter. Ist nur ein Secret gesetzt, wird nur dieser Provider verwendet.

Auch der GitHub-Actions-Prüfer verwendet für Gemini die stabile **Interactions API v1** und die neue `steps`-Antwortstruktur.

Die externe Prüfung erzeugt optional:

- `decoder-ai/provider_review.json`
- `decoder-ai/provider_review.md`

Diese Dateien enthalten nur eine Zweitmeinung. `decoder-ai/decoder_profile.json` wird weiterhin ausschließlich von der deterministischen Konsensanalyse erzeugt.

## Datenschutz und Sicherheitsgrenzen

Der App-Prompt übermittelt für die manuelle Decoder-Prüfung nur einen kompakten Statuskontext, zum Beispiel Regelanzahl, Profilquelle, Signale und Sync-Status. API-Keys, GitHub-Token, Bluetooth-Adresse und GPS-Daten werden absichtlich nicht in diesen Kontext aufgenommen.

Die externe KI ist technisch vom `BluetoothGatt` getrennt. Sie kann keine Scooter-Befehle ausführen. Unsichere Modellantworten dürfen nicht automatisch in Motor-, Startmodus- oder andere BLE-Schreiboperationen übernommen werden.

## Empfohlener Betrieb

- **Auto** für normale Fragen und möglichst geringe Nutzung.
- **Pro Duo** nur bei widersprüchlichen Decoder-Kandidaten, schwierigen Messfahrten oder wichtigen Code-Reviews.
- Neue Decoder-Zuordnungen erst übernehmen, wenn lokale Messdaten und die bestehende Konsenslogik sie ausreichend bestätigen.

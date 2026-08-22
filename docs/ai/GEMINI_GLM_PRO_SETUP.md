# Gemini + GLM Pro in VMAXDashboard

Stand: 21.08.2026. Provider-Modellnamen, Quoten und Endpunkte können sich ändern; die Sicherheitsgrenzen dieses Dokuments gelten unabhängig davon.

VMAXDashboard unterstützt optional zwei externe KI-Prüfer:

- **Gemini 3.7 Flash** – Standard für schnelle und tiefe Decoder-/Code-Prüfungen.
- **GLM-5.3** – unabhängige Zweitmeinung; bei fehlendem Guthaben fällt die App automatisch auf kostenlose GLM-Flash-Modelle zurück.

Die bestehende deterministische Decoder-AI bleibt maßgeblich. Externe Modellantworten sind **nur advisory** und aktivieren keine Decoder-Regel und senden keine BLE-Schreibbefehle.

## Gemini-Anbindung

Gemini läuft über die **Interactions API** auf dem stabilen `v1`-Endpunkt:

`https://generativelanguage.googleapis.com/v1/interactions`

VMAXDashboard verwendet `gemini-3.7-flash`, eine Systemanweisung, `thinking_level=high` und liest die neue `steps`-Antwortstruktur aus. Für Decoder-Prüfungen wird derzeit `store=false` gesetzt, damit keine serverseitige Gesprächshistorie benötigt wird.

Für neue Gemini-Schlüssel den von Google AI Studio erzeugten API-Key verwenden. Die App sendet ihn ausschließlich im `x-goog-api-key`-Header und speichert ihn verschlüsselt im Android Keystore.

## GLM: Z.ai + BigModel automatisch

VMAXDashboard unterstützt beide Plattformen:

1. **Z.ai / international** – `https://api.z.ai/api/paas/v4/chat/completions`
2. **BigModel / China** – `https://open.bigmodel.cn/api/paas/v4/chat/completions`

Die App versucht für GLM-5.3 zuerst Z.ai. Wird der Schlüssel dort wegen Plattform-/Auth-Zuordnung nicht akzeptiert, wird automatisch BigModel probiert.

Für GLM-5.3 wird Deep Thinking explizit aktiviert:

- `thinking.type = enabled`
- `reasoning_effort = max`

### Kostenloser GLM-Fallback

Wenn GLM-5.3 wegen `429`, fehlendem Guthaben oder fehlendem Ressourcenpaket nicht nutzbar ist, versucht VMAXDashboard automatisch über Z.ai:

1. `glm-4.7-flash` – kostenlos
2. `glm-4.5-flash` – kostenloser zweiter Fallback

Für diese beiden Flash-Modelle ist in Z.ai **keine zusätzliche Aktivierung und kein separates Ressourcenpaket nötig**. Ein vorhandener Z.ai-API-Key genügt. Es können weiterhin normale Rate-Limits des kostenlosen Angebots gelten.

Die App zeigt in der letzten automatischen Analyse das tatsächlich verwendete Modell an, damit erkennbar ist, ob GLM-5.3 oder ein kostenloser Fallback geantwortet hat.

### Wichtig zum GLM-Key

Z.ai und BigModel verwenden einen vollständigen API-Key im Format:

`API_KEY_ID.secret`

Das ist **nur die Formatbeschreibung**. Beim Eintragen in VMAXDashboard niemals den Text `API_KEY_ID` oder `secret` davor schreiben. Einfach auf der Provider-Seite beim Feld **API-Schlüssel** auf Kopieren drücken und den **komplett kopierten Wert unverändert** in die App einfügen.

Beispiel nur zur Form:

`abc123.def456`

Nicht verwenden:

`API_KEY_ID.secret-abc123...`

Die App prüft beim Speichern, ob beide Teile mit einem Punkt vorhanden sind.

## Automatische Kette in der Android-App

Im Alltag arbeitet die App möglichst kostenfrei:

`Gemini 3.7 Flash → Gemini Quota-Fallback → GLM-5.3 → GLM-4.7-Flash (gratis) → GLM-4.5-Flash (gratis)`

Bei einer neuen Messfahrt oder einem geänderten Decoder-Profil läuft diese Zweitprüfung automatisch. Wiederholte Hintergrundprüfungen werden über einen Evidenz-Fingerprint begrenzt, damit keine unnötigen API-Aufrufe entstehen.

## API-Keys auf dem Android-Gerät einrichten

1. VMAXDashboard öffnen.
2. **GitHub & Decoder AI** öffnen.
3. Gemini-Key in **Gemini API-Key** eintragen und **GEMINI SPEICHERN** drücken.
4. Den vollständig kopierten Z.ai-/BigModel-Key (`ID.secret`) in **GLM-5.3 API-Key** eintragen und **GLM SPEICHERN** drücken.
5. Die **Automatische KI-Zweitprüfung** eingeschaltet lassen.

Die Keys werden mit **Android Keystore + AES/GCM** verschlüsselt auf dem Gerät gespeichert. Sie stehen nicht im Quellcode und werden nicht in das GitHub-Repository geschrieben.

> Für eine öffentlich verteilte APK ist ein eigener Backend-Proxy die stärkere Sicherheitsstufe, weil ein Provider-Key dann das Gerät gar nicht verlassen bzw. dort gar nicht gespeichert werden muss.

## Automatische Zweitprüfung in GitHub Actions

Damit neue Messfahrten im `telemetry-data`-Workflow zusätzlich durch Gemini und GLM geprüft werden, im Repository unter:

**Settings → Secrets and variables → Actions → New repository secret**

folgende Secrets anlegen:

- `GEMINI_API_KEY`
- `ZHIPU_API_KEY`
- optional `OPENAI_API_KEY` für die zusätzliche GPT-Synthese im manuellen Workflowlauf

`ZHIPU_API_KEY` kann ein Z.ai- oder BigModel-Key sein. Der GitHub-Prüfer nutzt dieselbe Z.ai/BigModel- und kostenlose GLM-Fallback-Logik.

Sind weder Gemini- noch GLM-Secret gesetzt, läuft die vorhandene deterministische Decoder-AI unverändert weiter. Ist nur eines davon gesetzt, wird nur dieser Prüfprovider verwendet.

`OPENAI_API_KEY` ist kein weiterer Messbeweis und ersetzt weder Gemini/GLM noch die deterministische Analyse. Die GPT-Synthese fasst Prüfergebnisse nur advisory zusammen; im normalen automatischen Telemetrie-Push wird sie nicht erzwungen.

Die externe Prüfung erzeugt optional:

- `decoder-ai/provider_review.json`
- `decoder-ai/provider_review.md`

Diese Dateien enthalten nur eine Zweitmeinung. `decoder-ai/decoder_profile.json` wird weiterhin ausschließlich von der deterministischen Konsensanalyse erzeugt.

## Datenschutz und Sicherheitsgrenzen

Der App-Prompt übermittelt für die Decoder-Prüfung nur einen kompakten Statuskontext, zum Beispiel Regelanzahl, Profilquelle, Signale und Sync-Status. API-Keys, GitHub-Token, Bluetooth-Adresse und GPS-Daten werden absichtlich nicht in diesen Kontext aufgenommen.

Die externe KI ist technisch vom `BluetoothGatt` getrennt. Sie kann keine Scooter-Befehle ausführen. Unsichere Modellantworten dürfen nicht automatisch in Motor-, Startmodus- oder andere BLE-Schreiboperationen übernommen werden.

## Empfohlener Betrieb

- Automatik eingeschaltet lassen.
- Z.ai-Key einmal speichern; für die kostenlosen Flash-Fallbacks ist keine weitere Z.ai-Aktivierung nötig.
- Neue Decoder-Zuordnungen erst übernehmen, wenn lokale Messdaten und die bestehende Konsenslogik sie ausreichend bestätigen.

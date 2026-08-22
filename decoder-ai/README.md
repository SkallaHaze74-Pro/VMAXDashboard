# Decoder-Ausgaben

Die erzeugten Decoder-Berichte und Profile liegen auf dem Branch `telemetry-data` unter `decoder-ai/`. Auf `main` werden nur Analyzer-Code, Tests, Regeln und diese Erklärung gepflegt.

So bleiben App-Entwicklung und veränderliche Messfahrt-Ergebnisse getrennt. Die Android-App lädt das freigegebene Profil gezielt aus `telemetry-data`; KI-Berichte bleiben advisory und bestätigen keine Regel automatisch.

## Privacy-Prüfung für bestehende Daten

Die App redigiert neue öffentliche Uploads direkt an der GitHub-Grenze. Für bereits vorhandene CSV-Dateien spiegelt
[`tools/decoder_ai/redact_public_telemetry.py`](../tools/decoder_ai/redact_public_telemetry.py)
denselben Identitäts- und Freitext-Contract. Standardmäßig liest das Werkzeug nur und nennt ausschließlich Pfade und Zähler, niemals Payloadwerte:

```bash
python3 tools/decoder_ai/redact_public_telemetry.py --check /pfad/zum/telemetry-data-worktree
```

`--write` ist ausschließlich für eine ausdrücklich freigegebene Current-Tip-Bereinigung vorgesehen. Vorher müssen der genaue Datencommit, ein Wiederherstellungsref und eine private Sicherung feststehen. Das Werkzeug validiert zuerst den gesamten Stapel, lehnt leere oder symbolisch verlinkte Ziele ab, prüft jede Quelle unmittelbar vor dem Schreiben erneut und ersetzt danach jede betroffene Datei atomar; ein Mehrdateilauf ist jedoch keine Dateisystem-Transaktion und bleibt bei einem späten I/O-Fehler über die vorherige Sicherung wiederherstellbar. Betroffene CSVs werden kanonisch als UTF-8 mit LF und abschließendem Zeilenumbruch ausgegeben; BOM, CRLF und Leerzeilen bleiben deshalb nicht bytegleich, die logischen Zellen schon. Das Werkzeug verändert nur betroffene `BLE_Rohdaten.csv`-/`Gatt_READ_Diagnose.csv`-Dateien und ersetzt exakte sensible Bytes durch einen stabilen, ungesalzenen SHA-256-Vergleichswert. Ein solcher Hash bleibt über öffentliche Uploads verknüpfbar und ist kein anonymer Wert.

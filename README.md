# VMAX Dashboard 2.1 – dauerhaft updatefähig

Premium-Dashboard mit BLE-Telemetrie und fest eingerichteter Release-Signatur für zukünftige Updates.

## Build

Der GitHub-Workflow **Signierte VMAX Update-APK** erzeugt eine signierte Release-APK. Vor dem ersten Build müssen die vier Repository-Secrets aus dem privaten Signatur-Backup hinterlegt werden.

## Paket und Version

- Paketname: `de.kevin.vmaxdashboard`
- Basisversion: `2.1`
- `versionCode`: automatisch `2100 + GitHub-Laufnummer`

Siehe `UPDATE_SIGNATUR_ANLEITUNG.md`.

## Version 2.2 – BLE Labor

Die Diagnoseansicht enthält jetzt markierbare Testphasen (Stillstand, Rad, Fahrt und Bremse), automatische Min-/Max-/Änderungsstatistiken je Byte, einen Kandidatenfilter, Analyse-Reset und einen kopierbaren Textbericht. Damit lassen sich unbekannte Telemetriewerte beim Scooter-Test wesentlich schneller zuordnen.

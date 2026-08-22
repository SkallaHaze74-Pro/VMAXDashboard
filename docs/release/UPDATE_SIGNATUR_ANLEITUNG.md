# Dauerhafte Update-Signatur einrichten

Der Workflow **VMAX Dashboard Fahrdaten Build** erzeugt auf `main` eine signierte Release-APK und prüft danach Paketname, Version und das fest erwartete Signaturzertifikat. Damit Android spätere APKs als Update akzeptiert, müssen dieselben vier GitHub-Actions-Secrets dauerhaft verwendet werden.

## GitHub-Secrets anlegen

Im Repository öffnen:

`Settings → Secrets and variables → Actions → New repository secret`

Diese vier Secrets mit den privaten Keystore-Werten anlegen:

- `SIGNING_KEY_BASE64`
- `SIGNING_STORE_PASSWORD`
- `SIGNING_KEY_ALIAS`
- `SIGNING_KEY_PASSWORD`

Die Werte niemals in Issues, Chats, Screenshots, PRs oder Dateien im Repository einfügen. Eine lokale Datei `GITHUB-SECRETS.txt`, Keystores sowie APK/AAB-Dateien werden durch `.gitignore` und den Repository-Hygiene-Check blockiert.

## Update installieren

1. In **Actions → VMAX Dashboard Fahrdaten Build** einen grünen `main`-Lauf öffnen.
2. Das Artefakt `VMAXDashboard-Fahrdaten-Release` herunterladen.
3. Die enthaltene `VMAXDashboard-v…-Release.apk` über die vorhandene App installieren.

Der Paketname bleibt `de.kevin.vmaxdashboard`. Versionscode und Versionsname werden in CI aus der Laufnummer erzeugt und anschließend direkt aus der APK verifiziert.

Wenn Android einen Signaturkonflikt meldet, nicht blind deinstallieren: zuerst prüfen, ob wirklich die Release-APK dieses Workflows verwendet wurde. Eine Deinstallation löscht lokale App-Daten, ausstehende Messfahrten, Einstellungen und gespeicherte Tokens/Keys.

## Wiederherstellung

Der private Keystore und seine Passwörter müssen außerhalb von GitHub sicher gesichert werden. Geht der Keystore verloren, können bestehende Installationen nicht mit einer neu signierten APK aktualisiert werden.

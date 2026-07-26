# Dauerhafte Update-Signatur einrichten

Diese Projektversion baut eine signierte Release-APK. Damit Android spätere APKs als Update akzeptiert, müssen die vier GitHub-Secrets einmalig eingerichtet werden.

## GitHub-Secrets anlegen

Im Repository öffnen:

`Settings → Secrets and variables → Actions → New repository secret`

Lege diese vier Secrets mit den Werten aus der privaten Datei `GITHUB-SECRETS.txt` an:

- `SIGNING_KEY_BASE64`
- `SIGNING_STORE_PASSWORD`
- `SIGNING_KEY_ALIAS`
- `SIGNING_KEY_PASSWORD`

Danach unter `Actions` den Workflow **Signierte VMAX Update-APK** starten.

## Wichtig

Die bisher installierte Debug-App hat sehr wahrscheinlich eine andere Signatur. Deshalb ist beim ersten Wechsel auf diese dauerhaft signierte Version einmalig Folgendes nötig:

1. Alte App deinstallieren.
2. Die neue signierte APK installieren.
3. Ab dann alle weiteren APKs aus diesem Workflow einfach darüber installieren.

Der Paketname bleibt `de.kevin.vmaxdashboard`. Die Versionsnummer wird bei jedem GitHub-Lauf automatisch erhöht.

Den privaten Keystore und die Secret-Datei niemals ins öffentliche Repository hochladen.

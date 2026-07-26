# APK nur mit dem Handy bauen

Mimo ist dafür nicht geeignet. Diese Projektversion kann stattdessen kostenlos
über GitHub Actions gebaut werden.

1. Auf github.com ein kostenloses Konto erstellen oder anmelden.
2. Ein neues Repository erstellen, zum Beispiel `VMAXDashboard`.
3. Den Inhalt dieses Projektordners hochladen. Wichtig: `app`, `.github`,
   `settings.gradle.kts`, `build.gradle.kts` und `gradle.properties` müssen
   direkt im Hauptverzeichnis des Repositorys liegen.
4. Im Repository oben `Actions` öffnen.
5. Links `APK bauen` auswählen.
6. `Run workflow` drücken.
7. Nach einigen Minuten den fertigen Lauf öffnen.
8. Unter `Artifacts` die Datei `VMAX-Dashboard-APK` herunterladen.
9. ZIP entpacken und `app-debug.apk` installieren.

Die Debug-APK ist für den privaten Test gedacht. Android kann beim Installieren
die Erlaubnis für unbekannte Apps verlangen.

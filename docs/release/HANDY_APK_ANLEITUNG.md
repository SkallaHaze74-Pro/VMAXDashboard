# Signierte APK über GitHub Actions abrufen

Das Projekt kann direkt über GitHub Actions gebaut werden; eine Android-IDE auf dem Handy ist dafür nicht nötig.

1. Im Repository `SkallaHaze74-Pro/VMAXDashboard` den Bereich **Actions** öffnen.
2. Links **VMAX Dashboard Fahrdaten Build** auswählen.
3. **Run workflow** wählen und den Branch `main` starten oder den fertigen Lauf des aktuellen `main` öffnen.
4. Warten, bis alle Schritte grün sind.
5. Unter **Artifacts** `VMAXDashboard-Fahrdaten-Release` herunterladen.
6. ZIP entpacken und die versionsgebundene Datei `VMAXDashboard-v…-Release.apk` installieren.

Nur die Release-APK aus diesem Workflow ist für Updates vorgesehen. Die zusätzlich auf Pull Requests erzeugte Debug-APK hat eine andere Signatur und darf nicht als dauerhaftes Update verteilt werden.

Android kann beim ersten manuellen Installieren die Erlaubnis „Unbekannte Apps installieren“ für den verwendeten Browser oder Dateimanager verlangen. Vor dem Installieren muss der GitHub-Lauf vollständig grün sein.

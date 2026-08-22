# Datenschutz – technischer Entwurf für die private Testversion

Stand: 22.08.2026. Dieser Text beschreibt den aktuellen technischen Datenfluss. Er ist noch keine vollständige öffentliche Datenschutzerklärung und ersetzt keine rechtliche Prüfung.

## Lokal verarbeitete Daten

VMAXDashboard verarbeitet Bluetooth-Geräteinformationen, GATT-Metadaten, empfangene Rohpakete, daraus abgeleitete Telemetrie, Messfahrtmarker, Diagnose-READ-Antworten, Verbindungsereignisse, Decoderprofile und App-Einstellungen. Messfahrten und ausstehende Uploads liegen zunächst im privaten App-Speicher. Android-Cloud-Backup ist für die App deaktiviert.

Ein normales App-Update behält diese lokalen Daten. Nach Deinstallation, Neuinstallation oder Gerätewechsel werden sie wegen der deaktivierten Sicherung nicht automatisch wiederhergestellt; wichtige Messfahrten daher vorher sicher synchronisieren oder exportieren.

Die App benötigt auf älteren Android-Versionen eine Standortberechtigung für den BLE-Scan, erhebt aber keine GPS-Koordinaten für Messfahrten oder Berichte.

## GitHub-Sync

Eine Übertragung zu GitHub erfolgt nur, wenn der Nutzer einen GitHub-Token einrichtet und die Synchronisierung auslöst bzw. aktiviert. Messfahrten werden in das konfigurierte öffentliche Repository auf den Branch `telemetry-data` hochgeladen. Dadurch können hochgeladene Inhalte öffentlich abrufbar sein.

Neue Uploads durchlaufen eine deterministische Privacy-Redaktion. Bluetooth-Adressen, Seriennummern, Firmware-/Identitätsantworten und vergleichbare Gerätekennungen werden gemäß dem aktuellen Privacy-Contract entfernt oder redigiert. Dennoch sollten Nutzer Messfahrtordner vor einer Veröffentlichung prüfen.

Die verwendeten öffentlichen Hashmarker sind stabile, ungesalzene SHA-256-Pseudonyme. Gleiche Eingaben bleiben dadurch über Uploads hinweg verknüpfbar; das ist Pseudonymisierung, keine vollständige Anonymisierung. Bei Identitäten mit kleinem Suchraum ist ein Hash außerdem kein gleichwertiger Ersatz für Entfernung oder einen geheimen, zweckgebundenen HMAC.

**Historische Altlast am 22.08.2026:** Der aktuelle öffentliche `telemetry-data`-Tip wurde in einem gesonderten, normalen Datencommit redigiert. Ein deterministischer Nachlauf findet dort keine nach dem aktuellen CSV-Contract noch exakt veröffentlichten Identitäts-/Freitext-Payloads. Die zuvor veröffentlichten exakten Werte bleiben trotzdem in der Git-Historie und im angelegten Wiederherstellungsref erreichbar. Es gab keinen History-Rewrite und keinen Force-Push; eine Historienbereinigung bleibt ein weiterer destruktiver Vorgang und erfolgt nur nach neuer ausdrücklicher Freigabe und Sicherung.

## Externe KI-Prüfer

Gemini, GLM und optional GPT werden nur verwendet, wenn die jeweiligen API-Schlüssel eingerichtet sind. Für Decoder-Prüfungen wird ein begrenzter Analysekontext an den gewählten Provider übertragen. GitHub-Token, Provider-Keys, Bluetooth-Adresse und GPS-Daten dürfen nicht Teil dieses Kontexts sein.

Die Modellantworten sind ausschließlich advisory. Sie können keine BLE-Befehle ausführen und bestätigen keine Decoder-Regel automatisch. Provider speichern oder verarbeiten Anfragen nach ihren eigenen Bedingungen; diese müssen vor einer öffentlichen Verteilung separat verlinkt und bewertet werden.

## Entfernte Client-Integration

Die frühere, nicht mehr verwendete Firebase-Telemetrie-, Analytics-, Crashlytics-, Firestore- und Auth-Anbindung ist aus dem App-Client entfernt. Der aktuelle Quellstand enthält dafür keine Firebase-Abhängigkeiten und keine `google-services.json`. Das löscht nicht automatisch früher serverseitig gespeicherte Firebase-Daten oder die Firebase-Projektkonfiguration; deren Aufbewahrung und API-Key-Einschränkungen müssen der Projektinhaber separat prüfen.

## Gerätesteuerung

Telemetrie, Deep READ und KI-Analyse sind read-only. Die App besitzt als bewusst ausgelöste Einstellung die Zero-/Kick-Start-Umschaltung. Dieser lokale BLE-Schreibvorgang wird nicht von KI oder Uploads ausgelöst und ist kein Telemetrie-Upload.

## Vor einer öffentlichen Veröffentlichung ergänzen

- Verantwortlicher und ladungsfähige Kontaktmöglichkeit
- Rechtsgrundlagen und Zwecke je Datenkategorie
- Empfänger, Drittlandtransfers und Provider-Links
- Speicherdauer und Löschprozess für GitHub- und Providerdaten
- Betroffenenrechte und Beschwerdemöglichkeit
- Prüfung, ob das öffentliche Repository für reale Messfahrten weiterhin angemessen ist

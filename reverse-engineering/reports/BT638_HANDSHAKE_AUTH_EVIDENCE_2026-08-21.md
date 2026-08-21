# BT638 / VX2 Gear – Handshake- und Authentifizierungs-Evidenz 2026-08-21

## Ergebnis

Die Aussage, der **VMAX VX2 Gear / BT638** benötige einen geheimen Hersteller-Handshake bzw. einen „Secret Key“, um tiefere BLE-Daten freizuschalten, ist mit den vorliegenden Originaldateien **nicht belegt**.

Im ausgelieferten generischen GPST-BLE-SDK existiert zwar echte Authentifizierungs-/Key-Logik. Die konkret benannten Key-Routinen sind jedoch ausdrücklich **Sachs-Bike-spezifisch**. Ohne vollständige Caller-/Callee-Kette ist damit kein konkreter BT638-Runtimepfad bewiesen; die Sachs-Namen dürfen erst recht nicht als BT638-/V-Core-Handshake umetikettiert werden.

Die normale BT638-Telemetrie auf DA1A/15xx wird von VMAXDashboard bereits nach normalem GATT-Connect/Service-Discovery/Notify-Setup empfangen, ohne dass VMAXDashboard einen BT638-spezifischen Application-Layer-Handshake sendet oder beobachtet. Das sagt nichts über eventuell bereits bestehende BLE-Link-Layer-Pairing-/Bonding-Zustände aus.

## Nutzerhinweis zur Controller-Read-Protection

Der Nutzer berichtet, dass der V-Core-Controller einen Kopier-/Ausleseschutz („Read Protection“) besitzt. Dieser Hardwarehinweis ist bislang nicht unabhängig über eine konkrete Controller-/MCU-Kennung oder ausgelesene Protection-Bits belegt. Selbst wenn er zutrifft, ist Firmware-/Debug-Port-Read-Protection technisch nicht dasselbe wie ein geheimer BLE-Application-Layer-Handshake und beweist keinen BT638-„Unlock Key“.

VMAXDashboard versucht weder Schutzbits noch Debug-/Firmware-Sperren zu umgehen. Der neue Diagnosepfad beschränkt sich auf die von Android nach normaler Verbindung ausgewiesenen `PROPERTY_READ`-Characteristics und protokolliert deren echte GATT-Ergebnisse. Nicht angebotene oder abgewiesene Bereiche bleiben als nicht verfügbar/nicht nachgewiesen dokumentiert.

## Verifizierte Originaldateien

- `base.apk` / VMAX Original-App — SHA-256 `5f9ee266672bf7f24c7b45dd35546a46317498a499daeeff0dc1ef121ccdb8af`
- `libble-sdk-native-lib.so` — SHA-256 `6050df512a62edb19279d169c0e416a6674ed036d046f5026c6a6512dafe7760`

Diese Originaldateien und die vollständigen `nm`-/`strings`-/DEX-Suchausgaben sind nicht im Repository eingecheckt. Der Bericht ist deshalb hashgebunden, aber aus dem Repository allein noch nicht vollständig reproduzierbar oder als erschöpfender Negativnachweis geeignet. Symboladressen, Toolversionen und echte Aufrufketten bleiben nachzuliefern.

## Native Authentifizierungs-Symbole

Direkte Strings/Symbole aus `libble-sdk-native-lib.so` enthalten u. a.:

- `GPSTProtocolHandler::AuthenticationProcessFinished(bool)`
- `GPSTProtocolHandler::SendSachsBikeKey(..., SachsBikeSettingType)`
- `GPSTProtocolHandler::AuthSachsBike(...)`
- `GPSTProtocolHandler::ChangeSachsConnectionKey(...)`
- `GPSTLib::AuthSachsBike(...)`
- `GPSTLib::ChangeSachsConnectionKey(...)`
- `GPSTLib::SetApiKey(char const*)`
- JNI `GPSTProtocolLibWrapper_setAPIKey`
- JNI `GPSTProtocolLibWrapper_authSachsBike`
- JNI `GPSTProtocolLibWrapper_changeSachsConnectionKey`
- `GPSTProtocolAndroidCallback::authenticationRequired()`
- `GPSTUtils::CreateCRC(...)`
- Stringfragment `"publicKey":"`

### Evidenzgrenze

`SetApiKey`/`APIKey` beweist nur, dass das generische SDK einen API-Key-Konfigurationspfad besitzt. Es beweist **nicht**, dass dieser Wert ein BT638-Controller-Unlock-Key ist.

`AuthenticationProcessFinished` beweist, dass das generische SDK Authentifizierung unterstützt. Die benannten Routinen `AuthSachsBike`, `SendSachsBikeKey` und `ChangeSachsConnectionKey` gehören ihrem Namen nach zu einer anderen Protokoll-/Fahrzeugfamilie. Ohne dokumentierte Aufrufkette wird daraus weder ein Sachs-Runtimepfad noch ein BT638-Pfad abgeleitet.

## DEX-Evidenz aus der originalen Base

`classes4.dex` enthält gleichzeitig:

- `EbikeSachsProtocol.kt`
- `SachsAuthHandler.kt`
- `authSachsBike`
- `authSachsBike$blesdk_proRelease`
- `changeSachsConnectionKey`
- `changeSachsConnectionKey$blesdk_proRelease`
- `authenticationRequired`
- `bikeProtocolAuthenticationRequired`
- `GPSTProtocolLibWrapper`
- `setAPIKey`

Die App enthält damit einen echten generischen GPST-Authentifizierungsunterbau, während die konkret benannte Connection-Key-Logik Sachs-spezifisch bezeichnet ist. Das passt zur bereits eingeführten Multi-Vendor-Evidenztrennung: Code in `base.apk` ist nicht automatisch eine VX2-Gear-Funktion, und ein Stringinventar ist keine Runtime-Aufrufkette.

## Was für BT638 derzeit tatsächlich belegt ist

1. Das Zielgerät meldet sich als `BT638`.
2. VMAXDashboard verbindet sich regulär per BLE/GATT.
3. Der echte Live-Pfad nutzt die proprietäre DA1A-Familie, darunter `DA1A1500...` und 15xx-Characteristics.
4. Geschwindigkeit, Odometer, Akku %, Spannung, Strom und weitere Telemetriefelder werden ohne einen von VMAXDashboard ausgeführten oder im Mitschnitt beobachteten BT638-spezifischen Application-Layer-Auth-Schritt empfangen.
5. Der native `GPSTProtocolHandler` enthält zahlreiche READ-Routinen. Ob einzelne zusätzliche READs auf dem BT638 existieren oder antworten, wird ab jetzt durch den Deep-READ-Scanner direkt am Gerät geprüft.

## Neue Prüfregel für Handshake-/Key-Behauptungen

Ein BT638-Handshake gilt erst als belegt, wenn mindestens eine **BT638-spezifische** Evidenz vorliegt, z. B.:

- reproduzierbarer Auth-Request des BT638 während einer echten Verbindung,
- eine BT638-/V-Core-spezifische App-Codekette, die unmittelbar vor Zugriff auf bestimmte GATT-Felder eine Auth-Routine ausführt,
- oder ein beobachtbarer READ-Zugriff, der vor/nach einem legitimen gerätespezifischen Auth-Schritt reproduzierbar seinen Status ändert.

Nicht ausreichend sind:

- ein generischer `authenticationRequired`-String,
- `SetApiKey` ohne gerätespezifische Verbindung,
- Sachs-/Brose-/Hobbywing-/Hyena-Code in derselben Multi-Vendor-APK,
- KI-Aussagen oder Suchmaschinen-Zusammenfassungen,
- ein plausibel wirkender Schlüssel/String ohne Live-Nachweis.

## Sichere weitere Untersuchung

- Alle tatsächlich vorhandenen `PROPERTY_READ`-Characteristics des BT638 inventarisieren und Antwortstatus/Payload speichern.
- Besondere Priorität: Error, SerialNumbers, ErrorString, DebugLog, BatteryInfo/BatteryCell-Kandidaten, Firmware-/Motor-/Sensor-READs, Settings/Stats/Trip und Remote.
- Fehlgeschlagene READs ebenfalls mit GATT-Status erfassen; dadurch wird sichtbar, ob ein Feld wirklich fehlt, nicht lesbar ist oder nur ohne Payload antwortet.
- Keine geratenen Keys, Challenge-Antworten oder fremden Sachs-Verbindungskeys an den VX2 Gear senden.

## Fazit

**Es gibt Authentifizierungscode im generischen Original-SDK – aber bislang keinen belegten geheimen BT638-/VX2-Gear-Application-Layer-Handshake.** Die benannten Connection-Key-Routinen sind Sachs-spezifisch bezeichnet; eine vollständige Runtime-Aufrufkette ist noch nicht dokumentiert. Die weitere Suche erfolgt deshalb über reproduzierbare Originaldatei-/Call-Chain- und echte BT638-GATT-Evidenz, nicht über das Übertragen fremder Vendor-Authentifizierung.

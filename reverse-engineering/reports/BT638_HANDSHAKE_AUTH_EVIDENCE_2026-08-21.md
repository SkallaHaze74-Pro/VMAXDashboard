# BT638 / VX2 Gear – Handshake- und Authentifizierungs-Evidenz 2026-08-21

## Ergebnis

Die Aussage, der **VMAX VX2 Gear / BT638** benötige einen geheimen Hersteller-Handshake bzw. einen „Secret Key“, um tiefere BLE-Daten freizuschalten, ist mit den vorliegenden Originaldateien **nicht belegt**.

Im ausgelieferten generischen GPST-BLE-SDK existiert zwar echte Authentifizierungs-/Key-Logik. Der konkret auffindbare Authentifizierungspfad ist jedoch ausdrücklich **Sachs-Bike-spezifisch**. Er darf daher nicht als BT638-/V-Core-Handshake umetikettiert werden.

Die normale BT638-Telemetrie auf DA1A/15xx wird von VMAXDashboard bereits nach normalem GATT-Connect/Service-Discovery/Notify-Setup empfangen, ohne dass die App einen geratenen Secret-Key-Handshake sendet.

## Verifizierte Originaldateien

- `base.apk` / VMAX Original-App — SHA-256 `5f9ee266672bf7f24c7b45dd35546a46317498a499daeeff0dc1ef121ccdb8af`
- `libble-sdk-native-lib.so` — SHA-256 `6050df512a62edb19279d169c0e416a6674ed036d046f5026c6a6512dafe7760`

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

`AuthenticationProcessFinished` beweist, dass das generische SDK Authentifizierung unterstützt. Die benachbarten konkret benannten Routinen `AuthSachsBike`, `SendSachsBikeKey` und `ChangeSachsConnectionKey` zeigen aber, dass mindestens dieser Auth-Pfad zu einer anderen Protokoll-/Fahrzeugfamilie gehört.

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

Die App enthält damit einen echten generischen GPST-Authentifizierungsunterbau, aber die konkret benannte Connection-Key-Logik ist Sachs-spezifisch. Das passt zur bereits eingeführten Multi-Vendor-Evidenztrennung: Code in `base.apk` ist nicht automatisch eine VX2-Gear-Funktion.

## Was für BT638 derzeit tatsächlich belegt ist

1. Das Zielgerät meldet sich als `BT638`.
2. VMAXDashboard verbindet sich regulär per BLE/GATT.
3. Der echte Live-Pfad nutzt die proprietäre DA1A-Familie, darunter `DA1A1500...` und 15xx-Characteristics.
4. Geschwindigkeit, Odometer, Akku %, Spannung, Strom und weitere Telemetriefelder werden ohne einen geratenen Auth-Key empfangen.
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

**Es gibt Authentifizierungscode im generischen Original-SDK – aber bislang keinen belegten geheimen BT638-/VX2-Gear-Handshake.** Der bisher konkret identifizierte Connection-Key-Pfad ist Sachs-spezifisch. Die weitere Suche erfolgt deshalb über echte BT638-GATT-Evidenz und nicht über das Übertragen fremder Vendor-Authentifizierung.

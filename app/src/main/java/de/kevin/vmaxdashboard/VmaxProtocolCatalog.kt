package de.kevin.vmaxdashboard

enum class KnowledgeLevel(val label: String) {
    CONFIRMED("Bestätigt"),
    STRONG_CANDIDATE("Starker Kandidat"),
    SDK_KNOWN("SDK bekannt"),
    UNSUPPORTED("Nicht unterstützt"),
    UNKNOWN("Noch unbekannt")
}

enum class KnowledgeSource(val label: String) {
    ORIGINAL_SDK("Original SDK"),
    ORIGINAL_APK("Original APK"),
    BT638_CONFIRMED("BT638 bestätigt"),
    LIVE_OBSERVED("Live beobachtet"),
    SAFETY_RULE("Sicherheitsregel")
}

data class ChannelKnowledge(
    val channel: String,
    val title: String,
    val summary: String,
    val level: KnowledgeLevel,
    val sources: Set<KnowledgeSource> = emptySet(),
    val confirmedDetails: List<String> = emptyList(),
    val unknownDetails: List<String> = emptyList(),
    val uiHint: String = "",
    val safetyNote: String = ""
) {
    val meaning: String
        get() = buildString {
            append(summary)
            if (confirmedDetails.isNotEmpty()) {
                append(" Bestätigt: ")
                append(confirmedDetails.joinToString("; "))
                append('.')
            }
            if (unknownDetails.isNotEmpty()) {
                append(" Offen: ")
                append(unknownDetails.joinToString("; "))
                append('.')
            }
            if (uiHint.isNotBlank()) {
                append(" Anzeige: ")
                append(uiHint)
                append('.')
            }
            if (safetyNote.isNotBlank()) {
                append(" Sicherheit: ")
                append(safetyNote)
                append('.')
            }
        }
}

object VmaxProtocolCatalog {
    private val entries = listOf(
        ChannelKnowledge(
            channel = "1501",
            title = "Fahrzeuginformationen",
            summary = "Statischer Informationsblock für Fahrzeug- und Modellangaben mit Referenz aus der Original-Implementierung.",
            level = KnowledgeLevel.SDK_KNOWN,
            sources = setOf(KnowledgeSource.ORIGINAL_SDK, KnowledgeSource.ORIGINAL_APK),
            confirmedDetails = listOf("READ-Block dient als Referenz für Modell- und Identitätsabgleich"),
            unknownDetails = listOf("modellabhängige BT638-Feldbelegung nicht vollständig beschrieben"),
            uiHint = "als Referenz- und Identitätskanal anzeigen"
        ),
        ChannelKnowledge(
            channel = "1502",
            title = "Akkuinformationen",
            summary = "Statischer Akkublock aus der Original-Referenz mit mehreren modellabhängigen Rohfeldern.",
            level = KnowledgeLevel.SDK_KNOWN,
            sources = setOf(KnowledgeSource.ORIGINAL_SDK, KnowledgeSource.ORIGINAL_APK),
            confirmedDetails = listOf("0xFFFF und 0x8000 als nicht verfügbar behandeln"),
            unknownDetails = listOf("konkrete BT638-Feldbelegung je Modell weiter verfeinern"),
            uiHint = "statische Akku-Referenz getrennt von Live-Telemetrie darstellen"
        ),
        ChannelKnowledge(
            channel = "1503",
            title = "Motor-/Controllerinformationen",
            summary = "Statischer Motor- und Controllerblock mit bekannter SDK-Semantik.",
            level = KnowledgeLevel.SDK_KNOWN,
            sources = setOf(KnowledgeSource.ORIGINAL_SDK, KnowledgeSource.ORIGINAL_APK),
            confirmedDetails = listOf("SDK-Semantik ist bekannt"),
            unknownDetails = listOf("BT638-Offsets weiter gegen READ-Antworten prüfen"),
            uiHint = "als Referenzblock und nicht als Live-Kanal behandeln"
        ),
        ChannelKnowledge(
            channel = "1504",
            title = "Firmware-ID",
            summary = "Firmware- und Komponentenkennung als direkte Referenz zwischen Original-Implementierung und BT638.",
            level = KnowledgeLevel.SDK_KNOWN,
            sources = setOf(KnowledgeSource.ORIGINAL_SDK, KnowledgeSource.ORIGINAL_APK),
            confirmedDetails = listOf("READ-Antwort eignet sich als Referenzabgleich"),
            uiHint = "für Versionen und Komponentenidentität nutzen"
        ),
        ChannelKnowledge(
            channel = "1505",
            title = "BikePerformance / Fahrleistung",
            summary = "Live-Fahrleistungsblock mit bestätigter Geschwindigkeit; weitere SDK-Slots bleiben BT638-Kandidaten.",
            level = KnowledgeLevel.CONFIRMED,
            sources = setOf(KnowledgeSource.ORIGINAL_SDK, KnowledgeSource.ORIGINAL_APK, KnowledgeSource.BT638_CONFIRMED, KnowledgeSource.LIVE_OBSERVED),
            confirmedDetails = listOf(
                "Byte 6-7 Geschwindigkeit /10 km/h am BT638 bestätigt"
            ),
            unknownDetails = listOf(
                "Byte 0-1 und 2-3 sind im SDK als Power A/B vorgesehen, beim BT638 aber 1428/1428-mal identisch und semantisch offen",
                "Byte 8-9 RPM/Cadence und Byte 10-11 Restreichweite lieferten bisher nur FFFF",
                "Byte 4-5 Drehmomentrolle am BT638 nicht unabhängig bestätigt"
            ),
            uiHint = "als zentralen Live-Fahrdatenkanal anzeigen"
        ),
        ChannelKnowledge(
            channel = "1506",
            title = "Trip / Kilometer & Betriebszähler",
            summary = "Statistik- und Kilometerkanal mit bestätigtem Kilometerstand und weiteren Prüf-Kandidaten.",
            level = KnowledgeLevel.CONFIRMED,
            sources = setOf(KnowledgeSource.ORIGINAL_SDK, KnowledgeSource.BT638_CONFIRMED, KnowledgeSource.LIVE_OBSERVED),
            confirmedDetails = listOf("Byte 0-3 Kilometerstand /10"),
            unknownDetails = listOf("weitere Zähler gegen Langfahrtverlauf absichern"),
            uiHint = "für Historie und Session-Abgleich nutzen"
        ),
        ChannelKnowledge(
            channel = "1507",
            title = "Stats / Gesamtfahrdaten",
            summary = "Weiterer Statistikblock mit benannter SDK-Bedeutung und noch laufender Offset-Verifikation.",
            level = KnowledgeLevel.SDK_KNOWN,
            sources = setOf(KnowledgeSource.ORIGINAL_SDK, KnowledgeSource.ORIGINAL_APK),
            confirmedDetails = listOf("Rolle als Statistik-/Gesamtfahrdatenblock ist bekannt"),
            unknownDetails = listOf("BT638-Offsets durch wiederholte READ/RX-Vergleiche weiter bestätigen"),
            uiHint = "nicht zu aggressiv interpretieren"
        ),
        ChannelKnowledge(
            channel = "1508",
            title = "Settings / Licht & Fahrstufe",
            summary = "Einstellungsnaher Kanal mit bestätigten Byte-Bedeutungen für Licht und Fahrstufe.",
            level = KnowledgeLevel.CONFIRMED,
            sources = setOf(KnowledgeSource.ORIGINAL_SDK, KnowledgeSource.ORIGINAL_APK, KnowledgeSource.BT638_CONFIRMED),
            confirmedDetails = listOf("Byte 0: Licht 0=AUS/1=AN", "Byte 3: Fahrstufe"),
            unknownDetails = listOf("weitere Bits nur nach eindeutiger Übereinstimmung benennen"),
            uiHint = "bestätigte Zustände direkt anzeigen, Rest defensiv behandeln"
        ),
        ChannelKnowledge(
            channel = "1509",
            title = "BatteryChange / Akku-Livestand",
            summary = "Zentraler BT638-Live-Akkukanal; direkte Leistung bleibt trotz starkem Cross-Field-Abgleich ein Kandidat.",
            level = KnowledgeLevel.CONFIRMED,
            sources = setOf(KnowledgeSource.ORIGINAL_SDK, KnowledgeSource.ORIGINAL_APK, KnowledgeSource.BT638_CONFIRMED, KnowledgeSource.SAFETY_RULE),
            confirmedDetails = listOf(
                "Byte 0-1 Strom mA signed BE",
                "Byte 4 SOC %",
                "Byte 5-6 Spannung mV"
            ),
            unknownDetails = listOf(
                "Byte 2-3 Temperatur und Byte 7-8 zweiter Stromwert folgen dem nativen Parserlayout, sind am BT638 aber noch nicht unabhängig bestätigt",
                "Byte 9-10 direkte Leistung korreliert stark mit |Spannung × Strom|, ist aber nicht unabhängig physikalisch bestätigt",
                "direkte BMS-Herkunft gegenüber Weiterleitung durch V-Core bleibt offen"
            ),
            uiHint = "bestätigte Akkuwerte zeigen und direkte Leistung ausdrücklich als Kandidat markieren",
            safetyNote = "Darf nicht als Licht-, Blinker- oder Schalterkanal umgedeutet werden"
        ),
        ChannelKnowledge(
            channel = "150A",
            title = "MotorUpdate / Motor-Livestand",
            summary = "Im nativen SDK benannter Motor-Liveblock; Feldrollen und Verfügbarkeit sind am BT638 noch offen.",
            level = KnowledgeLevel.SDK_KNOWN,
            sources = setOf(KnowledgeSource.ORIGINAL_SDK, KnowledgeSource.ORIGINAL_APK),
            confirmedDetails = listOf("Das native Parserlayout benennt Slots für Motorstrom, Motorspannung, RPM, Drehmoment und Temperatur"),
            unknownDetails = listOf(
                "keine dieser 150A-Feldrollen ist am BT638 bislang unabhängig bestätigt",
                "0xFFFF-Felder je Modell als nicht verfügbar behandeln"
            ),
            uiHint = "nur als SDK-Layout/Kandidaten zeigen und Platzhalter ausblenden"
        ),
        ChannelKnowledge(
            channel = "150B",
            title = "Motor-/Controllerblock",
            summary = "Beim BT638 beobachtet, bisher aber überwiegend Platzhalterdaten und semantisch offen.",
            level = KnowledgeLevel.UNKNOWN,
            sources = setOf(KnowledgeSource.LIVE_OBSERVED),
            confirmedDetails = listOf("bisher häufig 0xFF-Platzhalter"),
            unknownDetails = listOf("erst bei echten Nicht-Platzhalterdaten neu bewerten"),
            uiHint = "als beobachtet/offen markieren und Platzhalter ausblenden"
        ),
        ChannelKnowledge(
            channel = "150C",
            title = "BatteryCellUpdate / Zell- & Temperaturdaten",
            summary = "Zell- und Temperaturkanal aus der Referenzlogik mit noch offener BT638-Bytezuordnung.",
            level = KnowledgeLevel.SDK_KNOWN,
            sources = setOf(KnowledgeSource.ORIGINAL_SDK, KnowledgeSource.ORIGINAL_APK),
            confirmedDetails = listOf("BatteryCellUpdate, cellIndex, cellVoltage, cellTemp und cellNum sind als Themen bekannt"),
            unknownDetails = listOf("exakte Byte-Offsets erst nach erneuter Verifikation automatisch dekodieren"),
            uiHint = "als fortgeschrittenen Diagnosekanal behandeln"
        ),
        ChannelKnowledge(
            channel = "150D",
            title = "Fahrstatistik Max/Ø",
            summary = "Persistenter Fahrstatistikkanal; kein zweiter Live-Geschwindigkeitskanal.",
            level = KnowledgeLevel.CONFIRMED,
            sources = setOf(KnowledgeSource.BT638_CONFIRMED, KnowledgeSource.LIVE_OBSERVED),
            confirmedDetails = listOf("Byte 0-1 Fahrt-Maximum /10 km/h", "Byte 2-3 Fahrt-Durchschnitt /10 km/h"),
            unknownDetails = listOf("weitere Statistikfelder ab Byte 4 weiter vergleichen"),
            uiHint = "getrennt vom Live-Tempo als gespeicherte Max/Ø-Statistik anzeigen"
        ),
        ChannelKnowledge(
            channel = "1514",
            title = "Error / Fehlercodes",
            summary = "Numerischer Fehler- und Warnkanal mit direkter Referenzbedeutung.",
            level = KnowledgeLevel.SDK_KNOWN,
            sources = setOf(KnowledgeSource.ORIGINAL_SDK, KnowledgeSource.ORIGINAL_APK),
            confirmedDetails = listOf("READ/RX als Fehlerstatus behandeln"),
            uiHint = "nicht als generischen Lernkanal behandeln"
        ),
        ChannelKnowledge(
            channel = "1516",
            title = "SerialNumbers / Seriennummern",
            summary = "Statischer Identifikationskanal für Serien- und Komponentenkennungen.",
            level = KnowledgeLevel.SDK_KNOWN,
            sources = setOf(KnowledgeSource.ORIGINAL_SDK, KnowledgeSource.ORIGINAL_APK),
            confirmedDetails = listOf("Das native GPST-SDK besitzt einen ReadCharacteristicSerialNumbers-Pfad"),
            unknownDetails = listOf("Existenz, READ-Berechtigung und Payload auf dem BT638 müssen erst der echte Deep READ zeigen"),
            uiHint = "exakt nur lokal sichern; bei öffentlichem Export redigieren"
        ),
        ChannelKnowledge(
            channel = "1517",
            title = "ErrorString / Fehlertext",
            summary = "Textuelle Fehler- oder Statusmeldung aus der Referenzlogik.",
            level = KnowledgeLevel.SDK_KNOWN,
            sources = setOf(KnowledgeSource.ORIGINAL_SDK, KnowledgeSource.ORIGINAL_APK),
            uiHint = "als Klartext-Hinweis anzeigen"
        ),
        ChannelKnowledge(
            channel = "1518",
            title = "Debug / Diagnoseprotokoll",
            summary = "Diagnose- und Debug-Kanal des Controllers getrennt von normaler Fahrtelemetrie.",
            level = KnowledgeLevel.SDK_KNOWN,
            sources = setOf(KnowledgeSource.ORIGINAL_SDK, KnowledgeSource.ORIGINAL_APK),
            uiHint = "separat vom normalen Telemetrie-Lernen darstellen"
        ),
        ChannelKnowledge(
            channel = "151D",
            title = "Status-/Ereignisdaten",
            summary = "Dynamischer Statuskanal mit noch offener Bit- und Bytebedeutung.",
            level = KnowledgeLevel.UNKNOWN,
            sources = setOf(KnowledgeSource.LIVE_OBSERVED),
            unknownDetails = listOf("erst nach Ausschluss aller bekannten Telemetrie- und Settings-Felder lernen"),
            uiHint = "vorsichtig und als experimentell kennzeichnen"
        ),
        ChannelKnowledge(
            channel = "1E03",
            title = "Funkfernbedienung",
            summary = "Statusdaten einer drahtlosen Lenker- oder Zubehörfernbedienung.",
            level = KnowledgeLevel.SDK_KNOWN,
            sources = setOf(KnowledgeSource.ORIGINAL_SDK, KnowledgeSource.ORIGINAL_APK),
            uiHint = "nur anzeigen, wenn der Kanal real vorhanden ist"
        ),
        ChannelKnowledge(
            channel = "1E04",
            title = "Fernbedienungsaktion",
            summary = "Aktionskanal einer drahtlosen Lenker- oder Zubehörfernbedienung.",
            level = KnowledgeLevel.SDK_KNOWN,
            sources = setOf(KnowledgeSource.ORIGINAL_SDK, KnowledgeSource.ORIGINAL_APK),
            uiHint = "eher als Zubehör-Event denn als Fahrtelemetrie behandeln"
        ),
        ChannelKnowledge(
            channel = "160C",
            title = "Motor-Tuning Rückmeldung",
            summary = "Rückmeldekanal für Motor-Tuning, in der App aber strikt getrennt von der Decoder-AI behandelt.",
            level = KnowledgeLevel.SDK_KNOWN,
            sources = setOf(KnowledgeSource.ORIGINAL_SDK, KnowledgeSource.ORIGINAL_APK, KnowledgeSource.SAFETY_RULE),
            confirmedDetails = listOf("Decoder-AI liest nur mit"),
            uiHint = "klar von normaler Telemetrie absetzen",
            safetyNote = "nicht automatisch von Lernlogik beschreiben lassen"
        ),
        ChannelKnowledge(
            channel = "160D",
            title = "Motor-Tuning Schreiben",
            summary = "Schreibkanal für Motor-Tuning mit expliziter Sicherheitsgrenze in der App.",
            level = KnowledgeLevel.SDK_KNOWN,
            sources = setOf(KnowledgeSource.ORIGINAL_SDK, KnowledgeSource.ORIGINAL_APK, KnowledgeSource.SAFETY_RULE),
            confirmedDetails = listOf("Write-Kanal für Tuning-Funktionen"),
            uiHint = "in UI klar als Schreibkanal markieren",
            safetyNote = "Decoder-AI darf niemals automatisch auf WRITE-Kanäle schreiben"
        )
    ).associateBy { it.channel }

    fun get(channel: String): ChannelKnowledge = entries[channel]
        ?: ChannelKnowledge(
            channel = channel,
            title = "Unbekannter Kanal",
            summary = "Keine feste Referenzzuordnung vorhanden.",
            level = KnowledgeLevel.UNKNOWN,
            sources = setOf(KnowledgeSource.LIVE_OBSERVED),
            unknownDetails = listOf("erst nach Ausschluss bekannter SDK-Felder statistisch lernen"),
            uiHint = "neutral und ohne harte Aussage anzeigen"
        )
}

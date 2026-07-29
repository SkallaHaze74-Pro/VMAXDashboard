package de.kevin.vmaxdashboard

enum class KnowledgeLevel(val label: String) {
    CONFIRMED("Bestätigt"),
    STRONG_CANDIDATE("Starker Kandidat"),
    UNKNOWN("Noch unbekannt")
}

data class ChannelKnowledge(
    val channel: String,
    val title: String,
    val meaning: String,
    val level: KnowledgeLevel
)

object VmaxProtocolCatalog {
    private val entries = listOf(
        ChannelKnowledge("1501", "Fahrzeuginformationen", "Statische Fahrzeug- und Modellinformationen.", KnowledgeLevel.CONFIRMED),
        ChannelKnowledge("1502", "Akkuinformationen", "Statische Akku-, Kapazitäts- und Zustandsinformationen.", KnowledgeLevel.CONFIRMED),
        ChannelKnowledge("1503", "Motorinformationen", "Statische Motor- und Controllerinformationen.", KnowledgeLevel.CONFIRMED),
        ChannelKnowledge("1504", "Firmware-ID", "Firmware- und Komponentenkennung.", KnowledgeLevel.CONFIRMED),
        ChannelKnowledge("1505", "Fahrleistung", "Leistung, Drehmoment, Geschwindigkeit, Drehzahl und Streckenfeld; Geschwindigkeit liegt in Byte 6-7 als km/h ×10.", KnowledgeLevel.CONFIRMED),
        ChannelKnowledge("1506", "Tripdaten", "Mehrteiliger Trip-/Zeitdatenblock; genaue VR2-Skalierung wird noch mit dem Display verglichen.", KnowledgeLevel.CONFIRMED),
        ChannelKnowledge("1507", "Gesamtfahrdaten", "Gesamtfahr- und Statistikdaten; Kandidat für den Kilometerstand.", KnowledgeLevel.CONFIRMED),
        ChannelKnowledge("1508", "Licht & Fahrstufe", "Byte 0 ist LightState, Byte 3 ist AssistLevelChange; weitere Bits werden im Standtest untersucht.", KnowledgeLevel.CONFIRMED),
        ChannelKnowledge("1509", "Akku-Livedaten", "Strom, Akkutemperatur, Prozent, Spannung und direkte Leistung.", KnowledgeLevel.CONFIRMED),
        ChannelKnowledge("150A", "Motor-Livedaten", "Motorstrom, Motorspannung, Drehzahl, Drehmoment und Motortemperatur.", KnowledgeLevel.CONFIRMED),
        ChannelKnowledge("150B", "Motorinformationen", "Weiterer Motor-/Controllerblock; genaue Struktur noch offen.", KnowledgeLevel.STRONG_CANDIDATE),
        ChannelKnowledge("150C", "Zell- & Temperaturdaten", "Zell-/Sensorindex, Zellspannung und bis zu drei Temperaturwerte.", KnowledgeLevel.CONFIRMED),
        ChannelKnowledge("150D", "Statistik", "Statistik-/Gesamtwerte; genaue VR2-Feldbelegung wird noch bestätigt.", KnowledgeLevel.STRONG_CANDIDATE),
        ChannelKnowledge("1514", "Fehlercodes", "Numerische Fehler- und Warncodes.", KnowledgeLevel.CONFIRMED),
        ChannelKnowledge("1516", "Seriennummern", "Serien- und Komponentenkennungen.", KnowledgeLevel.CONFIRMED),
        ChannelKnowledge("1517", "Fehlertext", "Textuelle Fehler- oder Statusmeldung.", KnowledgeLevel.CONFIRMED),
        ChannelKnowledge("1518", "Debug-Protokoll", "Debug- und Diagnosemeldungen des Controllers.", KnowledgeLevel.CONFIRMED),
        ChannelKnowledge("151D", "Status-/Ereignisdaten", "Dynamischer Statuskanal; wird automatisch analysiert.", KnowledgeLevel.UNKNOWN),
        ChannelKnowledge("1E03", "Funkfernbedienung", "Statusdaten einer drahtlosen Lenker- oder Zubehörfernbedienung.", KnowledgeLevel.CONFIRMED),
        ChannelKnowledge("1E04", "Fernbedienungsaktion", "Aktionen einer drahtlosen Lenker- oder Zubehörfernbedienung.", KnowledgeLevel.CONFIRMED),
        ChannelKnowledge("160C", "Motor-Tuning Rückmeldung", "Profilrahmen FD … FE und Bestätigung nach einem Schreibvorgang.", KnowledgeLevel.CONFIRMED),
        ChannelKnowledge("160D", "Motor-Tuning Schreiben", "Schreibkanal für Profilindex und Motorparameter.", KnowledgeLevel.CONFIRMED)
    ).associateBy { it.channel }

    fun get(channel: String): ChannelKnowledge = entries[channel]
        ?: ChannelKnowledge(channel, "Unbekannter Kanal", "Noch keine feste Bedeutung. Rohdaten werden vollständig gespeichert.", KnowledgeLevel.UNKNOWN)
}

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
        ChannelKnowledge("1502", "System-/Statusdaten", "Wiederkehrender Statusblock; genaue Felder werden noch zugeordnet.", KnowledgeLevel.STRONG_CANDIDATE),
        ChannelKnowledge("1505", "Sensoren", "Sensorwerte, wahrscheinlich Temperatur-, Schalter- oder Zubehörstatus.", KnowledgeLevel.STRONG_CANDIDATE),
        ChannelKnowledge("1506", "Fahr-/Systemdaten", "Live- oder Systemdaten; genaue Bytebelegung noch offen.", KnowledgeLevel.STRONG_CANDIDATE),
        ChannelKnowledge("1508", "Live-Daten", "Dynamischer Datenkanal; wird automatisch auf Änderungen untersucht.", KnowledgeLevel.UNKNOWN),
        ChannelKnowledge("1509", "Akkuänderung", "Akkustand/Änderungsdaten. Byte 4 wird als Prozentwert geprüft.", KnowledgeLevel.CONFIRMED),
        ChannelKnowledge("150A", "Motor-Livedaten", "Motorbezogene Livewerte, wahrscheinlich Geschwindigkeit/Leistung/Strom.", KnowledgeLevel.STRONG_CANDIDATE),
        ChannelKnowledge("150B", "Motorinformationen", "Motor-/Controllerinformationen; genaue Struktur noch offen.", KnowledgeLevel.STRONG_CANDIDATE),
        ChannelKnowledge("150C", "Fahrt-/Tripdaten", "Wahrscheinlicher Trip- oder Fahrdatenblock.", KnowledgeLevel.STRONG_CANDIDATE),
        ChannelKnowledge("150D", "Statistik", "Statistik-/Gesamtwerte, wahrscheinlich Strecke oder Betriebsdaten.", KnowledgeLevel.STRONG_CANDIDATE),
        ChannelKnowledge("1514", "Ereignis/Zubehör", "Notify-only Ereigniskanal, geeignet für Taster/Zubehörstatus.", KnowledgeLevel.UNKNOWN),
        ChannelKnowledge("1515", "Ereignis/Zubehör", "Notify-only Ereigniskanal, geeignet für Taster/Zubehörstatus.", KnowledgeLevel.UNKNOWN),
        ChannelKnowledge("1516", "Ereignis/Zubehör", "Notify-only Ereigniskanal, geeignet für Taster/Zubehörstatus.", KnowledgeLevel.UNKNOWN),
        ChannelKnowledge("1517", "Ereignis/Zubehör", "Notify-only Ereigniskanal, geeignet für Taster/Zubehörstatus.", KnowledgeLevel.UNKNOWN),
        ChannelKnowledge("1518", "Ereignis/Zubehör", "Notify-only Ereigniskanal, geeignet für Taster/Zubehörstatus.", KnowledgeLevel.UNKNOWN),
        ChannelKnowledge("151D", "Status-/Ereignisdaten", "Dynamischer Statuskanal; wird automatisch analysiert.", KnowledgeLevel.UNKNOWN)
    ).associateBy { it.channel }

    fun get(channel: String): ChannelKnowledge = entries[channel]
        ?: ChannelKnowledge(channel, "Unbekannter Kanal", "Noch keine feste Bedeutung. Rohdaten werden vollständig gespeichert.", KnowledgeLevel.UNKNOWN)
}

package de.kevin.vmaxdashboard

enum class KnowledgeLevel(val label: String) {
    CONFIRMED("Bestätigt"),
    STRONG_CANDIDATE("Starker Kandidat"),
    SDK_KNOWN("SDK bekannt"),
    UNSUPPORTED("Nicht unterstützt"),
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
        ChannelKnowledge("1501", "Fahrzeuginformationen", "Statischer Fahrzeug- und Modellblock. Wird bei vorhandener READ-Berechtigung automatisch ausgelesen.", KnowledgeLevel.SDK_KNOWN),
        ChannelKnowledge("1502", "Akkuinformationen", "BT638 sendet einen statischen 16-Byte-Block. 0xFFFF bedeutet nicht verfügbar; zwei beobachtete RAW-Felder enthalten 18200. Einheit noch offen.", KnowledgeLevel.STRONG_CANDIDATE),
        ChannelKnowledge("1503", "Motor-/Controllerinformationen", "Statischer Motor- und Controllerblock. Feldbelegung aus dem SDK bekannt, beim BT638 noch nicht vollständig beobachtet.", KnowledgeLevel.SDK_KNOWN),
        ChannelKnowledge("1504", "Firmware-ID", "Firmware- und Komponentenkennung; wird bei READ-Berechtigung automatisch abgefragt.", KnowledgeLevel.SDK_KNOWN),
        ChannelKnowledge("1505", "Fahrleistung", "BT638 bestätigt: Byte 6-7 Geschwindigkeit ×10. Byte 0-1 und 2-3 sind zwei stark belastungsabhängige Fahrleistungs-RAW-Werte.", KnowledgeLevel.CONFIRMED),
        ChannelKnowledge("1506", "Kilometer & Betriebszähler", "BT638 bestätigt: Byte 0-3 Kilometerstand ×10. Byte 4-7 ist ein Betriebs-/Fahrzeitzähler RAW; Skalierung noch offen.", KnowledgeLevel.CONFIRMED),
        ChannelKnowledge("1507", "Gesamtfahrdaten", "Weiterer Statistikblock; genaue BT638-Feldbelegung noch offen.", KnowledgeLevel.STRONG_CANDIDATE),
        ChannelKnowledge("1508", "Licht & Fahrstufe", "BT638 bestätigt: Byte 0 ist 0=Licht aus und 1=Licht an; Byte 3 ist die Fahrstufe. Blinker bleiben offen.", KnowledgeLevel.CONFIRMED),
        ChannelKnowledge("1509", "Akku-Livedaten", "BT638 bestätigt: Strom mA, Akkustand %, Spannung mV und direkte Leistung W. Temperaturfeld ist beim BT638 häufig 0xFFFF.", KnowledgeLevel.CONFIRMED),
        ChannelKnowledge("150A", "Motor-Livedaten", "Beim BT638 ist Byte 0-1 ein dynamischer Motorstrom-/Last-Kandidat. Die übrigen Felder wurden bisher überwiegend als 0xFFFF geliefert.", KnowledgeLevel.STRONG_CANDIDATE),
        ChannelKnowledge("150B", "Motor-/Controllerblock", "Beim BT638 bisher ausschließlich 0xFF-Platzhalter empfangen und daher aktuell nicht unterstützt.", KnowledgeLevel.UNSUPPORTED),
        ChannelKnowledge("150C", "Zell- & Temperaturdaten", "Allgemeine GPST-SDK-Struktur für Zellspannung und Temperaturen; in den BT638-Messfahrten nicht beobachtet.", KnowledgeLevel.SDK_KNOWN),
        ChannelKnowledge("150D", "Zweite Fahrstatistik", "BT638 bestätigt: Byte 0-1 liefert ebenfalls Geschwindigkeit ×10. Byte 2-3 ist ein zweiter dynamischer Statistik-/Geschwindigkeitswert, Bedeutung noch offen.", KnowledgeLevel.CONFIRMED),
        ChannelKnowledge("1514", "Fehlercodes", "Numerische Fehler- und Warncodes; bei READ-Berechtigung automatisch abfragen.", KnowledgeLevel.SDK_KNOWN),
        ChannelKnowledge("1516", "Seriennummern", "Serien- und Komponentenkennungen; bei READ-Berechtigung automatisch abfragen.", KnowledgeLevel.SDK_KNOWN),
        ChannelKnowledge("1517", "Fehlertext", "Textuelle Fehler- oder Statusmeldung.", KnowledgeLevel.SDK_KNOWN),
        ChannelKnowledge("1518", "Debug-Protokoll", "Debug- und Diagnosemeldungen des Controllers.", KnowledgeLevel.SDK_KNOWN),
        ChannelKnowledge("151D", "Status-/Ereignisdaten", "Dynamischer Statuskanal; Rohdaten werden automatisch aufgezeichnet und analysiert.", KnowledgeLevel.UNKNOWN),
        ChannelKnowledge("1E03", "Funkfernbedienung", "Statusdaten einer drahtlosen Lenker- oder Zubehörfernbedienung.", KnowledgeLevel.SDK_KNOWN),
        ChannelKnowledge("1E04", "Fernbedienungsaktion", "Aktionen einer drahtlosen Lenker- oder Zubehörfernbedienung.", KnowledgeLevel.SDK_KNOWN),
        ChannelKnowledge("160C", "Motor-Tuning Rückmeldung", "SDK-Kanal; beim BT638 wegen fehlender/gesperrter Characteristic nicht lesbar.", KnowledgeLevel.SDK_KNOWN),
        ChannelKnowledge("160D", "Motor-Tuning Schreiben", "SDK-Schreibkanal; im aktuellen Fahrdaten-Update deaktiviert und nicht verwendet.", KnowledgeLevel.SDK_KNOWN)
    ).associateBy { it.channel }

    fun get(channel: String): ChannelKnowledge = entries[channel]
        ?: ChannelKnowledge(channel, "Unbekannter Kanal", "Noch keine feste Bedeutung. Rohdaten werden vollständig gespeichert.", KnowledgeLevel.UNKNOWN)
}

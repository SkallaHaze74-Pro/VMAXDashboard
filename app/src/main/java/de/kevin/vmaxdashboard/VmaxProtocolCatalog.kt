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
        ChannelKnowledge("1501", "Fahrzeuginformationen", "libble: Fahrzeug-/Modellinformationen. Statischer READ-Block; wird mit der echten BT638-Antwort abgeglichen.", KnowledgeLevel.SDK_KNOWN),
        ChannelKnowledge("1502", "Akkuinformationen", "libble: BatteryInfo. Statischer Akkublock; 0xFFFF/0x8000 gelten als nicht verfügbar. BT638 liefert hier mehrere modellabhängige RAW-Felder.", KnowledgeLevel.SDK_KNOWN),
        ChannelKnowledge("1503", "Motor-/Controllerinformationen", "libble: MotorInfo. Statischer Motor-/Controllerblock; SDK-Semantik bekannt, BT638-Feldbelegung wird gegen READ-Antworten verifiziert.", KnowledgeLevel.SDK_KNOWN),
        ChannelKnowledge("1504", "Firmware-ID", "libble: FirmwareID. Firmware- und Komponentenkennung; READ-Antwort dient als direkte SDK↔BT638-Referenz.", KnowledgeLevel.SDK_KNOWN),
        ChannelKnowledge("1505", "BikePerformance / Fahrleistung", "libble Native-Parser: Byte 0-1 Leistung A /10 W, 2-3 Leistung B /10 W, 4-5 Drehmoment /100 Nm, 6-7 Geschwindigkeit /10 km/h, 8-9 RPM/Cadence, 10-11 Distanz-/Wegfeld. BT638-Tempo ist bestätigt.", KnowledgeLevel.CONFIRMED),
        ChannelKnowledge("1506", "Trip / Kilometer & Betriebszähler", "libble: Trip/TotalTrip/Stats-Familie. BT638 bestätigt Byte 0-3 Kilometerstand /10; weitere Zähler werden gegen SDK- und Langfahrtverlauf geprüft.", KnowledgeLevel.CONFIRMED),
        ChannelKnowledge("1507", "Stats / Gesamtfahrdaten", "libble: weiterer Statistik-/Gesamtfahrdatenblock. Bedeutung ist SDK-seitig benannt; BT638-Offsets werden durch wiederholte READ/RX-Vergleiche bestätigt.", KnowledgeLevel.SDK_KNOWN),
        ChannelKnowledge("1508", "Settings / Licht & Fahrstufe", "BT638 bestätigt Byte 0: Licht 0=AUS/1=AN und Byte 3: Fahrstufe. Andere Bits/Bytes werden nur dann benannt, wenn SDK-Semantik und RX-Verhalten übereinstimmen.", KnowledgeLevel.CONFIRMED),
        ChannelKnowledge("1509", "BatteryChange / Akku-Livestand", "libble Native-Parser: Byte 0-1 Strom mA (signed BE), 2-3 Akkutemperatur /10 °C, Byte 4 SOC %, 5-6 Spannung mV, 7-8 zweiter Stromwert mA, 9-10 direkte Leistung W. Diese Bytes sind Ground Truth und dürfen nicht als Schalter/Blinker/Licht umgedeutet werden.", KnowledgeLevel.CONFIRMED),
        ChannelKnowledge("150A", "MotorUpdate / Motor-Livestand", "libble Native-Parser: Motorstrom mA, Motorspannung mV, RPM, Drehmoment /100 Nm und Motortemperatur /10 °C. Beim BT638 sind nicht unterstützte Felder häufig 0xFFFF; vorhandene Felder werden live gegen Plausibilität geprüft.", KnowledgeLevel.SDK_KNOWN),
        ChannelKnowledge("150B", "Motor-/Controllerblock", "Beim BT638 bisher überwiegend 0xFF-Platzhalter. Bleibt nicht unterstützt, bis echte Nicht-Platzhalterdaten wiederholt beobachtet werden.", KnowledgeLevel.UNSUPPORTED),
        ChannelKnowledge("150C", "BatteryCellUpdate / Zell- & Temperaturdaten", "libble benennt BatteryCellUpdate, cellIndex, cellVoltage, cellTemp, cellNum sowie Zell-Drift-Warnungen. BT638-Präsenz wird aufgezeichnet; exakte Byte-Offsets werden erst nach erneuter Verifikation automatisch dekodiert.", KnowledgeLevel.SDK_KNOWN),
        ChannelKnowledge("150D", "Zweite Fahrstatistik", "BT638 bestätigt Byte 0-1 als zweite Geschwindigkeit /10 km/h. Byte 2-3 bleibt Statistik-/Wegkandidat und wird gegen 1505/1506/Trip-Verlauf verglichen.", KnowledgeLevel.CONFIRMED),
        ChannelKnowledge("1514", "Error / Fehlercodes", "libble: Error/ErrorCodes. Numerische Fehler- und Warncodes; READ/RX wird direkt als Fehlerstatus behandelt, nicht als generischer Lernkandidat.", KnowledgeLevel.SDK_KNOWN),
        ChannelKnowledge("1516", "SerialNumbers / Seriennummern", "libble: SerialNumbers und Komponentenkennungen. Statische Identifikation; bei READ-Berechtigung automatisch auslesen.", KnowledgeLevel.SDK_KNOWN),
        ChannelKnowledge("1517", "ErrorString / Fehlertext", "libble: textuelle Fehler-/Statusmeldung.", KnowledgeLevel.SDK_KNOWN),
        ChannelKnowledge("1518", "Debug / Diagnoseprotokoll", "libble: Debug-/Diagnosemeldungen des Controllers; separat vom normalen Telemetrie-Lernen behandeln.", KnowledgeLevel.SDK_KNOWN),
        ChannelKnowledge("151D", "Status-/Ereignisdaten", "Dynamischer Statuskanal. Unbekannte Bits/Bytes werden erst nach Ausschluss aller libble-bekannten Telemetrie- und Settings-Felder gelernt.", KnowledgeLevel.UNKNOWN),
        ChannelKnowledge("1E03", "Funkfernbedienung", "SDK: Statusdaten einer drahtlosen Lenker- oder Zubehörfernbedienung.", KnowledgeLevel.SDK_KNOWN),
        ChannelKnowledge("1E04", "Fernbedienungsaktion", "SDK: Aktionen einer drahtlosen Lenker- oder Zubehörfernbedienung.", KnowledgeLevel.SDK_KNOWN),
        ChannelKnowledge("160C", "Motor-Tuning Rückmeldung", "libble: MotorTuning-Rückmeldung. Bleibt strikt getrennt von der Decoder-AI; KI nutzt diesen Kanal nur lesend.", KnowledgeLevel.SDK_KNOWN),
        ChannelKnowledge("160D", "Motor-Tuning Schreiben", "libble: MotorTuning-Schreibkanal. Decoder-AI darf niemals automatisch auf diesen oder andere WRITE-Kanäle schreiben.", KnowledgeLevel.SDK_KNOWN)
    ).associateBy { it.channel }

    fun get(channel: String): ChannelKnowledge = entries[channel]
        ?: ChannelKnowledge(channel, "Unbekannter Kanal", "Keine feste libble-Zuordnung. Erst nach Ausschluss bekannter SDK-Felder statistisch lernen.", KnowledgeLevel.UNKNOWN)
}

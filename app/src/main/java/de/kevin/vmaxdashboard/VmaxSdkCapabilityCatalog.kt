package de.kevin.vmaxdashboard

/**
 * Static knowledge imported from the supplied VMAX V125345 APK and its exact
 * ARM64 native libraries. This catalog never enables writes; it only labels
 * discovered GATT services/characteristics and keeps SDK evidence separate
 * from fields confirmed on the user's BT638 recordings.
 */
enum class CapabilityEvidence(val label: String) {
    BT638_CONFIRMED("BT638 bestätigt"),
    BT638_OBSERVED("BT638 beobachtet"),
    SDK_CONFIRMED("SDK vorhanden"),
    BLUETOOTH_STANDARD("Bluetooth-Standard"),
    UNKNOWN("Unbekannt")
}

data class VmaxGattKnowledge(
    val evidence: CapabilityEvidence,
    val family: String,
    val meaning: String,
    val sources: List<String> = emptyList(),
    val confirmedDetails: List<String> = emptyList(),
    val unknownDetails: List<String> = emptyList(),
    val uiHint: String = "",
    val safetyNote: String = ""
)

object VmaxSdkCapabilityCatalog {
    private val bt638Confirmed = mapOf(
        "1505" to VmaxGattKnowledge(
            evidence = CapabilityEvidence.BT638_CONFIRMED,
            family = family("1505"),
            meaning = "Live-Fahrdatenkanal mit bestätigter Geschwindigkeitssemantik.",
            sources = listOf("Original SDK", "Original APK", "BT638 bestätigt", "Live beobachtet"),
            confirmedDetails = listOf("Geschwindigkeit Byte 6-7 bestätigt"),
            unknownDetails = listOf("weitere Leistungs- und Distanzfelder weiter absichern"),
            uiHint = "prominent als Live-Fahrdaten anzeigen"
        ),
        "1506" to VmaxGattKnowledge(
            evidence = CapabilityEvidence.BT638_CONFIRMED,
            family = family("1506"),
            meaning = "Statistik- und Kilometerkanal mit bestätigtem Odometer-Feld.",
            sources = listOf("Original SDK", "BT638 bestätigt", "Live beobachtet"),
            confirmedDetails = listOf("Kilometerstand Byte 0-3 bestätigt"),
            unknownDetails = listOf("weitere Betriebszähler im Verlauf validieren"),
            uiHint = "für Session- und Fahrhistorie nutzen"
        ),
        "1508" to VmaxGattKnowledge(
            evidence = CapabilityEvidence.BT638_CONFIRMED,
            family = family("1508"),
            meaning = "Settings-naher Kanal mit bestätigten Zuständen für Licht und Fahrmodus.",
            sources = listOf("Original SDK", "Original APK", "BT638 bestätigt"),
            confirmedDetails = listOf("Licht Byte 0 bestätigt", "ECO/SPORT Byte 3 bestätigt"),
            unknownDetails = listOf("weitere Bits nur bei klarer Übereinstimmung benennen"),
            uiHint = "bestätigte Schaltzustände direkt anzeigen"
        ),
        "1509" to VmaxGattKnowledge(
            evidence = CapabilityEvidence.BT638_CONFIRMED,
            family = family("1509"),
            meaning = "Zentraler Akku-Livekanal mit bestätigten Strom-, Spannungs- und Leistungswerten.",
            sources = listOf("Original SDK", "Original APK", "BT638 bestätigt", "Sicherheitsregel"),
            confirmedDetails = listOf("Akku, Spannung, Strom und direkte Leistung bestätigt"),
            uiHint = "als Ground-Truth-Akkukanal hervorheben",
            safetyNote = "nicht als Licht-, Blinker- oder Schalterkanal uminterpretieren"
        ),
        "150D" to VmaxGattKnowledge(
            evidence = CapabilityEvidence.BT638_CONFIRMED,
            family = family("150D"),
            meaning = "Persistente Fahrstatistik mit Maximum und Durchschnitt; kein Live-Tempo.",
            sources = listOf("BT638 bestätigt", "Live beobachtet"),
            confirmedDetails = listOf("Byte 0-1 Maximum /10 km/h", "Byte 2-3 Durchschnitt /10 km/h"),
            unknownDetails = listOf("weitere Statistikfelder ab Byte 4 weiter untersuchen"),
            uiHint = "als gespeicherte Max/Ø-Fahrstatistik darstellen"
        )
    )

    private val bt638Observed = mapOf(
        "1501" to VmaxGattKnowledge(CapabilityEvidence.BT638_OBSERVED, family("1501"), "Am BT638 entdeckt; statischer Fahrzeug-/Identitätsblock.", listOf("BT638 beobachtet", "Original SDK", "Original APK"), unknownDetails = listOf("modellabhängige Feldbelegung weiter präzisieren"), uiHint = "als Referenzblock darstellen"),
        "1502" to VmaxGattKnowledge(CapabilityEvidence.BT638_OBSERVED, family("1502"), "Statischer Akku-/Controllerblock mit offener Detailbelegung.", listOf("BT638 beobachtet", "Original SDK", "Original APK"), unknownDetails = listOf("Rohfelder je Modell weiter absichern"), uiHint = "nicht mit Live-Telemetrie verwechseln"),
        "1503" to VmaxGattKnowledge(CapabilityEvidence.BT638_OBSERVED, family("1503"), "Statischer Motor-/Controllerblock mit offener BT638-Zuordnung.", listOf("BT638 beobachtet", "Original SDK", "Original APK"), unknownDetails = listOf("Offsets gegen READ-Antworten prüfen"), uiHint = "als Referenzkanal behandeln"),
        "1504" to VmaxGattKnowledge(CapabilityEvidence.BT638_OBSERVED, family("1504"), "Firmware- und Komponentenkennung im BT638 beobachtet.", listOf("BT638 beobachtet", "Original SDK", "Original APK"), uiHint = "für Versionsabgleich nutzen"),
        "1507" to VmaxGattKnowledge(CapabilityEvidence.BT638_OBSERVED, family("1507"), "Statistikblock im BT638 beobachtet, Bedeutung im Detail noch offen.", listOf("BT638 beobachtet", "Original SDK"), unknownDetails = listOf("Offsets mit RX-/READ-Vergleich schärfen"), uiHint = "defensiv anzeigen"),
        "150A" to VmaxGattKnowledge(CapabilityEvidence.BT638_OBSERVED, family("150A"), "Dynamischer Motor-/Lastkanal beobachtet.", listOf("BT638 beobachtet", "Original SDK", "Original APK"), confirmedDetails = listOf("Motor- und Lastbezug aus Referenz bekannt"), unknownDetails = listOf("modellabhängige Feldverfügbarkeit beachten"), uiHint = "verfügbare Live-Felder priorisieren"),
        "150B" to VmaxGattKnowledge(CapabilityEvidence.BT638_OBSERVED, family("150B"), "Bisher überwiegend Platzhalterdaten.", listOf("BT638 beobachtet"), confirmedDetails = listOf("bisher überwiegend FF-Platzhalter"), unknownDetails = listOf("erst bei echten Daten neu bewerten"), uiHint = "als kaum nutzbar markieren"),
        "150C" to VmaxGattKnowledge(CapabilityEvidence.BT638_OBSERVED, family("150C"), "Zell- und Temperaturbezug im SDK plausibel, BT638-Details offen.", listOf("BT638 beobachtet", "Original SDK", "Original APK"), unknownDetails = listOf("exakte Byte-Offsets erst nach neuer Verifikation"), uiHint = "als erweiterten Diagnosekanal führen"),
        "150E" to VmaxGattKnowledge(CapabilityEvidence.BT638_OBSERVED, family("150E"), "Am BT638 entdeckt; Bedeutung offen.", listOf("BT638 beobachtet"), unknownDetails = listOf("noch keine belastbare Zuordnung"), uiHint = "neutral anzeigen"),
        "150F" to VmaxGattKnowledge(CapabilityEvidence.BT638_OBSERVED, family("150F"), "Am BT638 entdeckt; Bedeutung offen.", listOf("BT638 beobachtet"), unknownDetails = listOf("noch keine belastbare Zuordnung"), uiHint = "neutral anzeigen"),
        "1510" to VmaxGattKnowledge(CapabilityEvidence.BT638_OBSERVED, family("1510"), "Am BT638 entdeckt; Bedeutung offen.", listOf("BT638 beobachtet"), unknownDetails = listOf("noch keine belastbare Zuordnung"), uiHint = "neutral anzeigen"),
        "1511" to VmaxGattKnowledge(CapabilityEvidence.BT638_OBSERVED, family("1511"), "Am BT638 entdeckt; Bedeutung offen.", listOf("BT638 beobachtet"), unknownDetails = listOf("noch keine belastbare Zuordnung"), uiHint = "neutral anzeigen"),
        "1512" to VmaxGattKnowledge(CapabilityEvidence.BT638_OBSERVED, family("1512"), "Am BT638 entdeckt; Bedeutung offen.", listOf("BT638 beobachtet"), unknownDetails = listOf("noch keine belastbare Zuordnung"), uiHint = "neutral anzeigen"),
        "1513" to VmaxGattKnowledge(CapabilityEvidence.BT638_OBSERVED, family("1513"), "Am BT638 entdeckt; Bedeutung offen.", listOf("BT638 beobachtet"), unknownDetails = listOf("noch keine belastbare Zuordnung"), uiHint = "neutral anzeigen"),
        "151C" to VmaxGattKnowledge(CapabilityEvidence.BT638_OBSERVED, family("151C"), "Am BT638 entdeckt; Bedeutung offen.", listOf("BT638 beobachtet"), unknownDetails = listOf("noch keine belastbare Zuordnung"), uiHint = "neutral anzeigen"),
        "1802" to VmaxGattKnowledge(CapabilityEvidence.BT638_OBSERVED, family("1802"), "Am BT638 entdeckt; Firmware-/Hardwarefamilie möglich.", listOf("BT638 beobachtet", "Original SDK"), uiHint = "als Firmware-/Hardwarehinweis zeigen")
    )

    private val standardBluetooth = mapOf(
        "1800" to "Generic Access Service",
        "1801" to "Generic Attribute Service",
        "2A00" to "Device Name",
        "2A01" to "Appearance",
        "2A02" to "Peripheral Privacy Flag",
        "2A04" to "Peripheral Preferred Connection Parameters",
        "2A05" to "Service Changed",
        "2A28" to "Software Revision String"
    )

    fun classify(service: String, characteristic: String): VmaxGattKnowledge {
        bt638Confirmed[characteristic]?.let { return it }
        bt638Observed[characteristic]?.let { return it }
        standardBluetooth[characteristic]?.let {
            return VmaxGattKnowledge(
                evidence = CapabilityEvidence.BLUETOOTH_STANDARD,
                family = "Standard-GATT",
                meaning = it,
                sources = listOf("Bluetooth-Standard"),
                uiHint = "als Standarddienst kennzeichnen"
            )
        }
        standardBluetooth[service]?.let {
            return VmaxGattKnowledge(
                evidence = CapabilityEvidence.BLUETOOTH_STANDARD,
                family = "Standard-GATT",
                meaning = it,
                sources = listOf("Bluetooth-Standard"),
                uiHint = "als Standarddienst kennzeichnen"
            )
        }

        val sdkMeaning = when {
            characteristic in setOf("1514", "1516", "1517", "1518") ->
                "Im nativen GPST-SDK vorhanden; BT638-Unterstützung noch nicht bestätigt."
            characteristic.startsWith("16") ->
                "SDK-Familie für Motor-Tuning/Firmware; nur lesen, bis Format und Read-back bestätigt sind."
            characteristic.startsWith("18") ->
                "SDK-Familie für Firmware-/Hardwareinformationen."
            characteristic.startsWith("1A") ->
                "SDK-Familie für Batterie-/Ladeinformationen."
            characteristic == "1C00" ->
                "SDK-Zusatzdienst; Bedeutung modellabhängig."
            characteristic.startsWith("1E") ->
                "SDK-Familie für erweiterte Batterie-/Lade- oder Speichermodi."
            characteristic.startsWith("1F") ->
                "SDK-Zusatzfamilie; Bedeutung modellabhängig."
            service.startsWith("15") || characteristic.startsWith("15") ->
                "Telemetrie-Familie des GPST-SDK; konkrete Bedeutung noch offen."
            else -> null
        }
        return if (sdkMeaning != null) {
            VmaxGattKnowledge(
                evidence = CapabilityEvidence.SDK_CONFIRMED,
                family = family(characteristic),
                meaning = sdkMeaning,
                sources = listOf("Original SDK", "Original APK"),
                unknownDetails = listOf("BT638-Unterstützung noch nicht bestätigt"),
                uiHint = "als SDK-bekannt, aber noch nicht bestätigt markieren"
            )
        } else {
            VmaxGattKnowledge(
                evidence = CapabilityEvidence.UNKNOWN,
                family = "Unbekannt",
                meaning = "Nicht in der importierten Wissensbasis zugeordnet.",
                sources = listOf("Live beobachtet"),
                unknownDetails = listOf("keine belastbare Zuordnung vorhanden"),
                uiHint = "neutral und ohne harte Aussage anzeigen"
            )
        }
    }

    private fun family(uuid: String): String = when {
        uuid.startsWith("15") -> "Telemetrie 15xx"
        uuid.startsWith("16") -> "Motor-Tuning/Firmware 16xx"
        uuid.startsWith("18") -> "Firmware/Hardware 18xx"
        uuid.startsWith("1A") -> "Batterie/Laden 1Axx"
        uuid.startsWith("1E") -> "Erweiterte Batterie/Laden 1Exx"
        uuid.startsWith("1F") -> "Zusatzdienst 1Fxx"
        else -> "GATT"
    }
}

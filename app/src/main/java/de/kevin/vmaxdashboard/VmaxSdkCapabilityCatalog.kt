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
    val meaning: String
)

object VmaxSdkCapabilityCatalog {
    private val bt638Confirmed = mapOf(
        "1505" to "Fahrdaten; Geschwindigkeit Byte 6–7 bestätigt",
        "1506" to "Kilometerstand Byte 0–3 bestätigt",
        "1508" to "Licht Byte 0 und ECO/SPORT Byte 3 bestätigt",
        "1509" to "Akku, Spannung, Strom und direkte Leistung bestätigt",
        "150D" to "zweite Geschwindigkeitsquelle Byte 0–1 bestätigt"
    )

    private val bt638Observed = mapOf(
        "1501" to "am BT638 entdeckt; Bedeutung offen",
        "1502" to "statischer Akku-/Controllerblock; Bedeutung offen",
        "1503" to "am BT638 entdeckt; Bedeutung offen",
        "1504" to "am BT638 entdeckt; Bedeutung offen",
        "1507" to "am BT638 entdeckt; Bedeutung offen",
        "150A" to "dynamischer Last-/Stromkandidat",
        "150B" to "bisher überwiegend FF-Platzhalter",
        "150C" to "am BT638 entdeckt; Zell-/Temperaturbezug im SDK möglich",
        "150E" to "am BT638 entdeckt; Bedeutung offen",
        "150F" to "am BT638 entdeckt; Bedeutung offen",
        "1510" to "am BT638 entdeckt; Bedeutung offen",
        "1511" to "am BT638 entdeckt; Bedeutung offen",
        "1512" to "am BT638 entdeckt; Bedeutung offen",
        "1513" to "am BT638 entdeckt; Bedeutung offen",
        "151C" to "am BT638 entdeckt; Bedeutung offen",
        "1802" to "am BT638 entdeckt; Firmware-/Hardwarefamilie möglich"
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
        bt638Confirmed[characteristic]?.let {
            return VmaxGattKnowledge(CapabilityEvidence.BT638_CONFIRMED, family(characteristic), it)
        }
        bt638Observed[characteristic]?.let {
            return VmaxGattKnowledge(CapabilityEvidence.BT638_OBSERVED, family(characteristic), it)
        }
        standardBluetooth[characteristic]?.let {
            return VmaxGattKnowledge(CapabilityEvidence.BLUETOOTH_STANDARD, "Standard-GATT", it)
        }
        standardBluetooth[service]?.let {
            return VmaxGattKnowledge(CapabilityEvidence.BLUETOOTH_STANDARD, "Standard-GATT", it)
        }

        val sdkMeaning = when {
            characteristic in setOf("1514", "1516", "1517", "1518") ->
                "im nativen GPST-SDK vorhanden; BT638-Unterstützung noch nicht bestätigt"
            characteristic.startsWith("16") ->
                "SDK-Familie für Motor-Tuning/Firmware; nur lesen, bis Format und Read-back bestätigt sind"
            characteristic.startsWith("18") ->
                "SDK-Familie für Firmware-/Hardwareinformationen"
            characteristic.startsWith("1A") ->
                "SDK-Familie für Batterie-/Ladeinformationen"
            characteristic == "1C00" ->
                "SDK-Zusatzdienst; Bedeutung modellabhängig"
            characteristic.startsWith("1E") ->
                "SDK-Familie für erweiterte Batterie-/Lade- oder Speichermodi"
            characteristic.startsWith("1F") ->
                "SDK-Zusatzfamilie; Bedeutung modellabhängig"
            service.startsWith("15") || characteristic.startsWith("15") ->
                "Telemetrie-Familie des GPST-SDK; konkrete Bedeutung noch offen"
            else -> null
        }
        return if (sdkMeaning != null) {
            VmaxGattKnowledge(CapabilityEvidence.SDK_CONFIRMED, family(characteristic), sdkMeaning)
        } else {
            VmaxGattKnowledge(CapabilityEvidence.UNKNOWN, "Unbekannt", "nicht in der importierten Wissensbasis zugeordnet")
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

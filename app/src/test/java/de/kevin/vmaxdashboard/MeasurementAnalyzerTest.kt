package de.kevin.vmaxdashboard

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MeasurementAnalyzerTest {
    @Test
    fun automaticAnalysisDoesNotRediscoverKnown1508Or150dStatistics() {
        val rows = buildList {
            repeat(60) { index ->
                val phase = when (index) {
                    in 0..19 -> 0
                    in 20..39 -> 1
                    else -> 0
                }
                add(packet(index * 200L, "1508", bytes(phase, 0, 0, phase + 1, 0, 0, 0, 0, 0, 0, 0, 0)))
                add(packet(index * 200L + 50L, "150D", bytes(phase, 0, phase, 0, 0, 0, 0, 0)))
            }
        }

        val (findings, _) = MeasurementAnalyzer.analyze(rows, emptyList())

        assertFalse(findings.any { it.channel == "1508" && it.byteIndex in setOf(0, 3) })
        assertFalse(findings.any { it.channel == "150D" && it.byteIndex in 0..3 })
    }

    @Test
    fun reconnectMarkersAreNotReportedAsManualTests() {
        val markers = listOf(
            "0;1000;START",
            "2500;3500;BLE getrennt • RAW 10 gesichert",
            "3000;4000;BLE wieder verbunden • RAW 10 wiederhergestellt",
            "3200;4200;BLE-Link wieder verbunden • RAW 10 wiederhergestellt",
            "3300;4300;Telemetrie wieder aktiv",
            "3500;4500;BLE beim Laden getrennt",
            "4000;5000;BLE beim Laden wieder verbunden",
            "5000;6000;STOP"
        )

        val (_, report) = MeasurementAnalyzer.analyze(emptyList(), markers)

        assertTrue(report.contains("Manuelle Marker: 0"))
    }

    @Test
    fun unavailable150dRunDoesNotBecomeALearnedSwitch() {
        val validBefore = bytes(
            0x00, 0x0B, 0x00, 0x00,
            0xFF, 0xFF, 0xFF, 0xFF, 0x47, 0x18,
            0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0x00, 0x00
        )
        val unavailable = bytes(
            0x00, 0x00, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF,
            0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF
        )
        val validAfter = bytes(
            0x00, 0x0E, 0x00, 0x00,
            0xFF, 0xFF, 0xFF, 0xFF, 0x47, 0x18,
            0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0x00, 0x00
        )
        val rows = buildList {
            repeat(45) { add(packet(it * 200L, "150D", validBefore)) }
            repeat(12) { add(packet((45 + it) * 200L, "150D", unavailable)) }
            repeat(45) { add(packet((57 + it) * 200L, "150D", validAfter)) }
        }

        val (findings, _) = MeasurementAnalyzer.analyze(rows, emptyList())

        assertFalse(findings.any { it.channel == "150D" })
    }

    @Test
    fun reportSeparatesMeasurementDurationFromLastTelemetryPacket() {
        val rows = listOf(packet(95_355L, "1505", bytes(0, 0, 0, 0, 0xFF, 0xFF, 0, 0)))
        val markers = listOf("0;1000;START", "540705;541705;STOP")

        val (_, report) = MeasurementAnalyzer.analyze(rows, markers)

        assertTrue(report.contains("Messdauer_ms: 540705"))
        assertTrue(report.contains("Telemetrie_bis_ms: 95355"))
        assertTrue(report.contains("Größte_Datenlücke_ms: 445350"))
    }

    @Test
    fun unavailable150dPayloadIsNotDisplayedAsZeroMaximum() {
        val unavailable = "00-00-FF-FF-FF-FF-FF-FF-FF-FF-FF-FF-FF-FF-FF-FF-FF-FF-FF-FF"

        assertTrue(decode150dStatistic(unavailable, 0) == null)
        assertTrue(decode150dStatistic("00-DA-00-78-FF-FF", 0) == 21.8)
        assertTrue(decode150dStatistic("00-DA-00-78-FF-FF", 2) == 12.0)
    }

    private fun packet(relativeMs: Long, channel: String, payload: String): String =
        "$relativeMs;${1_000L + relativeMs};$channel;test;8;1;0;$payload"

    private fun bytes(vararg values: Int): String =
        values.joinToString("-") { "%02X".format(it) }
}

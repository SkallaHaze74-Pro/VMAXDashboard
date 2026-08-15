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
            "3500;4500;BLE beim Laden getrennt",
            "4000;5000;BLE beim Laden wieder verbunden",
            "5000;6000;STOP"
        )

        val (_, report) = MeasurementAnalyzer.analyze(emptyList(), markers)

        assertTrue(report.contains("Manuelle Marker: 0"))
    }

    private fun packet(relativeMs: Long, channel: String, payload: String): String =
        "$relativeMs;${1_000L + relativeMs};$channel;test;8;1;0;$payload"

    private fun bytes(vararg values: Int): String =
        values.joinToString("-") { "%02X".format(it) }
}

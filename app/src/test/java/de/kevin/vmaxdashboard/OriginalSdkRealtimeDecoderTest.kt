package de.kevin.vmaxdashboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OriginalSdkRealtimeDecoderTest {
    @Test
    fun rangeUsesOriginalSdkKilometresAndRejectsFfff() {
        val valid = OriginalSdkRealtimeDecoder.decodePackets(
            mapOf("1505" to "00-00-00-00-FF-FF-00-00-FF-FF-00-2A")
        )
        val unavailable = OriginalSdkRealtimeDecoder.decodePackets(
            mapOf("1505" to "00-00-00-00-FF-FF-00-00-FF-FF-FF-FF")
        )
        val implausible = OriginalSdkRealtimeDecoder.decodePackets(
            mapOf("1505" to "00-00-00-00-FF-FF-00-00-FF-FF-03-E9")
        )

        assertEquals(42.0, valid.remainingRangeKm!!, 0.0001)
        assertNull(unavailable.remainingRangeKm)
        assertNull(implausible.remainingRangeKm)
    }

    @Test
    fun batteryCurrentKeepsSignedSdkEncoding() {
        val decoded = OriginalSdkRealtimeDecoder.decodePackets(
            mapOf("1509" to "FF-9C-FF-FF-32-C3-50-FF-FF-00-00")
        )

        assertEquals(-0.1, decoded.batteryCurrentA!!, 0.0001)
    }
}

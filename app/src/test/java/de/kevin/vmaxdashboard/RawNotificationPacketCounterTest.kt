package de.kevin.vmaxdashboard

import org.junit.Assert.assertEquals
import org.junit.Test

class RawNotificationPacketCounterTest {
    @Test
    fun packetNumbersStayChannelScopedAcrossAcceptedAndQuarantinedRows() {
        val counter = RawNotificationPacketCounter()

        assertEquals(1, counter.next("1505"))
        assertEquals(1, counter.next("1509"))
        assertEquals(2, counter.next("1505"))
        assertEquals(3, counter.next("1505"))
    }

    @Test
    fun reconnectStartsFreshChannelSequences() {
        val counter = RawNotificationPacketCounter()
        counter.next("1505")
        counter.next("1505")

        counter.clear()

        assertEquals(1, counter.next("1505"))
    }
}

package de.kevin.vmaxdashboard

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VmaxStartModeProtocolTest {
    @Test
    fun decodesObservedSeventeenByteNotificationFingerprints() {
        val zero = hex("00-00-00-02-FF-FF-FF-FF-FF-FF-FF-00-00-00-00-00-00")
        val kick = hex("01-00-00-01-FF-FF-FF-FF-FF-FF-FF-01-00-00-00-00-00")

        assertEquals(VmaxStartMode.ZERO_START, VmaxStartModeProtocol.decodeLive1508(zero))
        assertEquals(VmaxStartMode.KICK_START, VmaxStartModeProtocol.decodeLive1508(kick))
    }

    @Test
    fun rejectsShortDiagnosticAndInvalidFingerprints() {
        val valid = hex("00-00-00-02-FF-FF-FF-FF-FF-FF-FF-00-00-00-00-00-00")

        assertNull(VmaxStartModeProtocol.decodeLive1508(valid.copyOf(16)))
        assertNull(VmaxStartModeProtocol.decodeLive1508(valid.copyOf(12)))
        assertNull(VmaxStartModeProtocol.decodeLive1508(valid.copyOf().also { it[0] = 2 }))
        assertNull(VmaxStartModeProtocol.decodeLive1508(valid.copyOf().also { it[1] = 1 }))
        assertNull(VmaxStartModeProtocol.decodeLive1508(valid.copyOf().also { it[3] = 0 }))
        assertNull(VmaxStartModeProtocol.decodeLive1508(valid.copyOf().also { it[4] = 0 }))
        assertNull(VmaxStartModeProtocol.decodeLive1508(valid.copyOf().also { it[12] = 1 }))
        assertNull(VmaxStartModeProtocol.decodeLive1508(valid.copyOf().also { it[11] = 2 }))
    }

    @Test
    fun legacyWriteChangesExactlyTheConfirmedFinalByte() {
        assertArrayEquals(
            byteArrayOf(-1, -1, -1, -1, -1, -1, -1, 0),
            VmaxStartModeProtocol.buildLegacyWriteFrame(VmaxStartMode.ZERO_START)
        )
        assertArrayEquals(
            byteArrayOf(-1, -1, -1, -1, -1, -1, -1, 1),
            VmaxStartModeProtocol.buildLegacyWriteFrame(VmaxStartMode.KICK_START)
        )
    }

    private fun hex(value: String): ByteArray = value.split('-')
        .map { it.toInt(16).toByte() }
        .toByteArray()
}

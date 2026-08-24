package de.kevin.vmaxdashboard

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VmaxDecoderPolicyTest {
    @Test
    fun canonicalCloudMappingsCannotChangeSignednessOrOffset() {
        assertTrue(VmaxDecoderPolicy.isAdaptiveRuleAllowed("currentA", "1509", 0, "s16be"))
        assertFalse(VmaxDecoderPolicy.isAdaptiveRuleAllowed("currentA", "1509", 0, "u16be"))
        assertTrue(VmaxDecoderPolicy.isAdaptiveRuleAllowed("odometerKm", "1506", 0, "u32be"))
        assertFalse(VmaxDecoderPolicy.isAdaptiveRuleAllowed("odometerKm", "1506", 2, "u16be"))
        assertFalse(VmaxDecoderPolicy.isAdaptiveRuleAllowed("speedKmh", "150D", 0, "u16be"))
        assertFalse(VmaxDecoderPolicy.isAdaptiveRuleAllowed("currentA", "150A", 0, "s16be"))
        assertFalse(VmaxDecoderPolicy.isAdaptiveRuleAllowed("speedKmh", "151D", 6, "u16be"))
        assertFalse(VmaxDecoderPolicy.isAdaptiveRuleAllowed("powerW", "1509", 9, "u16be"))
        assertFalse(VmaxDecoderPolicy.isAdaptiveRuleAllowed("powerW", "1509", 7, "u16be"))
        assertFalse(VmaxDecoderPolicy.isAdaptiveRuleAllowed("powerW", "150A", 9, "u16be"))
        assertTrue(VmaxDecoderPolicy.isAdaptiveRuleAllowed("lightOn", "1508", 0, "u8"))
        assertFalse(VmaxDecoderPolicy.isAdaptiveRuleAllowed("lightOn", "1509", 0, "u8"))
        assertFalse(VmaxDecoderPolicy.isAdaptiveRuleAllowed("brakeActive", "150D", 8, "u8"))
        assertFalse(VmaxDecoderPolicy.isAdaptiveRuleAllowed("brakeActive", "1508", 3, "u8"))
        assertFalse(VmaxDecoderPolicy.isAdaptiveRuleAllowed("charging", "151D", 2, "u8"))
    }

    @Test
    fun automaticLearningSkipsKnownSwitchesAndRideStatistics() {
        assertFalse(VmaxDecoderPolicy.isLearningCandidateAllowed("Auto Zustandswechsel", "1508", 0))
        assertFalse(VmaxDecoderPolicy.isLearningCandidateAllowed("Auto Fahrt/Ruhe", "1508", 3))
        assertFalse(VmaxDecoderPolicy.isLearningCandidateAllowed("Auto Zustandswechsel", "1508", 11))
        assertFalse(VmaxDecoderPolicy.isLearningCandidateAllowed("Zero-Start", "1508", 11))
        assertFalse(VmaxDecoderPolicy.isLearningCandidateAllowed("Kick-Start", "1508", 11))
        assertFalse(VmaxDecoderPolicy.isLearningCandidateAllowed("Auto Zustandswechsel", "150D", 0))
        assertFalse(VmaxDecoderPolicy.isLearningCandidateAllowed("Auto Zustandswechsel", "150D", 3))
        assertFalse(VmaxDecoderPolicy.isLearningCandidateAllowed("Auto Zustandswechsel", "150D", 8))
        assertFalse(VmaxDecoderPolicy.isLearningCandidateAllowed("Licht AN", "150D", 19))
        assertTrue(VmaxDecoderPolicy.isLearningCandidateAllowed("Licht AN", "1508", 0))
        assertTrue(VmaxDecoderPolicy.isLearningCandidateAllowed("SPORT", "1508", 3))
        assertTrue(VmaxDecoderPolicy.isLearningCandidateAllowed("Auto Impuls A-B-A", "151D", 2))
    }

    @Test
    fun firmwareIdHybridIsQuarantinedFromLive1505() {
        val hybrid = byteArrayOf(
            0x00, 0x00, 0x00, 0x00, 0xFF.toByte(), 0xFF.toByte(), 0x00, 0x00,
            0x33, 0x34, 0x35, 0x36, 0x37, 0x38, 0x39, 0x35, 0x3D, 0x14
        )
        val clean = byteArrayOf(
            0x00, 0x00, 0x00, 0x00, 0xFF.toByte(), 0xFF.toByte(), 0x00, 0x7B,
            0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(),
            0x00, 0x00, 0x00, 0x00, 0x00
        )

        assertTrue(VmaxDecoderPolicy.isSuspiciousReadLikePayload("1505", hybrid))
        assertFalse(VmaxDecoderPolicy.isSuspiciousReadLikePayload("1505", clean))
    }
}

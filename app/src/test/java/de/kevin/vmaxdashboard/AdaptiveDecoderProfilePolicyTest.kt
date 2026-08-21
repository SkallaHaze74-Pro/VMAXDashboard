package de.kevin.vmaxdashboard

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveDecoderProfilePolicyTest {
    @Test
    fun emptyOrOlderCloudProfileCannotReplaceWorkingProfile() {
        assertFalse(shouldInstallCloudProfile(2_000L, 1_000L, 0))
        assertFalse(shouldInstallCloudProfile(999L, 1_000L, 4))
        assertTrue(shouldInstallCloudProfile(1_000L, 1_000L, 4))
        assertTrue(shouldInstallCloudProfile(2_000L, 1_000L, 4))
    }

    @Test
    fun onlyConfirmedHighConfidenceRulesAreActivatable() {
        assertFalse(isActivatableAdaptiveRule("candidate", 99))
        assertFalse(isActivatableAdaptiveRule("confirmed", 91))
        assertFalse(isActivatableAdaptiveRule("rejected", 99))
        assertTrue(isActivatableAdaptiveRule("confirmed", 92))
        assertTrue(isActivatableAdaptiveRule("confirmed", 99))
    }

    @Test
    fun malformedConfirmedRulesCannotReplaceAWorkingProfile() {
        fun usable(
            signal: String = "speedKmh",
            observations: Int = 20,
            offset: Int = 6,
            width: Int = 2,
            encoding: String = "u16be",
            scale: Double = 0.1,
            bias: Double = 0.0,
            activeValue: Long? = null,
            inactiveValue: Long? = null
        ) = isSemanticallyUsableAdaptiveRule(
            signal = signal,
            status = "confirmed",
            confidence = 97,
            observations = observations,
            offset = offset,
            width = width,
            encoding = encoding,
            scale = scale,
            bias = bias,
            activeValue = activeValue,
            inactiveValue = inactiveValue
        )

        assertTrue(usable())
        assertFalse(usable(observations = 0))
        assertFalse(usable(offset = Int.MAX_VALUE))
        assertFalse(usable(scale = 0.0))
        assertFalse(usable(scale = Double.NaN))
        assertFalse(usable(bias = Double.POSITIVE_INFINITY))
        assertFalse(usable(signal = "lightOn", width = 1, encoding = "u8", scale = 1.0))
        assertFalse(usable(signal = "lightOn", width = 1, encoding = "u8", scale = 1.0, activeValue = 1, inactiveValue = 1))
        assertFalse(usable(signal = "lightOn", width = 1, encoding = "u8", scale = 1.0, activeValue = 300, inactiveValue = 0))
        assertTrue(usable(signal = "lightOn", width = 1, encoding = "u8", scale = 1.0, activeValue = 1, inactiveValue = 0))
    }

    @Test
    fun consumerGateRejectsStructurallyValidButNonCanonicalCloudRule() {
        fun safe(channel: String, offset: Int = 6, encoding: String = "u16be") =
            isSafeAdaptiveRuleForActivation(
                signal = "speedKmh",
                channel = channel,
                status = "confirmed",
                confidence = 99,
                observations = 10_000,
                offset = offset,
                width = 2,
                encoding = encoding,
                scale = 0.1,
                bias = 0.0,
                activeValue = null,
                inactiveValue = null
            )

        assertTrue(safe("1505"))
        assertFalse(safe("151D"))
        assertFalse(safe("1505", offset = 4))
        assertFalse(safe("1505", encoding = "u16le"))
    }

    @Test
    fun legacyConfirmedDirectPowerProfileIsBlockedByFinalConsumerGate() {
        assertFalse(
            isSafeAdaptiveRuleForActivation(
                signal = "powerW",
                channel = "1509",
                status = "confirmed",
                confidence = 99,
                observations = 939,
                offset = 9,
                width = 2,
                encoding = "u16be",
                scale = 1.0,
                bias = 0.0,
                activeValue = null,
                inactiveValue = null
            )
        )
    }

    @Test
    fun reconnectDerivedChargingProfileIsBlockedUntilDirectEvidenceExists() {
        assertFalse(
            isSafeAdaptiveRuleForActivation(
                signal = "charging",
                channel = "151D",
                status = "confirmed",
                confidence = 99,
                observations = 100,
                offset = 2,
                width = 1,
                encoding = "u8",
                scale = 1.0,
                bias = 0.0,
                activeValue = 1,
                inactiveValue = 0
            )
        )
    }
}

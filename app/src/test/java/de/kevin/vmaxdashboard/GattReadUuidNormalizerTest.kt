package de.kevin.vmaxdashboard

import org.junit.Assert.assertEquals
import org.junit.Test

class GattReadUuidNormalizerTest {
    @Test
    fun normalizesDa1aCharacteristicLikeManagerLog() {
        assertEquals(
            "1509",
            normalizeGattShortUuid("da1a1509-d532-4285-be94-b07a3e11a098")
        )
    }

    @Test
    fun normalizesBluetoothStandardCharacteristicLikeManagerLog() {
        assertEquals(
            "2A28",
            normalizeGattShortUuid("00002a28-0000-1000-8000-00805f9b34fb")
        )
    }

    @Test
    fun normalizesBluetoothStandardServiceLikeManagerLog() {
        assertEquals(
            "1800",
            normalizeGattShortUuid("00001800-0000-1000-8000-00805f9b34fb")
        )
    }

    @Test
    fun mirrorsManagerForOtherCustomUuidFamilies() {
        assertEquals(
            "1234",
            normalizeGattShortUuid("abcd1234-1111-2222-3333-444455556666")
        )
    }
}

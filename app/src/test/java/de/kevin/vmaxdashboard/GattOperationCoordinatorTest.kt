package de.kevin.vmaxdashboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GattOperationCoordinatorTest {
    @Test
    fun notifyOnlyMotorVerificationKeepsGattOwnedUntilPendingWriteFinishes() {
        assertTrue(keepMotorTuningOperationForNotifyOnlyVerification(hasPendingWrite = true))
        assertFalse(keepMotorTuningOperationForNotifyOnlyVerification(hasPendingWrite = false))
    }

    @Test
    fun staleConnectionAndOperationTokensCannotAffectCurrentConnection() {
        val coordinator = GattOperationCoordinator()
        val firstEpoch = coordinator.openConnection()
        val firstOperation = coordinator.tryBegin(firstEpoch, GattOperationKind.DIAGNOSTIC_READ_SCAN)!!

        val secondEpoch = coordinator.openConnection()
        val secondOperation = coordinator.tryBegin(secondEpoch, GattOperationKind.START_MODE_WRITE)!!

        assertFalse(coordinator.isActiveConnection(firstEpoch))
        assertTrue(coordinator.isActiveConnection(secondEpoch))
        assertFalse(coordinator.finish(firstOperation))
        assertTrue(coordinator.isCurrent(secondOperation))
        assertFalse(coordinator.closeConnection(firstEpoch))
        assertTrue(coordinator.isCurrent(secondOperation))
    }

    @Test
    fun oneGattOperationOwnsTheConnectionUntilItsExactTokenFinishes() {
        val coordinator = GattOperationCoordinator()
        val epoch = coordinator.openConnection()
        val operation = coordinator.tryBegin(epoch, GattOperationKind.MOTOR_TUNING)!!

        assertNull(coordinator.tryBegin(epoch, GattOperationKind.START_MODE_WRITE))
        assertEquals(GattOperationKind.MOTOR_TUNING, coordinator.currentKind(epoch))
        assertTrue(coordinator.finish(operation))
        assertNull(coordinator.currentKind(epoch))
        assertTrue(coordinator.tryBegin(epoch, GattOperationKind.START_MODE_WRITE) != null)
    }

    @Test
    fun sequentialReadsAdvanceOnlyAfterMatchingCallbackOrTimeout() {
        val reads = SequentialGattReadCoordinator<String>()
        reads.reset(listOf("1505", "1508"))

        assertEquals("1505", reads.beginNext())
        assertNull(reads.beginNext())
        assertFalse(reads.complete("1508"))
        assertEquals("1505", reads.inFlight)
        assertTrue(reads.complete("1505"))

        assertEquals("1508", reads.beginNext())
        assertTrue(reads.running)
        assertTrue(reads.timeout("1508"))
        assertFalse(reads.running)
        assertNull(reads.beginNext())
    }
}

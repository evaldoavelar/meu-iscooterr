package br.dev.evaldo.meuscooter.data

import br.dev.evaldo.meuscooter.protocol.Clock
import br.dev.evaldo.meuscooter.protocol.LockRefusal
import br.dev.evaldo.meuscooter.protocol.LockResult
import br.dev.evaldo.meuscooter.protocol.Pin
import br.dev.evaldo.meuscooter.protocol.Telemetry
import br.dev.evaldo.meuscooter.protocol.UnconfirmedCause
import br.dev.evaldo.meuscooter.protocol.UnlockResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Bloqueio modo 2 SIMULADO (nenhum BLE): mesmo contrato do real, sem PIN. */
@OptIn(ExperimentalCoroutinesApi::class)
class FakeScooterTransportLockTest {
    private fun TestScope.fake(pinMode: Boolean = true) =
        FakeScooterTransport(backgroundScope, Clock { currentTime }, FakeConfig(), pinLock = if (pinMode) Pin.of("1357") else null)

    private suspend fun TestScope.connected(f: FakeScooterTransport): MutableList<Telemetry> {
        val seen = mutableListOf<Telemetry>()
        backgroundScope.launch { f.telemetry.collect { seen += it } }
        f.connect()
        runCurrent()
        return seen
    }

    private suspend fun TestScope.unlock(f: FakeScooterTransport) {
        val u = async { f.unlock(Pin.of("1357")) }
        advanceTimeBy(1_000)
        u.await()
    }

    @Test fun withoutTheRealLikeModeLockIsNotSupported() = runTest {
        val f = fake(pinMode = false)
        assertFalse(f.lockAvailable)
        connected(f)
        assertEquals(LockResult.Refused(LockRefusal.NotSupported), f.lock())
    }

    @Test fun lockWithoutPinConfirmsOnlyThroughTelemetry() = runTest {
        val f = fake()
        assertTrue(f.lockAvailable)
        val seen = connected(f)
        val unlock = async { f.unlock(Pin.of("1357")) }
        advanceTimeBy(1_000)
        unlock.await()
        assertEquals(false, seen.last().locked)
        val res = async { f.lock() }
        advanceTimeBy(1_000)
        assertEquals(LockResult.Locked, res.await())
        assertEquals(true, seen.last().locked)
    }

    @Test fun aStaleSnapshotRefusesLockWithoutChangingStateUntilFreshTelemetryArrives() = runTest {
        val f = fake()
        val seen = connected(f)
        assertEquals(UnlockResult.Unlocked, f.unlock(Pin.of("1357")))
        runCurrent()
        f.freezeTelemetry(true)
        val before = seen.last()
        val count = seen.size
        assertEquals(false, before.locked)
        advanceTimeBy(2_001)
        assertEquals(LockResult.Refused(LockRefusal.TelemetryStale), f.lock())
        runCurrent()
        assertEquals(count, seen.size)
        assertEquals(before, seen.last())
        f.freezeTelemetry(false)
        advanceTimeBy(300)
        runCurrent()
        assertEquals(false, seen.last().locked)
        assertEquals(LockResult.Locked, f.lock())
        runCurrent()
        assertEquals(true, seen.last().locked)
    }

    @Test fun lockWhenAlreadyLockedIsRefusedWithoutChangingAnything() = runTest {
        val f = fake()
        val seen = connected(f)
        assertEquals(true, seen.last().locked)
        assertEquals(LockResult.Refused(LockRefusal.AlreadyLocked), f.lock())
        assertEquals(true, seen.last().locked)
    }

    @Test fun lockTelemetryAgeMustBeStrictlyLessThanTwoSeconds() = runTest {
        for (age in listOf(1_999L, 2_000L)) {
            val f = fake()
            val seen = connected(f)
            assertEquals(UnlockResult.Unlocked, f.unlock(Pin.of("1357")))
            runCurrent()
            f.freezeTelemetry(true)
            advanceTimeBy(age)
            val expected = if (age < 2_000) LockResult.Locked else LockResult.Refused(LockRefusal.TelemetryStale)
            assertEquals("idade=$age", expected, f.lock())
            runCurrent()
            assertEquals(age < 2_000, seen.last().locked)
            f.disconnect()
        }
    }

    @Test fun preconditionsRefuseWithoutChangingAnything() = runTest {
        val f = fake()
        assertEquals(LockResult.Refused(LockRefusal.LinkNotReady), f.lock())
        val seen = connected(f)
        unlock(f)
        f.setSpeed(5f)
        assertEquals(LockResult.Refused(LockRefusal.Moving), f.lock())
        f.setSpeed(0f)
        val first = async { f.lock() }
        runCurrent()
        assertEquals(LockResult.Refused(LockRefusal.Busy), f.lock())
        advanceTimeBy(1_000)
        first.await()
        assertTrue(seen.isNotEmpty())
    }

    @Test fun lockIsBusyWhileDriveSettingIsPending() = runTest {
        val f = fake()
        connected(f)
        assertEquals(UnlockResult.Unlocked, f.unlock(Pin.of("1357")))

        val setting = async { f.setDrive(twoMotors = true) }
        runCurrent()
        assertEquals(LockResult.Refused(LockRefusal.Busy), f.lock())

        advanceTimeBy(1_000)
        assertEquals(br.dev.evaldo.meuscooter.protocol.ScooterSettingResult.Confirmed, setting.await())
    }

    @Test fun anIgnoredLockEndsWithoutConfirmationAfterThreeSeconds() = runTest {
        val f = fake()
        val seen = connected(f)
        unlock(f)
        f.setSpeed(0f)
        f.ignoreNextCommand()
        val res = async { f.lock() }
        advanceTimeBy(2_999)
        assertFalse(res.isCompleted)
        advanceTimeBy(2)
        assertEquals(LockResult.Unconfirmed(UnconfirmedCause.Timeout), res.await())
        assertEquals(false, seen.last().locked) // ignorado: nada foi alterado
    }
}

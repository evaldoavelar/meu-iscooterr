package br.dev.evaldo.meuscooter.data

import br.dev.evaldo.meuscooter.protocol.Clock
import br.dev.evaldo.meuscooter.protocol.LockRefusal
import br.dev.evaldo.meuscooter.protocol.LockResult
import br.dev.evaldo.meuscooter.protocol.Pin
import br.dev.evaldo.meuscooter.protocol.Telemetry
import br.dev.evaldo.meuscooter.protocol.UnconfirmedCause
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

    @Test fun preconditionsRefuseWithoutChangingAnything() = runTest {
        val f = fake()
        assertEquals(LockResult.Refused(LockRefusal.LinkNotReady), f.lock())
        val seen = connected(f)
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

    @Test fun anIgnoredLockEndsWithoutConfirmationAfterThreeSeconds() = runTest {
        val f = fake()
        val seen = connected(f)
        f.setSpeed(0f)
        f.ignoreNextCommand()
        val res = async { f.lock() }
        advanceTimeBy(2_999)
        assertFalse(res.isCompleted)
        advanceTimeBy(2)
        assertEquals(LockResult.Unconfirmed(UnconfirmedCause.Timeout), res.await())
        assertEquals(true, seen.last().locked) // o simulado já nasce bloqueado; nada foi alterado
    }
}

package br.dev.evaldo.meuscooter.data

import br.dev.evaldo.meuscooter.protocol.Clock
import br.dev.evaldo.meuscooter.protocol.Pin
import br.dev.evaldo.meuscooter.protocol.ScooterCommand
import br.dev.evaldo.meuscooter.protocol.Telemetry
import br.dev.evaldo.meuscooter.protocol.UnconfirmedCause
import br.dev.evaldo.meuscooter.protocol.UnlockRefusal
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

/** Desbloqueio com PIN SIMULADO (nenhum BLE). PIN sintético 1357. */
@OptIn(ExperimentalCoroutinesApi::class)
class FakeScooterTransportPinTest {
    private fun TestScope.fake(pin: String? = "1357") =
        FakeScooterTransport(backgroundScope, Clock { currentTime }, FakeConfig(), pinLock = pin?.let(Pin::of))

    private suspend fun TestScope.connected(f: FakeScooterTransport): MutableList<Telemetry> {
        val seen = mutableListOf<Telemetry>()
        backgroundScope.launch { f.telemetry.collect { seen += it } }
        f.connect()
        runCurrent()
        return seen
    }

    @Test fun withoutAPinLockTheFakeKeepsTheOldBehaviour() = runTest {
        val f = fake(pin = null)
        assertFalse(f.pinUnlockAvailable)
        connected(f)
        assertEquals(UnlockResult.Refused(UnlockRefusal.NotSupported), f.unlock(Pin.of("1357")))
    }

    @Test fun theRightPinUnlocksAndTheTelemetryConfirmsIt() = runTest {
        val f = fake()
        assertTrue(f.pinUnlockAvailable)
        val seen = connected(f)
        assertEquals(true, seen.last().locked)
        val res = async { f.unlock(Pin.of("1357")) }
        advanceTimeBy(1_000)
        assertEquals(UnlockResult.Unlocked, res.await())
        assertEquals(false, seen.last().locked)
    }

    @Test fun aWrongPinIsRejectedAndNothingChanges() = runTest {
        val f = fake()
        val seen = connected(f)
        assertEquals(UnlockResult.PinRejected, f.unlock(Pin.of("2468")))
        advanceTimeBy(1_000)
        assertEquals(true, seen.last().locked)
    }

    @Test fun preconditionsRefuse() = runTest {
        val f = fake()
        assertEquals(UnlockResult.Refused(UnlockRefusal.LinkNotReady), f.unlock(Pin.of("1357")))
        connected(f)
        f.setSpeed(5f)
        assertEquals(UnlockResult.Refused(UnlockRefusal.Moving), f.unlock(Pin.of("1357")))
        f.setSpeed(0f)
        val first = async { f.unlock(Pin.of("1357")) }
        runCurrent()
        assertEquals(UnlockResult.Refused(UnlockRefusal.Busy), f.unlock(Pin.of("1357")))
        advanceTimeBy(1_000)
        assertEquals(UnlockResult.Unlocked, first.await())
        assertEquals(UnlockResult.Refused(UnlockRefusal.NotLocked), f.unlock(Pin.of("1357")))
    }

    @Test fun anIgnoredUnlockEndsWithoutConfirmationAfterThreeSeconds() = runTest {
        val f = fake()
        connected(f)
        f.ignoreNextCommand()
        val res = async { f.unlock(Pin.of("1357")) }
        advanceTimeBy(2_999)
        assertFalse(res.isCompleted)
        advanceTimeBy(2)
        assertEquals(UnlockResult.Unconfirmed(UnconfirmedCause.Timeout), res.await())
    }

    @Test fun disconnectingWhileWaitingEndsWithoutConfirmation() = runTest {
        val f = fake()
        connected(f)
        val res = async { f.unlock(Pin.of("1357")) }
        advanceTimeBy(100)
        f.disconnect()
        advanceTimeBy(1_000)
        assertEquals(UnlockResult.Unconfirmed(UnconfirmedCause.LinkLost), res.await())
    }

    @Test fun sendStillWorksForTheOtherControls() = runTest {
        val f = fake()
        connected(f)
        assertEquals(br.dev.evaldo.meuscooter.protocol.Ack.Accepted, f.send(ScooterCommand.SetHeadlight(true)))
    }
}

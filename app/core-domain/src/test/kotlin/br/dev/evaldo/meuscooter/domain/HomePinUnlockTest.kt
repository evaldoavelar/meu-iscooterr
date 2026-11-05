package br.dev.evaldo.meuscooter.domain

import br.dev.evaldo.meuscooter.data.FakeConfig
import br.dev.evaldo.meuscooter.data.FakeScooterTransport
import br.dev.evaldo.meuscooter.protocol.Ack
import br.dev.evaldo.meuscooter.protocol.Clock
import br.dev.evaldo.meuscooter.protocol.ConnectionStatus
import br.dev.evaldo.meuscooter.protocol.Pin
import br.dev.evaldo.meuscooter.protocol.ScooterCommand
import br.dev.evaldo.meuscooter.protocol.ScooterTransport
import br.dev.evaldo.meuscooter.protocol.Telemetry
import br.dev.evaldo.meuscooter.protocol.UnconfirmedCause
import br.dev.evaldo.meuscooter.protocol.UnlockRefusal
import br.dev.evaldo.meuscooter.protocol.UnlockResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Home com desbloqueio por PIN (etapa 4.4). PIN sintético 1357. */
@OptIn(ExperimentalCoroutinesApi::class)
class HomePinUnlockTest {
    private fun state(t: Telemetry?, now: Long = 1_000, unlock: UnlockStatus? = null) = HomeState(
        connection = ConnectionStatus.Connected, telemetry = t, nowMs = now, meter1AtMs = t?.receivedAtMs, pinUnlock = true, unlock = unlock,
    )

    private fun tel(at: Long = 1_000, speed: Float? = 0f, locked: Boolean? = true) = Telemetry(receivedAtMs = at, speedKmh = speed, locked = locked)
    private fun disabled(r: DisabledReason) = Availability.Disabled(r)

    // ---------- Reducer ----------

    @Test fun unlockEnabledOnlyWhenLockedStillFreshAndConnected() {
        assertEquals(Availability.Enabled, HomeReducer.pinUnlockAvailability(state(tel())))
        assertEquals(disabled(DisabledReason.NotConnected), HomeReducer.pinUnlockAvailability(state(tel()).copy(connection = ConnectionStatus.Disconnected)))
        assertEquals(disabled(DisabledReason.SpeedUnknown), HomeReducer.pinUnlockAvailability(state(tel(speed = null))))
        assertEquals(disabled(DisabledReason.Moving), HomeReducer.pinUnlockAvailability(state(tel(speed = 4f))))
        assertEquals(disabled(DisabledReason.StateUnknown), HomeReducer.pinUnlockAvailability(state(tel(locked = null))))
        assertEquals(disabled(DisabledReason.StateUnknown), HomeReducer.pinUnlockAvailability(state(tel(locked = false))))
        assertEquals(disabled(DisabledReason.TelemetryStale), HomeReducer.pinUnlockAvailability(state(tel(), now = 3_001)))
        assertEquals(disabled(DisabledReason.TelemetryStale), HomeReducer.pinUnlockAvailability(state(tel()).copy(meter1AtMs = null)))
        assertEquals(disabled(DisabledReason.Pending), HomeReducer.pinUnlockAvailability(state(tel(), unlock = UnlockStatus.Waiting(900))))
    }

    @Test fun inPinModeTheGenericLockCommandsAreNeverSent() {
        listOf(true, false).forEach { locked ->
            listOf(true, false).forEach { target ->
                val r = HomeReducer.reduce(state(tel(locked = locked)), HomeEvent.UserRequested(ScooterCommand.SetLocked(target), 1_000))
                assertTrue(r.effects.isEmpty())
                assertTrue(r.state.pending.isEmpty())
            }
        }
    }

    @Test fun anUnlockRequestStartsOnceAndWaits() {
        val r = HomeReducer.reduce(state(tel()), HomeEvent.UnlockRequested(1_000))
        assertEquals(listOf(HomeEffect.StartUnlock), r.effects)
        assertEquals(UnlockStatus.Waiting(1_000), r.state.unlock)
        val again = HomeReducer.reduce(r.state, HomeEvent.UnlockRequested(1_100))
        assertTrue(again.effects.isEmpty())
    }

    @Test fun aRefusedRequestDoesNothing() {
        val r = HomeReducer.reduce(state(tel(speed = 3f)), HomeEvent.UnlockRequested(1_000))
        assertTrue(r.effects.isEmpty())
        assertNull(r.state.unlock)
        val notPinMode = HomeReducer.reduce(state(tel()).copy(pinUnlock = false), HomeEvent.UnlockRequested(1_000))
        assertTrue(notPinMode.effects.isEmpty())
    }

    @Test fun unlockedIsShownBrieflyFailuresStayUntilTheNextAttempt() {
        val ok = HomeReducer.reduce(state(tel(), unlock = UnlockStatus.Waiting(1_000)), HomeEvent.UnlockFinished(UnlockResult.Unlocked, 1_500)).state
        assertEquals(UnlockStatus.Finished(UnlockResult.Unlocked, 1_500), ok.unlock)
        assertEquals(ok.unlock, HomeReducer.reduce(ok, HomeEvent.Tick(3_499)).state.unlock)
        assertNull(HomeReducer.reduce(ok, HomeEvent.Tick(3_500)).state.unlock)
        val wrong = HomeReducer.reduce(state(tel(), unlock = UnlockStatus.Waiting(1_000)), HomeEvent.UnlockFinished(UnlockResult.PinRejected, 1_500)).state
        assertEquals(UnlockStatus.Finished(UnlockResult.PinRejected, 1_500), HomeReducer.reduce(wrong, HomeEvent.Tick(60_000)).state.unlock)
        // Nova tentativa (ação do usuário) substitui o aviso anterior.
        val retry = HomeReducer.reduce(wrong.copy(nowMs = 2_000, telemetry = tel(at = 2_000), meter1AtMs = 2_000), HomeEvent.UnlockRequested(2_000))
        assertEquals(UnlockStatus.Waiting(2_000), retry.state.unlock)
    }

    // ---------- Controller ----------

    private class Spy(private val d: FakeScooterTransport) : ScooterTransport by d {
        val pins = mutableListOf<Pin>()
        val sent = mutableListOf<ScooterCommand>()
        override suspend fun unlock(pin: Pin): UnlockResult { pins += pin; return d.unlock(pin) }
        override suspend fun send(command: ScooterCommand): Ack { sent += command; return d.send(command) }
    }

    private suspend fun TestScope.rig(pin: String? = "1357"): Pair<Spy, HomeController> {
        val clock = Clock { currentTime }
        val spy = Spy(FakeScooterTransport(backgroundScope, clock, FakeConfig(), pinLock = pin?.let(Pin::of)))
        val c = HomeController(spy, backgroundScope, clock)
        runCurrent()
        c.connect(); advanceTimeBy(900); runCurrent()
        return spy to c
    }

    @Test fun pinModeComesFromTheTransport() = runTest {
        assertTrue(rig().second.state.value.pinUnlock)
        assertFalse(rig(pin = null).second.state.value.pinUnlock)
    }

    @Test fun theRightPinUnlocksThroughTheTransportAndThePinIsWipedAfterUse() = runTest {
        val (spy, c) = rig()
        val pin = Pin.of("1357")
        c.unlock(pin)
        runCurrent()
        assertTrue(c.state.value.unlock is UnlockStatus.Waiting)
        advanceTimeBy(1_000)
        assertEquals(UnlockResult.Unlocked, (c.state.value.unlock as UnlockStatus.Finished).result)
        assertEquals(false, c.state.value.telemetry?.locked)
        assertEquals(1, spy.pins.size)
        assertTrue(pin.isWiped)
        assertTrue(spy.sent.isEmpty())
    }

    @Test fun aWrongPinEndsAsRejectedWithASingleAttempt() = runTest {
        val (spy, c) = rig()
        c.unlock(Pin.of("2468"))
        advanceTimeBy(5_000)
        assertEquals(UnlockResult.PinRejected, (c.state.value.unlock as UnlockStatus.Finished).result)
        assertEquals(1, spy.pins.size)
    }

    @Test fun aSecondRequestWhileWaitingIsIgnoredAndItsPinWiped() = runTest {
        val (spy, c) = rig()
        c.unlock(Pin.of("1357"))
        val second = Pin.of("1357")
        c.unlock(second)
        assertTrue(second.isWiped)
        advanceTimeBy(1_000)
        assertEquals(1, spy.pins.size)
    }

    @Test fun aRequestRefusedByTheDomainNeverReachesTheTransport() = runTest {
        val (spy, c) = rig()
        c.disconnect(); runCurrent()
        val pin = Pin.of("1357")
        c.unlock(pin)
        advanceTimeBy(1_000)
        assertTrue(spy.pins.isEmpty())
        assertTrue(pin.isWiped)
    }

    @Test fun noConfirmationIsReported() = runTest {
        val clock = Clock { currentTime }
        val fake = FakeScooterTransport(backgroundScope, clock, FakeConfig(), pinLock = Pin.of("1357"))
        val c = HomeController(fake, backgroundScope, clock)
        runCurrent(); c.connect(); advanceTimeBy(900); runCurrent()
        fake.ignoreNextCommand()
        c.unlock(Pin.of("1357"))
        advanceTimeBy(3_100)
        assertEquals(UnlockResult.Unconfirmed(UnconfirmedCause.Timeout), (c.state.value.unlock as UnlockStatus.Finished).result)
    }

    @Test fun aTransportRefusalIsReportedAsSuch() = runTest {
        // O domínio deixa passar (mesmas regras), mas o transporte tem a palavra final.
        val clock = Clock { currentTime }
        val fake = FakeScooterTransport(backgroundScope, clock, FakeConfig(), pinLock = Pin.of("1357"))
        val refusing = object : ScooterTransport by fake {
            override suspend fun unlock(pin: Pin) = UnlockResult.Refused(UnlockRefusal.LinkNotReady)
        }
        val c = HomeController(refusing, backgroundScope, clock)
        runCurrent(); c.connect(); advanceTimeBy(900); runCurrent()
        c.unlock(Pin.of("1357"))
        runCurrent()
        assertEquals(UnlockResult.Refused(UnlockRefusal.LinkNotReady), (c.state.value.unlock as UnlockStatus.Finished).result)
    }
}

package br.dev.evaldo.meuscooter.domain

import br.dev.evaldo.meuscooter.data.FakeConfig
import br.dev.evaldo.meuscooter.data.FakeScooterTransport
import br.dev.evaldo.meuscooter.protocol.Ack
import br.dev.evaldo.meuscooter.protocol.Clock
import br.dev.evaldo.meuscooter.protocol.ConnectionStatus
import br.dev.evaldo.meuscooter.protocol.LockRefusal
import br.dev.evaldo.meuscooter.protocol.LockResult
import br.dev.evaldo.meuscooter.protocol.Pin
import br.dev.evaldo.meuscooter.protocol.ScooterCommand
import br.dev.evaldo.meuscooter.protocol.ScooterTransport
import br.dev.evaldo.meuscooter.protocol.Telemetry
import br.dev.evaldo.meuscooter.protocol.UnconfirmedCause
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Home com bloqueio temporário (LOCK modo 2, sem PIN; S20). */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeLockModeTwoTest {
    private fun state(t: Telemetry?, now: Long = 1_000, lock: LockStatus? = null, unlock: UnlockStatus? = null) = HomeState(
        connection = ConnectionStatus.Connected, telemetry = t, nowMs = now, meter1AtMs = t?.receivedAtMs,
        pinUnlock = true, lockMode2 = true, lock = lock, unlock = unlock,
    )

    private fun tel(at: Long = 1_000, speed: Float? = 0f, locked: Boolean? = false, moving: Boolean? = null) =
        Telemetry(receivedAtMs = at, speedKmh = speed, locked = locked, wheelMoving = moving)

    private fun disabled(r: DisabledReason) = Availability.Disabled(r)

    @Test fun lockEnabledOnlyWhenUnlockedStillFreshStoppedAndConnected() {
        assertEquals(Availability.Enabled, HomeReducer.lockAvailability(state(tel())))
        assertEquals(disabled(DisabledReason.NotConnected), HomeReducer.lockAvailability(state(tel()).copy(connection = ConnectionStatus.Disconnected)))
        assertEquals(disabled(DisabledReason.NotAvailable), HomeReducer.lockAvailability(state(tel()).copy(lockMode2 = false)))
        assertEquals(disabled(DisabledReason.SpeedUnknown), HomeReducer.lockAvailability(state(tel(speed = null))))
        assertEquals(disabled(DisabledReason.Moving), HomeReducer.lockAvailability(state(tel(speed = 4f))))
        assertEquals(disabled(DisabledReason.Moving), HomeReducer.lockAvailability(state(tel(moving = true))))
        assertEquals(disabled(DisabledReason.StateUnknown), HomeReducer.lockAvailability(state(tel(locked = null))))
        assertEquals(disabled(DisabledReason.StateUnknown), HomeReducer.lockAvailability(state(tel(locked = true))))
        assertEquals(disabled(DisabledReason.TelemetryStale), HomeReducer.lockAvailability(state(tel(), now = 3_001)))
        assertEquals(disabled(DisabledReason.TelemetryStale), HomeReducer.lockAvailability(state(tel()).copy(meter1AtMs = null)))
        assertEquals(disabled(DisabledReason.Pending), HomeReducer.lockAvailability(state(tel(), lock = LockStatus.Waiting(900))))
        assertEquals(disabled(DisabledReason.Pending), HomeReducer.lockAvailability(state(tel(), unlock = UnlockStatus.Waiting(900))))
    }

    @Test fun aLockRequestStartsOnceAndWaitsAndNeverUsesTheGenericCommand() {
        val r = HomeReducer.reduce(state(tel()), HomeEvent.LockRequested(1_000))
        assertEquals(listOf(HomeEffect.StartLock), r.effects)
        assertEquals(LockStatus.Waiting(1_000), r.state.lock)
        assertTrue(r.state.pending.isEmpty())
        assertTrue(HomeReducer.reduce(r.state, HomeEvent.LockRequested(1_100)).effects.isEmpty())
        // O comando genérico segue recusado no modo real-like.
        assertTrue(HomeReducer.reduce(state(tel()), HomeEvent.UserRequested(ScooterCommand.SetLocked(true), 1_000)).effects.isEmpty())
    }

    @Test fun aRefusedRequestDoesNothing() {
        val moving = HomeReducer.reduce(state(tel(speed = 3f)), HomeEvent.LockRequested(1_000))
        assertTrue(moving.effects.isEmpty())
        assertNull(moving.state.lock)
    }

    @Test fun anUnlockIsRefusedWhileALockWaitsAndAUnlockRequestClearsAnOldLockResult() {
        val waiting = state(tel(locked = true), lock = LockStatus.Waiting(900))
        assertEquals(disabled(DisabledReason.Pending), HomeReducer.pinUnlockAvailability(waiting))
        val old = state(tel(locked = true), lock = LockStatus.Finished(LockResult.Locked, 500))
        val r = HomeReducer.reduce(old, HomeEvent.UnlockRequested(1_000))
        assertNull(r.state.lock)
        assertEquals(listOf(HomeEffect.StartUnlock), r.effects)
    }

    @Test fun lockedIsShownBrieflyFailuresStayUntilTheNextAttempt() {
        val ok = HomeReducer.reduce(state(tel(), lock = LockStatus.Waiting(1_000)), HomeEvent.LockFinished(LockResult.Locked, 1_500)).state
        assertEquals(LockStatus.Finished(LockResult.Locked, 1_500), ok.lock)
        assertEquals(ok.lock, HomeReducer.reduce(ok, HomeEvent.Tick(3_499)).state.lock)
        assertNull(HomeReducer.reduce(ok, HomeEvent.Tick(3_500)).state.lock)
        val bad = HomeReducer.reduce(
            state(tel(), lock = LockStatus.Waiting(1_000)), HomeEvent.LockFinished(LockResult.Unconfirmed(UnconfirmedCause.Timeout), 1_500),
        ).state
        assertEquals(bad.lock, HomeReducer.reduce(bad, HomeEvent.Tick(60_000)).state.lock)
    }

    // ---------- Controller ----------

    private class Spy(private val d: FakeScooterTransport) : ScooterTransport by d {
        var locks = 0
        val sent = mutableListOf<ScooterCommand>()
        override suspend fun lock(): LockResult { locks++; return d.lock() }
        override suspend fun send(command: ScooterCommand): Ack { sent += command; return d.send(command) }
    }

    /** Conectado e DESBLOQUEADO (o simulado nasce bloqueado: desbloqueia pelo próprio transporte). */
    private suspend fun TestScope.rig(pin: Boolean = true): Triple<FakeScooterTransport, Spy, HomeController> {
        val clock = Clock { currentTime }
        val fake = FakeScooterTransport(backgroundScope, clock, FakeConfig(), pinLock = if (pin) Pin.of("1357") else null)
        val spy = Spy(fake)
        val c = HomeController(spy, backgroundScope, clock)
        runCurrent()
        c.connect(); advanceTimeBy(900); runCurrent()
        if (pin) { c.unlock(Pin.of("1357")); advanceTimeBy(1_000); runCurrent() }
        return Triple(fake, spy, c)
    }

    @Test fun lockModeComesFromTheTransport() = runTest {
        assertTrue(rig().third.state.value.lockMode2)
        assertEquals(false, rig(pin = false).third.state.value.lockMode2)
    }

    @Test fun lockGoesThroughTheTransportAndOnlyTelemetryConcludesIt() = runTest {
        val (_, spy, c) = rig()
        assertEquals(false, c.state.value.telemetry?.locked)
        c.lock()
        runCurrent()
        assertTrue(c.state.value.lock is LockStatus.Waiting)
        assertEquals(false, c.state.value.telemetry?.locked) // ainda não concluído
        advanceTimeBy(1_000)
        assertEquals(LockResult.Locked, (c.state.value.lock as LockStatus.Finished).result)
        assertEquals(true, c.state.value.telemetry?.locked)
        assertEquals(1, spy.locks)
        assertTrue(spy.sent.isEmpty())
    }

    @Test fun aSecondRequestWhileWaitingIsIgnored() = runTest {
        val (_, spy, c) = rig()
        c.lock(); c.lock()
        advanceTimeBy(1_000)
        assertEquals(1, spy.locks)
    }

    @Test fun aRequestRefusedByTheDomainNeverReachesTheTransport() = runTest {
        val (fake, spy, c) = rig()
        fake.setSpeed(5f)
        advanceTimeBy(600)
        c.lock()
        advanceTimeBy(1_000)
        assertEquals(0, spy.locks)
    }

    @Test fun noConfirmationIsReportedAndATransportRefusalIsReportedAsSuch() = runTest {
        val (fake, _, c) = rig()
        fake.ignoreNextCommand()
        c.lock()
        advanceTimeBy(3_100)
        assertEquals(LockResult.Unconfirmed(UnconfirmedCause.Timeout), (c.state.value.lock as LockStatus.Finished).result)
        val clock = Clock { currentTime }
        val refusing = object : ScooterTransport by fake {
            override suspend fun lock() = LockResult.Refused(LockRefusal.LinkNotReady)
        }
        val c2 = HomeController(refusing, backgroundScope, clock)
        runCurrent(); c2.connect(); advanceTimeBy(900); runCurrent()
        c2.lock()
        runCurrent()
        assertEquals(LockResult.Refused(LockRefusal.LinkNotReady), (c2.state.value.lock as LockStatus.Finished).result)
    }
}

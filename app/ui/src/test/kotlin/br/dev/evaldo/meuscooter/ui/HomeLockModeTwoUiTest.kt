package br.dev.evaldo.meuscooter.ui

import androidx.lifecycle.viewModelScope
import br.dev.evaldo.meuscooter.data.FakeConfig
import br.dev.evaldo.meuscooter.data.FakeScooterTransport
import br.dev.evaldo.meuscooter.domain.HomeState
import br.dev.evaldo.meuscooter.domain.LockStatus
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Bloqueio temporário modo 2 na Home (S20): mapper e ViewModel sobre o simulado. Sem PIN, sem teclado. */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeLockModeTwoUiTest {
    private val mapper = HomeUiMapper(emptyList(), "iX7 Pro") { "14:32" }

    private fun tel(locked: Boolean? = false, speed: Float? = 0f, moving: Boolean? = null) =
        Telemetry(receivedAtMs = 1_000, locked = locked, speedKmh = speed, batteryPercent = 100, speedLimitKmh = 50, wheelMoving = moving)

    private fun st(t: Telemetry = tel(), lock: LockStatus? = null, now: Long = 1_000, lockMode2: Boolean = true, pinUnlock: Boolean = true) = HomeState(
        connection = ConnectionStatus.Connected, telemetry = t, nowMs = now, meter1AtMs = t.receivedAtMs,
        pinUnlock = pinUnlock, lockMode2 = lockMode2, lock = lock,
    )

    @Test fun unlockedStoppedAndFreshOffersLockWithoutPin() {
        val b = mapper.map(st()).mainButton
        assertEquals(MainButtonKind.Lock, b.kind)
        assertEquals("Bloquear", b.label)
        assertTrue(b.enabled)
        assertFalse(b.asksPin)
    }

    @Test fun movingTellsToStopAndIsDisabled() {
        for (t in listOf(tel(speed = 5f), tel(moving = true))) {
            val b = mapper.map(st(t)).mainButton
            assertEquals(MainButtonKind.Moving, b.kind)
            assertEquals("Pare para bloquear", b.label)
            assertFalse(b.enabled)
        }
    }

    @Test fun staleOrUnknownSpeedDisablesWithTheReason() {
        assertFalse(mapper.map(st(now = 3_500)).mainButton.enabled)
        val unknown = mapper.map(st(tel(speed = null))).mainButton
        assertFalse(unknown.enabled)
        assertEquals("Velocidade desconhecida — aguarde o patinete", unknown.disabledReason)
    }

    @Test fun withoutTheTransportSupportTheLockStaysDisabled() {
        val b = mapper.map(st(lockMode2 = false)).mainButton
        assertFalse(b.enabled)
        assertEquals("Bloqueio pelo app ainda não disponível", b.disabledReason)
    }

    @Test fun waitingIsLockingNeverDone() {
        val b = mapper.map(st(lock = LockStatus.Waiting(1_000))).mainButton
        assertEquals(MainButtonKind.Pending, b.kind)
        assertEquals("Bloqueando…", b.label)
        assertFalse(b.enabled)
    }

    @Test fun lockedOnlyFromTheConfirmedResult() {
        val b = mapper.map(st(tel(locked = true), lock = LockStatus.Finished(LockResult.Locked, 1_000))).mainButton
        assertEquals(MainButtonKind.Confirmed, b.kind)
        assertEquals("Bloqueado às 14:32", b.label)
    }

    @Test fun lockModeTwoWithoutPinUnlockStillShowsLockingAndLockedAt() {
        val waiting = mapper.map(st(lock = LockStatus.Waiting(1_000), pinUnlock = false)).mainButton
        assertEquals(MainButtonKind.Pending, waiting.kind)
        assertEquals("Bloqueando…", waiting.label)
        val done = mapper.map(st(tel(locked = true), lock = LockStatus.Finished(LockResult.Locked, 1_000), pinUnlock = false)).mainButton
        assertEquals(MainButtonKind.Confirmed, done.kind)
        assertEquals("Bloqueado às 14:32", done.label)
        val idle = mapper.map(st(pinUnlock = false)).mainButton
        assertEquals(MainButtonKind.Lock, idle.kind)
        assertTrue(idle.enabled)
    }

    @Test fun lockModeTwoWithoutPinUnlockNeverAsksForAPinToUnlock() {
        val b = mapper.map(st(tel(locked = true), pinUnlock = false)).mainButton
        assertEquals(MainButtonKind.Unlock, b.kind)
        assertFalse(b.asksPin)
    }

    @Test fun alreadyLockedRefusalSaysSo() {
        val ui = mapper.map(st(lock = LockStatus.Finished(LockResult.Refused(LockRefusal.AlreadyLocked), 1_000)))
        assertEquals("Bloqueio não enviado: já bloqueado", ui.connection.label)
    }

    @Test fun failuresNeverSayDone() {
        fun label(r: LockResult) = mapper.map(st(lock = LockStatus.Finished(r, 1_000)))
        assertEquals("Sem confirmação — estado de bloqueio desconhecido", label(LockResult.Unconfirmed(UnconfirmedCause.Timeout)).connection.label)
        assertEquals("O patinete recusou o bloqueio", label(LockResult.Rejected).connection.label)
        assertEquals("Bloqueio não enviado: pare o patinete", label(LockResult.Refused(LockRefusal.Moving)).connection.label)
        for (r in listOf(LockResult.Rejected, LockResult.Refused(LockRefusal.Moving), LockResult.Unconfirmed(UnconfirmedCause.LinkLost))) {
            val ui = label(r)
            assertEquals(ConnectionKind.Error, ui.connection.kind)
            assertFalse(ui.mainButton.kind == MainButtonKind.Confirmed)
        }
    }

    @Test fun everyLockRefusalHasAText() {
        for (r in LockRefusal.entries) {
            assertTrue(mapper.map(st(lock = LockStatus.Finished(LockResult.Refused(r), 1_000))).connection.label.startsWith("Bloqueio não enviado: "))
        }
    }

    // ---------- ViewModel ----------

    private class Spy : (CoroutineScope, Clock) -> ScooterTransport {
        lateinit var fake: FakeScooterTransport
        val sent = mutableListOf<ScooterCommand>()
        var locks = 0
        override fun invoke(scope: CoroutineScope, clock: Clock): ScooterTransport {
            fake = FakeScooterTransport(scope, clock, FakeConfig(), pinLock = Pin.of("1357"))
            return object : ScooterTransport by fake {
                override suspend fun send(command: ScooterCommand): Ack { sent += command; return fake.send(command) }
                override suspend fun lock(): LockResult { locks++; return fake.lock() }
            }
        }
    }

    private var vmToCancel: HomeViewModel? = null

    @After fun tearDown() { Dispatchers.resetMain() }

    private fun vmTest(body: suspend TestScope.() -> Unit) = runTest {
        try { body() } finally { vmToCancel?.viewModelScope?.cancel() }
    }

    private fun TestScope.vm(spy: Spy): HomeViewModel {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val clock = Clock { testScheduler.currentTime }
        return HomeViewModel(spy, clock, deviceName = null, timeLabel = { "14:32" }).also { vmToCancel = it; runCurrent() }
    }

    private suspend fun TestScope.connectedAndUnlocked(spy: Spy): HomeViewModel {
        val vm = vm(spy)
        vm.onMainButton(); advanceTimeBy(900); runCurrent()
        vm.onUnlockPin(Pin.of("1357")); advanceTimeBy(3_000); runCurrent()
        return vm
    }

    @Test fun `Bloquear envia o modo 2 uma vez, mostra Bloqueando e so confirma com a telemetria`() = vmTest {
        val spy = Spy(); val vm = connectedAndUnlocked(spy)
        assertEquals(MainButtonKind.Lock, vm.uiState.value.mainButton.kind)
        vm.onMainButton(); runCurrent()
        assertEquals("Bloqueando…", vm.uiState.value.mainButton.label)
        advanceTimeBy(500); runCurrent()
        assertEquals("Bloqueado às 14:32", vm.uiState.value.mainButton.label)
        assertEquals(1, spy.locks)
        assertTrue(spy.sent.isEmpty())
    }

    @Test fun `em movimento o toque nao chega ao transporte`() = vmTest {
        val spy = Spy(); val vm = connectedAndUnlocked(spy)
        spy.fake.setSpeed(6f); advanceTimeBy(600); runCurrent()
        assertEquals(MainButtonKind.Moving, vm.uiState.value.mainButton.kind)
        vm.onMainButton(); advanceTimeBy(1_000); runCurrent()
        assertEquals(0, spy.locks)
    }

    @Test fun `sem confirmacao em 3 s mostra erro e nunca Bloqueado`() = vmTest {
        val spy = Spy(); val vm = connectedAndUnlocked(spy)
        spy.fake.ignoreNextCommand()
        vm.onMainButton(); advanceTimeBy(3_100); runCurrent()
        assertEquals("Sem confirmação — estado de bloqueio desconhecido", vm.uiState.value.connection.label)
        assertFalse(vm.uiState.value.mainButton.kind == MainButtonKind.Confirmed)
        assertEquals(1, spy.locks)
    }
}

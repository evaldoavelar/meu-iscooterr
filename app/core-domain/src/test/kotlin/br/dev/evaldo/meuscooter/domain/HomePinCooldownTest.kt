package br.dev.evaldo.meuscooter.domain

import br.dev.evaldo.meuscooter.data.FakeConfig
import br.dev.evaldo.meuscooter.data.FakeScooterTransport
import br.dev.evaldo.meuscooter.protocol.Clock
import br.dev.evaldo.meuscooter.protocol.ConnectionStatus
import br.dev.evaldo.meuscooter.protocol.Pin
import br.dev.evaldo.meuscooter.protocol.ScooterTransport
import br.dev.evaldo.meuscooter.protocol.Telemetry
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
import org.junit.Assert.assertTrue
import org.junit.Test

/** S29: cooldown local após 5 PINs recusados seguidos (decisão do proprietário; só no app, por sessão). */
@OptIn(ExperimentalCoroutinesApi::class)
class HomePinCooldownTest {
    private val cooldown = Availability.Disabled(DisabledReason.PinCooldown)

    private fun fresh(now: Long) = HomeState(
        connection = ConnectionStatus.Connected, telemetry = Telemetry(receivedAtMs = now, speedKmh = 0f, locked = true),
        nowMs = now, meter1AtMs = now, pinUnlock = true,
    )

    /** Mantém a telemetria fresca ao avançar o relógio (o cooldown não pode depender de dados velhos). */
    private fun HomeState.at(now: Long) = copy(telemetry = telemetry?.copy(receivedAtMs = now), meter1AtMs = now, nowMs = now)

    private fun HomeState.finish(result: UnlockResult, now: Long) = HomeReducer.reduce(this, HomeEvent.UnlockFinished(result, now)).state

    private fun HomeState.rejectN(n: Int, now: Long): HomeState {
        var s = this
        repeat(n) { s = s.finish(UnlockResult.PinRejected, now) }
        return s
    }

    @Test fun fourRejectionsDoNotLockTheFifthDoes() {
        val four = fresh(1_000).rejectN(4, 1_000)
        assertEquals(Availability.Enabled, HomeReducer.pinUnlockAvailability(four))
        val five = four.finish(UnlockResult.PinRejected, 1_000)
        assertEquals(cooldown, HomeReducer.pinUnlockAvailability(five))
        assertEquals(HomeRules.PIN_COOLDOWN_MS, five.pinCooldownRemainingMs)
    }

    @Test fun duringCooldownTheRequestIsRefusedWithNoEffect() {
        val s = fresh(1_000).rejectN(5, 1_000).at(20_000)
        val r = HomeReducer.reduce(s, HomeEvent.UnlockRequested(20_000))
        assertTrue(r.effects.isEmpty())
        assertEquals(11_000L, r.state.pinCooldownRemainingMs)
    }

    @Test fun cooldownEndsAtThirtySecondsViaTheTick() {
        val s = fresh(1_000).rejectN(5, 1_000)
        val before = HomeReducer.reduce(s.at(30_999), HomeEvent.Tick(30_999)).state
        assertEquals(cooldown, HomeReducer.pinUnlockAvailability(before))
        assertEquals(1L, before.pinCooldownRemainingMs)
        val after = HomeReducer.reduce(s.at(31_000), HomeEvent.Tick(31_000)).state
        assertEquals(Availability.Enabled, HomeReducer.pinUnlockAvailability(after))
        assertEquals(0L, after.pinCooldownRemainingMs)
        // contador zerado: precisa de mais 5 erros para travar de novo
        val again = after.rejectN(4, 31_000)
        assertEquals(Availability.Enabled, HomeReducer.pinUnlockAvailability(again))
        assertEquals(cooldown, HomeReducer.pinUnlockAvailability(again.finish(UnlockResult.PinRejected, 31_000)))
    }

    @Test fun availabilityIsEnabledExactlyAtTheEndEvenWithoutATick() {
        val s = fresh(1_000).rejectN(5, 1_000).at(31_000)
        assertEquals(Availability.Enabled, HomeReducer.pinUnlockAvailability(s))
    }

    @Test fun unlockedResetsTheCounter() {
        val s = fresh(1_000).rejectN(4, 1_000).finish(UnlockResult.Unlocked, 1_000).rejectN(4, 1_000)
        assertEquals(Availability.Enabled, HomeReducer.pinUnlockAvailability(s))
    }

    @Test fun nonConsecutiveRejectionsAreNotCounted() {
        val s = fresh(1_000).rejectN(3, 1_000).finish(UnlockResult.Unlocked, 1_000).rejectN(2, 1_000)
        assertEquals(Availability.Enabled, HomeReducer.pinUnlockAvailability(s))
    }

    @Test fun otherResultsDoNotCount() {
        var s = fresh(1_000).rejectN(4, 1_000)
        s = s.finish(UnlockResult.Refused(UnlockRefusal.LinkNotReady), 1_000)
        s = s.finish(UnlockResult.Unconfirmed(br.dev.evaldo.meuscooter.protocol.UnconfirmedCause.Timeout), 1_000)
        assertEquals(Availability.Enabled, HomeReducer.pinUnlockAvailability(s))
        assertFalse(s.pinCooldownRemainingMs > 0)
    }

    // ---------- Controller: nada chega ao transporte ----------

    private class Spy(private val d: FakeScooterTransport) : ScooterTransport by d {
        val pins = mutableListOf<Pin>()
        override suspend fun unlock(pin: Pin): UnlockResult { pins += pin; return d.unlock(pin) }
    }

    private suspend fun TestScope.rig(): Pair<Spy, HomeController> {
        val clock = Clock { currentTime }
        val spy = Spy(FakeScooterTransport(backgroundScope, clock, FakeConfig(), pinLock = Pin.of("1357")))
        val c = HomeController(spy, backgroundScope, clock)
        runCurrent()
        c.connect(); advanceTimeBy(900); runCurrent()
        return spy to c
    }

    @Test fun afterFiveWrongPinsTheTransportIsNotCalledUntilThirtySecondsPass() = runTest {
        val (spy, c) = rig()
        repeat(5) { assertTrue(c.unlock(Pin.of("2468"))); advanceTimeBy(5_000) }
        assertEquals(5, spy.pins.size)
        assertEquals(cooldown, HomeReducer.pinUnlockAvailability(c.state.value))

        val blocked = Pin.of("1357")
        assertFalse(c.unlock(blocked))
        assertTrue(blocked.isWiped)
        assertEquals(5, spy.pins.size)

        advanceTimeBy(HomeRules.PIN_COOLDOWN_MS)
        assertEquals(Availability.Enabled, HomeReducer.pinUnlockAvailability(c.state.value))
        assertTrue(c.unlock(Pin.of("1357")))
        advanceTimeBy(5_000)
        assertEquals(6, spy.pins.size)
    }
}

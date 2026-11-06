package br.dev.evaldo.meuscooter.domain

import br.dev.evaldo.meuscooter.protocol.ConnectionStatus
import br.dev.evaldo.meuscooter.protocol.LockRefusal
import br.dev.evaldo.meuscooter.protocol.LockResult
import br.dev.evaldo.meuscooter.protocol.Telemetry
import br.dev.evaldo.meuscooter.protocol.UnlockRefusal
import br.dev.evaldo.meuscooter.protocol.UnlockResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** S29: desbloqueio/bloqueio RECUSADO (nada escrito) não conta como PIN errado e o aviso some sozinho. */
class HomeRefusalVisibilityTest {
    private val base = HomeState(
        connection = ConnectionStatus.Connected, telemetry = Telemetry(receivedAtMs = 1_000, speedKmh = 0f, locked = true),
        nowMs = 1_000, meter1AtMs = 1_000, pinUnlock = true, lockMode2 = true,
    )

    @Test fun refusedUnlockDoesNotCountAsAWrongPin() {
        var s = base
        UnlockRefusal.entries.forEach { r -> s = HomeReducer.reduce(s, HomeEvent.UnlockFinished(UnlockResult.Refused(r), 1_000)).state }
        assertEquals(0, s.pinRejects)
        assertNull(s.pinCooldownUntilMs)
    }

    @Test fun refusedUnlockStaysVisibleThenExpires() {
        val s = HomeReducer.reduce(base, HomeEvent.UnlockFinished(UnlockResult.Refused(UnlockRefusal.PendingReception), 1_000)).state
        val before = HomeReducer.reduce(s, HomeEvent.Tick(1_000 + HomeRules.REFUSAL_VISIBLE_MS - 1)).state
        assertNotNull(before.unlock)
        val after = HomeReducer.reduce(s, HomeEvent.Tick(1_000 + HomeRules.REFUSAL_VISIBLE_MS)).state
        assertNull(after.unlock)
    }

    @Test fun refusedLockExpiresToo() {
        val s = HomeReducer.reduce(base, HomeEvent.LockFinished(LockResult.Refused(LockRefusal.Moving), 1_000)).state
        assertNotNull(HomeReducer.reduce(s, HomeEvent.Tick(2_000)).state.lock)
        assertNull(HomeReducer.reduce(s, HomeEvent.Tick(1_000 + HomeRules.REFUSAL_VISIBLE_MS)).state.lock)
    }

    @Test fun pinRejectedIsNotExpiredByTheRefusalRule() {
        val s = HomeReducer.reduce(base, HomeEvent.UnlockFinished(UnlockResult.PinRejected, 1_000)).state
        assertNotNull(HomeReducer.reduce(s, HomeEvent.Tick(1_000 + HomeRules.REFUSAL_VISIBLE_MS * 2)).state.unlock)
    }
}

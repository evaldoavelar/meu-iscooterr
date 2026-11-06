package br.dev.evaldo.meuscooter.ui

import br.dev.evaldo.meuscooter.domain.HomeState
import br.dev.evaldo.meuscooter.domain.LockStatus
import br.dev.evaldo.meuscooter.domain.UnlockStatus
import br.dev.evaldo.meuscooter.protocol.ConnectionStatus
import br.dev.evaldo.meuscooter.protocol.LockRefusal
import br.dev.evaldo.meuscooter.protocol.LockResult
import br.dev.evaldo.meuscooter.protocol.Telemetry
import br.dev.evaldo.meuscooter.protocol.UnlockRefusal
import br.dev.evaldo.meuscooter.protocol.UnlockResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** S29: Desbloquear/Bloquear recusado antes de escrever mostra aviso curto e específico (banner da Home). */
class HomeRefusalNoticeTest {
    private val mapper = HomeUiMapper(emptyList(), "iX7 Pro") { "14:32" }
    private fun st(unlock: UnlockStatus? = null, lock: LockStatus? = null) = HomeState(
        connection = ConnectionStatus.Connected, telemetry = Telemetry(receivedAtMs = 1_000, speedKmh = 0f, locked = true),
        nowMs = 1_000, meter1AtMs = 1_000, pinUnlock = true, lockMode2 = true, unlock = unlock, lock = lock,
    )
    private fun unlockNotice(r: UnlockRefusal) = mapper.map(st(unlock = UnlockStatus.Finished(UnlockResult.Refused(r), 1_000))).notice

    @Test fun eachUnlockRefusalHasItsOwnPortugueseNotice() {
        val texts = UnlockRefusal.entries.map { unlockNotice(it) }
        texts.forEach { assertNotNull(it) }
        assertEquals("Pare o patinete para desbloquear", unlockNotice(UnlockRefusal.Moving))
        assertEquals("Aguarde a telemetria do patinete e tente de novo", unlockNotice(UnlockRefusal.TelemetryStale))
        assertEquals("Conexão ainda não está pronta. Tente de novo em instantes", unlockNotice(UnlockRefusal.LinkNotReady))
        assertTrue(unlockNotice(UnlockRefusal.PendingReception)!!.startsWith("Aguarde a leitura"))
        assertEquals(setOf(UnlockRefusal.NotSupported, UnlockRefusal.TxDisabled).size, 2)
    }

    @Test fun eachLockRefusalHasANotice() {
        LockRefusal.entries.forEach {
            assertNotNull(mapper.map(st(lock = LockStatus.Finished(LockResult.Refused(it), 1_000))).notice)
        }
        assertEquals("Pare o patinete para bloquear", mapper.map(st(lock = LockStatus.Finished(LockResult.Refused(LockRefusal.Moving), 1_000))).notice)
    }

    @Test fun noRefusalMeansNoRefusalNotice() {
        assertNull(mapper.map(st()).notice)
        assertNull(mapper.map(st(unlock = UnlockStatus.Finished(UnlockResult.PinRejected, 1_000))).notice)
    }
}

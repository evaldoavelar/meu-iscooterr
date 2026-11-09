package br.dev.evaldo.meuscooter.domain

import br.dev.evaldo.meuscooter.protocol.Ack
import br.dev.evaldo.meuscooter.protocol.AgedTelemetry
import br.dev.evaldo.meuscooter.protocol.ConnectionStatus
import br.dev.evaldo.meuscooter.protocol.Control
import br.dev.evaldo.meuscooter.protocol.ScooterCommand
import br.dev.evaldo.meuscooter.protocol.Telemetry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S31 (revisão Sol r1): com o farol real (`headlightAvailable` sem comandos genéricos) o "concluído" vem só do resultado
 * validado do transporte (`Ack.Accepted`), nunca da telemetria acumulada nem de prazo da Home.
 */
class HomeHeadlightTransportTest {
    private val on = ScooterCommand.SetHeadlight(true)
    private val t = Telemetry(receivedAtMs = 1_000, speedKmh = 0f, locked = false, headlightOn = true)
    private val real = HomeState(
        connection = ConnectionStatus.Connected, telemetry = t, nowMs = 1_000, meter1AtMs = 1_000, meter2AtMs = 1_000,
        commandsAvailable = false, headlightAvailable = true,
    )

    private fun pendingState() = HomeReducer.reduce(real, HomeEvent.UserRequested(on, 1_000)).state

    // ---- P1 ----
    @Test fun `Meter2 com farol acumulado igual ao pedido nao confirma antes da escrita`() {
        val s = pendingState()
        val meter2 = AgedTelemetry(t.copy(receivedAtMs = 1_500), meter1AtMs = 1_000, meter2AtMs = 1_500)
        val r = HomeReducer.reduce(s, HomeEvent.AgedTelemetryReceived(meter2)).state
        assertTrue(Control.Headlight in r.pending)
        assertTrue(Control.Headlight !in r.outcomes)
    }

    @Test fun `Accepted do transporte conclui e limpa a pendencia`() {
        val r = HomeReducer.reduce(pendingState(), HomeEvent.AckReceived(Control.Headlight, Ack.Accepted, 2_000)).state
        assertTrue(Control.Headlight !in r.pending)
        assertEquals(Outcome.Confirmed(2_000, on), r.outcomes[Control.Headlight])
    }

    // ---- P3 ----
    @Test fun `a pendencia do farol nao expira antes do resultado do transporte e o Accepted tardio conclui`() {
        val s = pendingState()
        val ticked = HomeReducer.reduce(s, HomeEvent.Tick(1_000 + HomeRules.PENDING_TIMEOUT_MS + 100)).state
        assertTrue(Control.Headlight in ticked.pending)
        assertTrue(Control.Headlight !in ticked.outcomes)
        val done = HomeReducer.reduce(ticked, HomeEvent.AckReceived(Control.Headlight, Ack.Accepted, 4_100)).state
        assertEquals(Outcome.Confirmed(4_100, on), done.outcomes[Control.Headlight])
    }

    @Test fun `perda de conexao ainda falha a pendencia do farol`() {
        val r = HomeReducer.reduce(pendingState(), HomeEvent.ConnectionChanged(ConnectionStatus.Disconnected, 2_000)).state
        assertTrue(r.pending.isEmpty())
        assertTrue(r.outcomes[Control.Headlight] is Outcome.Failed)
    }

    @Test fun `com comandos genericos o fluxo antigo continua (telemetria confirma, prazo expira)`() {
        val gen = real.copy(commandsAvailable = true)
        val s = HomeReducer.reduce(gen.copy(telemetry = t.copy(headlightOn = false)), HomeEvent.UserRequested(on, 1_000)).state
        assertTrue(HomeReducer.reduce(s, HomeEvent.Tick(4_000)).state.outcomes[Control.Headlight] is Outcome.TimedOut)
    }

    // ---- P2 ----
    private fun disabled(s: HomeState, reason: DisabledReason) {
        assertEquals(Availability.Disabled(reason), HomeReducer.availability(s, on))
        val r = HomeReducer.reduce(s, HomeEvent.UserRequested(on, s.nowMs))
        assertTrue(r.effects.isEmpty())
        assertTrue(r.state.pending.isEmpty())
    }

    @Test fun `farol real so habilita desbloqueado parado fresco e sem lock ou unlock pendente`() {
        assertEquals(Availability.Enabled, HomeReducer.availability(real, on))
        disabled(real.copy(telemetry = t.copy(locked = true)), DisabledReason.Locked)
        disabled(real.copy(telemetry = t.copy(locked = null)), DisabledReason.Locked)
        disabled(real.copy(telemetry = t.copy(wheelMoving = true)), DisabledReason.Moving)
        disabled(real.copy(telemetry = t.copy(speedKmh = 3f)), DisabledReason.Moving)
        disabled(real.copy(telemetry = t.copy(speedKmh = null)), DisabledReason.SpeedUnknown)
        disabled(real.copy(nowMs = 9_000), DisabledReason.TelemetryStale)
        disabled(real.copy(meter1AtMs = null), DisabledReason.TelemetryStale)
        disabled(real.copy(telemetry = null), DisabledReason.TelemetryStale)
        disabled(real.copy(lock = LockStatus.Waiting(900)), DisabledReason.Pending)
        disabled(real.copy(unlock = UnlockStatus.Waiting(900)), DisabledReason.Pending)
        disabled(real.copy(connection = ConnectionStatus.Disconnected), DisabledReason.NotConnected)
    }

    // ---- r2: exclusão mútua nos dois sentidos (o transporte tem um comando em voo por vez) ----
    @Test fun `farol real pendente desabilita Bloquear e Desbloquear com motivo Pending`() {
        val s = pendingState().copy(lockMode2 = true, pinUnlock = true)
        assertEquals(Availability.Disabled(DisabledReason.Pending), HomeReducer.lockAvailability(s))
        assertTrue(HomeReducer.reduce(s, HomeEvent.LockRequested(1_100)).effects.isEmpty())
        val locked = s.copy(telemetry = t.copy(locked = true))
        assertEquals(Availability.Disabled(DisabledReason.Pending), HomeReducer.pinUnlockAvailability(locked))
        assertTrue(HomeReducer.reduce(locked, HomeEvent.UnlockRequested(1_100)).effects.isEmpty())
    }

    @Test fun `sem farol pendente Bloquear e Desbloquear seguem habilitados e o farol generico nao interfere`() {
        val s = real.copy(lockMode2 = true, pinUnlock = true)
        assertEquals(Availability.Enabled, HomeReducer.lockAvailability(s))
        assertEquals(Availability.Enabled, HomeReducer.pinUnlockAvailability(s.copy(telemetry = t.copy(locked = true))))
        val generic = s.copy(commandsAvailable = true).let { HomeReducer.reduce(it, HomeEvent.UserRequested(on, 1_000)).state }
        assertEquals(Availability.Enabled, HomeReducer.lockAvailability(generic))
    }
}

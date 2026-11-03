package br.dev.evaldo.meuscooter.domain

import br.dev.evaldo.meuscooter.protocol.Ack
import br.dev.evaldo.meuscooter.protocol.ConnectionStatus
import br.dev.evaldo.meuscooter.protocol.Control
import br.dev.evaldo.meuscooter.protocol.ScooterCommand
import br.dev.evaldo.meuscooter.protocol.Telemetry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Comando enviado ≠ executado" (CLAUDE.md regra 5): só a telemetria confirma.
 * Tempos de referência: ACK ~50 ms, estado ~420 ms, p95 ~680 ms [CONFIRMADO] em S2;
 * timeout de 3 s e visibilidade do "confirmado" de 2 s são [HIPÓTESE] de UX.
 */
class HomeReducerConfirmationTest {
    private val unlock = ScooterCommand.SetLocked(false)

    private fun pendingUnlock(): HomeState {
        val base = HomeState(
            connection = ConnectionStatus.Connected,
            telemetry = Telemetry(receivedAtMs = 1_000, locked = true, speedKmh = 0f),
            nowMs = 1_000,
        )
        return HomeReducer.reduce(base, HomeEvent.UserRequested(unlock, 1_000)).state
    }

    private fun telemetryAt(at: Long, locked: Boolean) = Telemetry(receivedAtMs = at, locked = locked, speedKmh = 0f)

    @Test
    fun matchingTelemetryAfterSendConfirms() {
        val r = HomeReducer.reduce(pendingUnlock(), HomeEvent.TelemetryReceived(telemetryAt(1_420, locked = false)))
        assertFalse(Control.Lock in r.state.pending)
        assertEquals(Outcome.Confirmed(atMs = 1_420), r.state.outcomes[Control.Lock])
    }

    @Test
    fun ackAloneNeverConfirms() {
        val r = HomeReducer.reduce(pendingUnlock(), HomeEvent.AckReceived(Control.Lock, Ack.Accepted, 1_050))
        assertTrue(Control.Lock in r.state.pending)
        assertNull(r.state.outcomes[Control.Lock])
    }

    @Test
    fun telemetryOlderThanTheCommandDoesNotConfirm() {
        val r = HomeReducer.reduce(pendingUnlock(), HomeEvent.TelemetryReceived(telemetryAt(900, locked = false)))
        assertTrue(Control.Lock in r.state.pending)
        assertNull(r.state.outcomes[Control.Lock])
    }

    @Test
    fun nonMatchingTelemetryKeepsPending() {
        val r = HomeReducer.reduce(pendingUnlock(), HomeEvent.TelemetryReceived(telemetryAt(1_300, locked = true)))
        assertTrue(Control.Lock in r.state.pending)
        assertEquals(1_300L, r.state.telemetry?.receivedAtMs)
    }

    @Test
    fun rejectedAckFailsWithReason() {
        val r = HomeReducer.reduce(pendingUnlock(), HomeEvent.AckReceived(Control.Lock, Ack.Rejected("recusado"), 1_050))
        assertFalse(Control.Lock in r.state.pending)
        assertEquals(Outcome.Failed("recusado", atMs = 1_050, command = unlock), r.state.outcomes[Control.Lock])
    }

    @Test
    fun timesOutExactlyAfterThreeSeconds() {
        val early = HomeReducer.reduce(pendingUnlock(), HomeEvent.Tick(3_999))
        assertTrue(Control.Lock in early.state.pending)
        val late = HomeReducer.reduce(pendingUnlock(), HomeEvent.Tick(4_000))
        assertFalse(Control.Lock in late.state.pending)
        assertEquals(Outcome.TimedOut(atMs = 4_000, command = unlock), late.state.outcomes[Control.Lock])
    }

    @Test
    fun confirmedOutcomeExpiresButFailureStays() {
        val confirmed = HomeReducer.reduce(pendingUnlock(), HomeEvent.TelemetryReceived(telemetryAt(1_420, false))).state
        assertEquals(Outcome.Confirmed(1_420), HomeReducer.reduce(confirmed, HomeEvent.Tick(3_419)).state.outcomes[Control.Lock])
        assertNull(HomeReducer.reduce(confirmed, HomeEvent.Tick(3_420)).state.outcomes[Control.Lock])
        val failed = HomeReducer.reduce(pendingUnlock(), HomeEvent.AckReceived(Control.Lock, Ack.Rejected("x"), 1_050)).state
        assertEquals(Outcome.Failed("x", 1_050, unlock), HomeReducer.reduce(failed, HomeEvent.Tick(60_000)).state.outcomes[Control.Lock])
    }

    @Test
    fun connectionLossFailsPendingOperations() {
        val r = HomeReducer.reduce(pendingUnlock(), HomeEvent.ConnectionChanged(ConnectionStatus.Disconnected, 1_200))
        assertTrue(r.state.pending.isEmpty())
        assertEquals(Outcome.Failed("connection-lost", atMs = 1_200, command = unlock), r.state.outcomes[Control.Lock])
        assertEquals(ConnectionStatus.Disconnected, r.state.connection)
    }

    @Test
    fun newRequestClearsPreviousOutcomeOfThatControl() {
        val failed = HomeReducer.reduce(pendingUnlock(), HomeEvent.AckReceived(Control.Lock, Ack.Rejected("x"), 1_050)).state
        val retry = HomeReducer.reduce(failed, HomeEvent.UserRequested(unlock, 1_100))
        assertNull(retry.state.outcomes[Control.Lock])
        assertEquals(1, retry.effects.size)
    }

    private fun timedOutUnlock() = HomeReducer.reduce(pendingUnlock(), HomeEvent.Tick(4_000)).state

    @Test
    fun lateTelemetryMatchingTimedOutCommandClearsOutcome() {
        val r = HomeReducer.reduce(timedOutUnlock(), HomeEvent.TelemetryReceived(telemetryAt(4_200, locked = false)))
        assertNull(r.state.outcomes[Control.Lock])
    }

    @Test
    fun lateTelemetryMatchingRejectedCommandClearsOutcome() {
        val rejected = HomeReducer.reduce(pendingUnlock(), HomeEvent.AckReceived(Control.Lock, Ack.Rejected("x"), 1_050)).state
        assertNull(HomeReducer.reduce(rejected, HomeEvent.TelemetryReceived(telemetryAt(1_500, locked = false))).state.outcomes[Control.Lock])
    }

    @Test
    fun telemetryNotMatchingKeepsFailure() {
        val r = HomeReducer.reduce(timedOutUnlock(), HomeEvent.TelemetryReceived(telemetryAt(4_200, locked = true)))
        assertEquals(Outcome.TimedOut(4_000, unlock), r.state.outcomes[Control.Lock])
    }

    @Test
    fun reconnectClearsConnectionLostButKeepsRejection() {
        val lost = HomeReducer.reduce(pendingUnlock(), HomeEvent.ConnectionChanged(ConnectionStatus.Disconnected, 1_200)).state
        val back = HomeReducer.reduce(lost, HomeEvent.ConnectionChanged(ConnectionStatus.Connected, 2_000)).state
        assertNull(back.outcomes[Control.Lock])
        val rejected = HomeReducer.reduce(pendingUnlock(), HomeEvent.AckReceived(Control.Lock, Ack.Rejected("x"), 1_050)).state
        val kept = HomeReducer.reduce(rejected, HomeEvent.ConnectionChanged(ConnectionStatus.Connected, 2_000)).state
        assertEquals(Outcome.Failed("x", 1_050, unlock), kept.outcomes[Control.Lock])
    }
}

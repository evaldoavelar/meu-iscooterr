package br.dev.evaldo.meuscooter.domain

import br.dev.evaldo.meuscooter.protocol.Ack
import br.dev.evaldo.meuscooter.protocol.ConnectionStatus
import br.dev.evaldo.meuscooter.protocol.Control
import br.dev.evaldo.meuscooter.protocol.ScooterCommand
import br.dev.evaldo.meuscooter.protocol.Telemetry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regras de origem (independentes do código): docs/sprints/fase-3-plano.md, decisões 3 e 6:
 * - ação só "concluída" após telemetria; sem comando duplicado com operação pendente;
 * - lock, motores, modo, limite, cruise e partida do zero desabilitados em movimento,
 *   com velocidade desconhecida ou telemetria desatualizada; desbloqueio tem regra própria;
 * - farol pode ficar acessível.
 * Latências de referência (S2, [CONFIRMADO]): ACK ~50 ms, estado ~420 ms, p95 ~680 ms.
 */
class HomeReducerTest {
    private fun tel(at: Long = 1_000, locked: Boolean? = true, speed: Float? = 0f) =
        Telemetry(receivedAtMs = at, locked = locked, speedKmh = speed, headlightOn = false)

    private fun connected(t: Telemetry? = tel(), now: Long = 1_000) = HomeState(
        connection = ConnectionStatus.Connected, telemetry = t, nowMs = now,
    )

    @Test
    fun everythingDisabledWhenNotConnected() {
        val s = HomeState(connection = ConnectionStatus.Disconnected, telemetry = tel(), nowMs = 1_000)
        Control.entries.forEach { c ->
            val cmd = sampleCommand(c)
            assertEquals(Availability.Disabled(DisabledReason.NotConnected), HomeReducer.availability(s, cmd))
        }
    }

    @Test
    fun unlockRequestSendsCommandAndMarksPending() {
        val r = HomeReducer.reduce(connected(), HomeEvent.UserRequested(ScooterCommand.SetLocked(false), 1_000))
        assertEquals(listOf<HomeEffect>(HomeEffect.Send(ScooterCommand.SetLocked(false))), r.effects)
        assertEquals(PendingOp(ScooterCommand.SetLocked(false), sentAtMs = 1_000), r.state.pending[Control.Lock])
        assertTrue(r.state.outcomes.isEmpty())
    }

    @Test
    fun duplicateRequestWhilePendingIsIgnored() {
        val first = HomeReducer.reduce(connected(), HomeEvent.UserRequested(ScooterCommand.SetLocked(false), 1_000))
        val second = HomeReducer.reduce(first.state, HomeEvent.UserRequested(ScooterCommand.SetLocked(false), 1_100))
        assertTrue(second.effects.isEmpty())
        assertEquals(first.state.pending, second.state.pending)
        assertEquals(
            Availability.Disabled(DisabledReason.Pending),
            HomeReducer.availability(first.state, ScooterCommand.SetLocked(false)),
        )
    }

    private fun sampleCommand(c: Control): ScooterCommand = when (c) {
        Control.Lock -> ScooterCommand.SetLocked(true)
        Control.Headlight -> ScooterCommand.SetHeadlight(true)
        Control.Motors -> ScooterCommand.SetDualMotor(true)
        Control.Mode -> ScooterCommand.SetSpeedMode(1)
        Control.Cruise -> ScooterCommand.SetCruise(true)
        Control.ZeroStart -> ScooterCommand.SetZeroStart(true)
        Control.Limit -> ScooterCommand.SetSpeedLimit(25)
        Control.Unit -> ScooterCommand.SetSpeedUnit(true)
    }
}

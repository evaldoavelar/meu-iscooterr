package br.dev.evaldo.meuscooter.domain

import br.dev.evaldo.meuscooter.protocol.ConnectionStatus
import br.dev.evaldo.meuscooter.protocol.ScooterCommand
import br.dev.evaldo.meuscooter.protocol.Telemetry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Transporte real não envia comandos genéricos (só Lock/Unlock): a Home desabilita com motivo claro, sem enviar nada. */
class HomeCommandsUnavailableTest {
    private val t = Telemetry(receivedAtMs = 1_000, speedKmh = 0f, locked = false)
    private val state = HomeState(
        connection = ConnectionStatus.Connected, telemetry = t, nowMs = 1_000, meter1AtMs = 1_000, meter2AtMs = 1_000,
        commandsAvailable = false,
    )
    private val unavailable = Availability.Disabled(DisabledReason.CommandsUnavailable)

    @Test fun `todo comando generico fica desabilitado com o motivo proprio`() {
        listOf(
            ScooterCommand.SetHeadlight(true), ScooterCommand.SetDualMotor(true), ScooterCommand.SetCruise(true),
            ScooterCommand.SetZeroStart(true), ScooterCommand.SetSpeedMode(1), ScooterCommand.SetSpeedLimit(20),
            ScooterCommand.SetLocked(true), ScooterCommand.SetLocked(false),
        ).forEach { assertEquals(it.toString(), unavailable, HomeReducer.availability(state, it)) }
    }

    @Test fun `pedido do usuario nao envia nada nem cria pendencia`() {
        val r = HomeReducer.reduce(state, HomeEvent.UserRequested(ScooterCommand.SetHeadlight(true), 1_000))
        assertTrue(r.effects.isEmpty())
        assertTrue(r.state.pending.isEmpty())
    }

    @Test fun `desconectado continua dizendo NotConnected`() {
        assertEquals(
            Availability.Disabled(DisabledReason.NotConnected),
            HomeReducer.availability(state.copy(connection = ConnectionStatus.Disconnected), ScooterCommand.SetHeadlight(true)),
        )
    }

    @Test fun `com comandos disponiveis o farol segue habilitado`() {
        assertEquals(Availability.Enabled, HomeReducer.availability(state.copy(commandsAvailable = true), ScooterCommand.SetHeadlight(true)))
    }
}

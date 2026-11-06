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

    @Test fun `bloqueio e desbloqueio reais seguem habilitados com comandos genericos indisponiveis`() {
        val unlocked = state.copy(pinUnlock = true, lockMode2 = true)
        assertEquals(Availability.Enabled, HomeReducer.lockAvailability(unlocked))
        val locked = unlocked.copy(telemetry = t.copy(locked = true))
        assertEquals(Availability.Enabled, HomeReducer.pinUnlockAvailability(locked))
    }

    @Test fun `controlador repassa genericCommandsAvailable do transporte`() = kotlinx.coroutines.test.runTest {
        val fake = br.dev.evaldo.meuscooter.data.FakeScooterTransport(backgroundScope, br.dev.evaldo.meuscooter.protocol.Clock { 0L })
        val noGeneric = object : br.dev.evaldo.meuscooter.protocol.ScooterTransport by fake {
            override val genericCommandsAvailable: Boolean get() = false
        }
        assertEquals(false, HomeController(noGeneric, backgroundScope, br.dev.evaldo.meuscooter.protocol.Clock { 0L }).state.value.commandsAvailable)
        assertEquals(true, HomeController(fake, backgroundScope, br.dev.evaldo.meuscooter.protocol.Clock { 0L }).state.value.commandsAvailable)
    }
}

/** P3 (S20): velocidade negativa/NaN não prova "parado"; falha segura = em movimento. */
class HomeNegativeSpeedTest {
    private fun state(speed: Float) = HomeState(
        connection = ConnectionStatus.Connected,
        telemetry = Telemetry(receivedAtMs = 1_000, speedKmh = speed, locked = false),
        nowMs = 1_000, meter1AtMs = 1_000, meter2AtMs = 1_000,
    )

    @Test fun `velocidade negativa ou NaN desabilita comandos restritos e o desbloqueio legado`() {
        for (v in listOf(-0.1f, -5f, Float.NaN)) {
            val moving = Availability.Disabled(DisabledReason.Moving)
            assertEquals("v=$v", moving, HomeReducer.availability(state(v), ScooterCommand.SetSpeedLimit(20)))
            assertEquals("v=$v", moving, HomeReducer.availability(state(v), ScooterCommand.SetLocked(false)))
        }
    }

    @Test fun `velocidade zero continua habilitando`() {
        assertEquals(Availability.Enabled, HomeReducer.availability(state(0f), ScooterCommand.SetSpeedLimit(20)))
    }
}

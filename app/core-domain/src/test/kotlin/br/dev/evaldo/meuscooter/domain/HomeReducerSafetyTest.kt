package br.dev.evaldo.meuscooter.domain

import br.dev.evaldo.meuscooter.protocol.ConnectionStatus
import br.dev.evaldo.meuscooter.protocol.ScooterCommand
import br.dev.evaldo.meuscooter.protocol.Telemetry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fonte: docs/sprints/fase-3-plano.md, decisão 3. Restritos = bloqueio, motores, modo, limite,
 * cruise, partida do zero. Farol acessível. Desbloqueio com regra própria.
 * Limiar de "desatualizada" (2 s, ~6 períodos de telemetria de ~300 ms) é [HIPÓTESE].
 */
class HomeReducerSafetyTest {
    private val restricted = listOf(
        ScooterCommand.SetLocked(true),
        ScooterCommand.SetDualMotor(true),
        ScooterCommand.SetSpeedMode(2),
        ScooterCommand.SetSpeedLimit(30),
        ScooterCommand.SetCruise(true),
        ScooterCommand.SetZeroStart(true),
    )

    private fun state(t: Telemetry?, now: Long = 1_000) =
        HomeState(connection = ConnectionStatus.Connected, telemetry = t, nowMs = now)

    private fun tel(at: Long = 1_000, speed: Float? = 0f, locked: Boolean? = false) =
        Telemetry(receivedAtMs = at, speedKmh = speed, locked = locked)

    private fun disabled(reason: DisabledReason) = Availability.Disabled(reason)

    @Test
    fun restrictedControlsEnabledWhenStillAndFresh() {
        restricted.forEach { assertEquals(Availability.Enabled, HomeReducer.availability(state(tel()), it)) }
    }

    @Test
    fun restrictedControlsDisabledWhileMoving() {
        restricted.forEach { assertEquals(disabled(DisabledReason.Moving), HomeReducer.availability(state(tel(speed = 0.1f)), it)) }
    }

    @Test
    fun restrictedControlsDisabledWhenSpeedUnknown() {
        restricted.forEach { assertEquals(disabled(DisabledReason.SpeedUnknown), HomeReducer.availability(state(tel(speed = null)), it)) }
        restricted.forEach { assertEquals(disabled(DisabledReason.SpeedUnknown), HomeReducer.availability(state(null), it)) }
    }

    @Test
    fun restrictedControlsDisabledWhenTelemetryStale() {
        restricted.forEach { assertEquals(disabled(DisabledReason.TelemetryStale), HomeReducer.availability(state(tel(at = 1_000), now = 3_001), it)) }
    }

    @Test
    fun telemetryExactlyTwoSecondsOldIsStillFresh() {
        assertEquals(Availability.Enabled, HomeReducer.availability(state(tel(at = 1_000), now = 3_000), ScooterCommand.SetCruise(true)))
    }

    @Test
    fun headlightStaysAvailableMovingStaleOrUnknown() {
        val cmd = ScooterCommand.SetHeadlight(true)
        listOf(state(tel(speed = 20f)), state(tel(), now = 9_000), state(tel(speed = null)), state(null)).forEach {
            assertEquals(Availability.Enabled, HomeReducer.availability(it, cmd))
        }
    }

    @Test
    fun unlockHasItsOwnRule() {
        val unlock = ScooterCommand.SetLocked(false)
        // permitido com a última telemetria "bloqueado" mesmo desatualizada ou sem velocidade
        assertEquals(Availability.Enabled, HomeReducer.availability(state(tel(locked = true), now = 9_000), unlock))
        assertEquals(Availability.Enabled, HomeReducer.availability(state(tel(locked = true, speed = null)), unlock))
        // movimento ainda bloqueia; estado de bloqueio desconhecido também
        assertEquals(disabled(DisabledReason.Moving), HomeReducer.availability(state(tel(locked = true, speed = 3f)), unlock))
        assertEquals(disabled(DisabledReason.StateUnknown), HomeReducer.availability(state(tel(locked = null)), unlock))
        assertEquals(disabled(DisabledReason.StateUnknown), HomeReducer.availability(state(null), unlock))
    }

    @Test
    fun disabledRequestProducesNoEffect() {
        val r = HomeReducer.reduce(state(tel(speed = 5f)), HomeEvent.UserRequested(ScooterCommand.SetLocked(true), 1_000))
        assertTrue(r.effects.isEmpty())
        assertTrue(r.state.pending.isEmpty())
    }
}

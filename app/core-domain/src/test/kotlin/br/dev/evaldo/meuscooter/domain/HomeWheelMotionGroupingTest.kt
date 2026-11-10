package br.dev.evaldo.meuscooter.domain

import br.dev.evaldo.meuscooter.protocol.AgedTelemetry
import br.dev.evaldo.meuscooter.protocol.ConnectionStatus
import br.dev.evaldo.meuscooter.protocol.FieldGroup
import br.dev.evaldo.meuscooter.protocol.ScooterCommand
import br.dev.evaldo.meuscooter.protocol.Telemetry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S20 (itens BAIXO da revisão da S19): quem prova "parado" é o Meter1 (ADR 0005). O Meter2 renova `receivedAtMs`
 * mas não carrega velocidade/movimento. Cobre Meter1 nunca visto, estado sem idades por grupo e bloqueio com Meter1 velho.
 */
class HomeWheelMotionGroupingTest {
    private val now = 10_000L
    private val restricted = listOf(
        ScooterCommand.SetLocked(true),
        ScooterCommand.SetDualMotor(true),
        ScooterCommand.SetSpeedMode(2),
        ScooterCommand.SetSpeedLimit(30),
        ScooterCommand.SetCruise(true),
        ScooterCommand.SetZeroStart(true),
    )
    /** Sem idades por grupo, SetLocked(true) cai antes em meter1Stale (Meter1 "nunca visto"): ver teste dedicado. */
    private val restrictedExceptLock = restricted - ScooterCommand.SetLocked(true)
    private val stale = Availability.Disabled(DisabledReason.TelemetryStale)

    private fun state(t: Telemetry?, meter1At: Long?, meter2At: Long?, nowMs: Long = now, pinUnlock: Boolean = false) =
        HomeState(ConnectionStatus.Connected, t, nowMs, meter1AtMs = meter1At, meter2AtMs = meter2At, pinUnlock = pinUnlock)

    private fun assertNoEffect(s: HomeState, command: ScooterCommand) {
        val r = HomeReducer.reduce(s, HomeEvent.UserRequested(command, s.nowMs))
        assertTrue(r.effects.isEmpty())
        assertTrue(r.state.pending.isEmpty())
    }

    // ---------- (a) Meter1 nunca recebido + Meter2 fresco ----------

    /** Telemetria só com Meter2: velocidade/movimento desconhecidos (o decoder não os preenche no Meter2). */
    private val meter2Only = Telemetry(receivedAtMs = now - 100, batteryPercent = null)

    @Test fun neverSeenMeter1WithFreshMeter2DisablesEveryRestrictedAction() {
        // Mesmo que um valor "0 km/h" residual exista, sem Meter1 nunca há prova de parada.
        for (t in listOf(meter2Only, meter2Only.copy(speedKmh = 0f, wheelMoving = false))) {
            val s = state(t, meter1At = null, meter2At = now - 100)
            assertTrue(s.meter1Stale)
            for (command in restricted) {
                assertEquals(command.toString(), stale, HomeReducer.availability(s, command))
                assertNoEffect(s, command)
            }
        }
    }

    @Test fun neverSeenMeter1RefusesPinUnlockButLegacyUnlockFollowsTheOwnerRule() {
        val s = state(meter2Only, meter1At = null, meter2At = now - 100)
        assertEquals(stale, HomeReducer.pinUnlockAvailability(s.copy(pinUnlock = true)))
        // P2-b: Desbloqueio legado (SetLocked(false)) na dúvida = bloqueado; sem Meter1 fresco -> TelemetryStale.
        assertEquals(stale, HomeReducer.availability(s, ScooterCommand.SetLocked(false)))
    }

    // ---------- (b) estado sem idades por grupo ----------

    @Test fun withoutGroupAgesTheOverallReceivedAtDecidesFreshness() {
        val t = Telemetry(receivedAtMs = now - 500, speedKmh = 0f, wheelMoving = false, locked = false)
        for (command in restrictedExceptLock) {
            assertEquals(Availability.Enabled, HomeReducer.availability(state(t, null, null), command))
            assertEquals(stale, HomeReducer.availability(state(t, null, null, nowMs = now + 1_501), command))
        }
        // Limite exclusivo: exatamente 2000 ms ainda é fresco.
        assertEquals(Availability.Enabled, HomeReducer.availability(state(t, null, null, nowMs = t.receivedAtMs + 2_000), ScooterCommand.SetCruise(true)))
    }

    @Test fun withoutGroupAgesMotionAndUnknownSpeedStillBlock() {
        val base = Telemetry(receivedAtMs = now)
        val moving = Disabled(DisabledReason.Moving)
        val unknown = Disabled(DisabledReason.SpeedUnknown)
        for (command in restrictedExceptLock) {
            assertEquals(moving, HomeReducer.availability(state(base.copy(wheelMoving = true), null, null), command))
            assertEquals(moving, HomeReducer.availability(state(base.copy(speedKmh = 3f), null, null), command))
            assertEquals(unknown, HomeReducer.availability(state(base, null, null), command))
            assertEquals(unknown, HomeReducer.availability(state(null, null, null), command))
        }
    }

    @Test fun withoutGroupAgesLockIsAlwaysRefusedAsStaleBecauseMeter1WasNeverSeen() {
        // Conservador/seguro: sem idade do Meter1 não há prova de parada para bloquear. Não ocorre com transporte real/Fake.
        val t = Telemetry(receivedAtMs = now, speedKmh = 0f, wheelMoving = false, locked = false)
        val s = state(t, null, null)
        assertTrue(s.meter1Stale)
        assertEquals(stale, HomeReducer.availability(s, ScooterCommand.SetLocked(true)))
        assertNoEffect(s, ScooterCommand.SetLocked(true))
    }

    @Test fun withoutGroupAgesPinUnlockIsStillRefused() {
        val t = Telemetry(receivedAtMs = now, speedKmh = 0f, wheelMoving = false, locked = true)
        assertEquals(stale, HomeReducer.pinUnlockAvailability(state(t, null, null, pinUnlock = true)))
    }

    /** O transporte real (e o Fake, pelo padrão da interface) sempre informa ao menos uma idade: o ramo "sem idades" não ocorre. */
    @Test fun anyAgedReadingFromATransportCarriesAtLeastOneGroupAge() {
        val m2 = Telemetry(receivedAtMs = now - 100)
        val fromBle = AgedTelemetry.empty().receive(m2, FieldGroup.Meter2) // 1º quadro é Meter2
        val fromFakeDefault = AgedTelemetry(m2, m2.receivedAtMs, m2.receivedAtMs) // padrão de ScooterTransport.agedTelemetry
        for (reading in listOf(fromBle, fromFakeDefault)) {
            val s = HomeReducer.reduce(
                HomeState(connection = ConnectionStatus.Connected, nowMs = now),
                HomeEvent.AgedTelemetryReceived(reading),
            ).state
            assertTrue(s.meter1AtMs != null || s.meter2AtMs != null)
            assertNotNull(s.telemetry)
        }
        // Só Meter2 (BLE): agrupado e Meter1 nunca visto -> restrito desabilitado, mesmo com speedKmh residual 0.
        val s = HomeReducer.reduce(
            HomeState(connection = ConnectionStatus.Connected, nowMs = now),
            HomeEvent.AgedTelemetryReceived(fromBle),
        ).state
        for (command in restricted) assertEquals(stale, HomeReducer.availability(s, command))
    }

    // ---------- (c) SetLocked(true) com Meter1 velho ----------

    @Test fun lockWithStaleMeter1IsRefusedAsTelemetryStaleWithNoEffect() {
        val t = Telemetry(receivedAtMs = now - 100, speedKmh = 0f, wheelMoving = false, locked = false)
        val s = state(t, meter1At = 1_000, meter2At = now - 100)
        val lock = ScooterCommand.SetLocked(true)
        assertEquals(stale, HomeReducer.availability(s, lock))
        assertNoEffect(s, lock)
        // Meter1 fresco no limite (2000 ms) volta a permitir.
        assertEquals(Availability.Enabled, HomeReducer.availability(s.copy(meter1AtMs = now - 2_000), lock))
        assertEquals(stale, HomeReducer.availability(s.copy(meter1AtMs = now - 2_001), lock))
    }

    @Test fun lockWithStaleMeter1StillReportsMovingWhenTheLastKnownReadingWasMoving() {
        // Movimento conhecido (mesmo velho) tem precedência sobre "velho": o motivo acionável é parar.
        val t = Telemetry(receivedAtMs = now - 100, wheelMoving = true)
        val s = state(t, meter1At = 1_000, meter2At = now - 100)
        assertEquals(Availability.Disabled(DisabledReason.Moving), HomeReducer.availability(s, ScooterCommand.SetLocked(true)))
        assertNoEffect(s, ScooterCommand.SetLocked(true))
    }

    @Test fun lockWithStaleMeter1AndUnknownSpeedReportsStaleBeforeSpeedUnknown() {
        val s = state(Telemetry(receivedAtMs = now - 100), meter1At = 1_000, meter2At = now - 100)
        assertEquals(stale, HomeReducer.availability(s, ScooterCommand.SetLocked(true)))
    }

    private fun Disabled(reason: DisabledReason) = Availability.Disabled(reason)
}

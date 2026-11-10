package br.dev.evaldo.meuscooter.domain

import br.dev.evaldo.meuscooter.protocol.ConnectionStatus
import br.dev.evaldo.meuscooter.protocol.Telemetry
import br.dev.evaldo.meuscooter.protocol.ScooterCommand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** M1 (revisão r1): Meter1 parado em t=1000 e Meter2 ativo em t=6000; agora = 6000 → bateria/bloqueio têm 5000 ms. Números à mão. */
class HomeGroupAgeTest {
    private fun connected(now: Long) = HomeState(connection = ConnectionStatus.Connected, nowMs = now)
    private fun HomeState.after(e: HomeEvent) = HomeReducer.reduce(this, e).state

    /** P2-b / S41-F48-FIX: na dúvida = bloqueado. Com Meter1 vencido, desbloqueio é desabilitado (TelemetryStale), salvo se em movimento (Moving). */
    @Test fun unlockWithStaleMeter1IsDisabledRefusingMovingOrStale() {
        for (locked in listOf(true, false, null)) {
            for (speed in listOf(0f, null, 12f)) {
                val state = connected(now = 6_000).copy(
                    telemetry = Telemetry(receivedAtMs = 6_000, speedKmh = speed, locked = locked),
                    meter1AtMs = 1_000,
                )
                assertTrue(state.meter1Stale)
                val command = ScooterCommand.SetLocked(false)
                val result = HomeReducer.reduce(state, HomeEvent.UserRequested(command, 6_000))
                if (speed != null && speed > 0f) {
                    assertEquals(Availability.Disabled(DisabledReason.Moving), HomeReducer.availability(state, command))
                    assertTrue(result.effects.isEmpty())
                } else {
                    assertEquals(Availability.Disabled(DisabledReason.TelemetryStale), HomeReducer.availability(state, command))
                    assertTrue(result.effects.isEmpty())
                    assertTrue(result.state.outcomes.isEmpty())
                }
            }
        }
    }

    @Test fun staleMeter1IsReportedEvenWhenMeter2KeepsTheOverallTelemetryFresh() {
        val s = connected(now = 6_000)
            .after(HomeEvent.TelemetryReceived(Telemetry(receivedAtMs = 6_000, speedKmh = 0f, locked = true, batteryPercent = 80)))
            .after(HomeEvent.GroupAgesReceived(meter1AtMs = 1_000, meter2AtMs = 6_000))
        assertTrue(s.meter1Stale) // 5000 ms > STALE_AFTER_MS (2000)
        assertTrue(s.nowMs - s.telemetry!!.receivedAtMs <= HomeRules.STALE_AFTER_MS) // o geral parece fresco
    }

    @Test fun neverSeenMeter1IsStaleOnceTelemetryExists() {
        val s = connected(now = 6_000)
            .after(HomeEvent.TelemetryReceived(Telemetry(receivedAtMs = 6_000, speedKmh = 0f)))
            .after(HomeEvent.GroupAgesReceived(meter1AtMs = null, meter2AtMs = 6_000))
        assertTrue(s.meter1Stale)
    }

    @Test fun freshMeter1IsNotStaleAndTheLimitIsExclusive() {
        val s = connected(now = 3_000)
            .after(HomeEvent.TelemetryReceived(Telemetry(receivedAtMs = 3_000, speedKmh = 0f)))
            .after(HomeEvent.GroupAgesReceived(meter1AtMs = 1_000, meter2AtMs = 3_000))
        assertFalse(s.meter1Stale) // 2000 ms não é > 2000
        assertTrue(s.copy(nowMs = 3_001).meter1Stale)
    }

    @Test fun nothingIsStaleBeforeConnectingOrWithoutTelemetry() {
        assertFalse(HomeState(nowMs = 9_000).meter1Stale)
        assertFalse(connected(now = 9_000).meter1Stale)
    }

    // Plano Fase 3, decisão 3: controles restritos desabilitados com telemetria velha; bloqueio depende do estado do Meter1.
    @Test fun lockCommandIsDisabledAsStaleWhenMeter1IsStaleEvenIfOverallTelemetryIsFresh() {
        val s = connected(now = 6_000)
            .after(HomeEvent.TelemetryReceived(Telemetry(receivedAtMs = 6_000, speedKmh = 0f, locked = false)))
            .after(HomeEvent.GroupAgesReceived(meter1AtMs = 1_000, meter2AtMs = 6_000))
        assertEquals(Availability.Disabled(DisabledReason.TelemetryStale), HomeReducer.availability(s, ScooterCommand.SetLocked(true)))
    }

    @Test fun staleMeter2IsReportedByItsOwnAge() {
        val base = connected(now = 10_000).copy(telemetry = Telemetry(receivedAtMs = 10_000))
        assertTrue(base.copy(meter1AtMs = 10_000, meter2AtMs = 1_000).meter2Stale)   // 9000 ms > limite
        assertTrue(base.copy(meter1AtMs = 10_000, meter2AtMs = null).meter2Stale)    // nunca visto
        assertFalse(base.copy(meter1AtMs = 1_000, meter2AtMs = 10_000).meter2Stale)  // Meter1 velho não afeta
        assertFalse(base.meter2Stale)                                               // sem idades por grupo (legado)
    }
}

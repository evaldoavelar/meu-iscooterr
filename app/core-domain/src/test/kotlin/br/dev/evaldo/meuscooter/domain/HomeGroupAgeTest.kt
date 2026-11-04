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
}

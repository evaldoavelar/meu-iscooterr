package br.dev.evaldo.meuscooter.domain

import br.dev.evaldo.meuscooter.protocol.AgedTelemetry
import br.dev.evaldo.meuscooter.protocol.FieldGroup
import br.dev.evaldo.meuscooter.protocol.Telemetry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Seam S1: forTrip → TripSession → TripTracker. Oráculos calculados à mão: d = v × segundos / 3600,
 * média = d × 3600 / segundos; frescor de 2 s (ADR 0004/HomeRules), pausa reancora (ADR 0013).
 */
class TripSpeedTest {
    private fun TripSession.offer(aged: AgedTelemetry, gps: GpsSpeedSample? = null): Telemetry {
        val t = HomeState(telemetry = aged.telemetry, meter1AtMs = aged.meter1AtMs, meter2AtMs = aged.meter2AtMs)
            .tripTelemetry()!!
            .forTrip(gps, aged.telemetry.receivedAtMs, meter1AtMs = aged.meter1AtMs)
        onTelemetry(t, meter1AtMs = aged.meter1AtMs)
        return t
    }

    private fun meter1(atMs: Long) = AgedTelemetry.empty().receive(
        Telemetry(receivedAtMs = atMs, wheelMoving = true, scooterSpeedKmh = 18f), FieldGroup.Meter1,
    )

    private fun assertSummary(session: TripSession, distanceKm: Float, durationMs: Long, avgKmh: Float, maxKmh: Float) {
        val summary = session.current!!
        assertEquals(distanceKm, summary.distanceKm, 0.000001f)
        assertEquals(durationMs, summary.durationMs)
        assertEquals(avgKmh, summary.avgKmh, 0.0001f)
        assertEquals(maxKmh, summary.maxKmh, 0.0001f)
    }

    @Test fun `Meter2 por 60 s sem novo Meter1 acumula so os 2 s validos e nao integra a lacuna`() {
        val session = TripSession()
        var aged = meter1(0)
        session.offer(aged)
        for (second in 1L..60L) {
            aged = aged.receive(Telemetry(receivedAtMs = second * 1_000, voltageV = 60f), FieldGroup.Meter2)
            assertEquals(0L, aged.meter1AtMs)
            assertEquals(true, aged.telemetry.wheelMoving)
            assertEquals(18f, aged.telemetry.scooterSpeedKmh)
            val selected = session.offer(aged)
            if (second <= 2) assertEquals(18f, selected.speedKmh) else assertNull(selected.speedKmh)
            // 18 km/h × min(t, 2 s) = 5 m/s × min(t, 2 s); depois de 2 s tudo fica congelado.
            if (second == 1L) assertSummary(session, 0.005f, 1_000L, 18f, 18f)
            else assertSummary(session, 0.010f, 2_000L, 18f, 18f)
        }
        // Novo Meter1 reancora sem cobrir a lacuna sem velocidade; no segundo seguinte somam só 5 m.
        session.offer(meter1(61_000))
        assertSummary(session, 0.010f, 61_000L, 36f / 61f, 18f)
        session.offer(meter1(62_000))
        assertSummary(session, 0.015f, 62_000L, 27f / 31f, 18f)
    }

    @Test fun `trocas patinete GPS patinete GPS integram cada intervalo uma vez sem salto`() {
        val session = TripSession()
        session.offer(meter1(0))
        session.offer(meter1(1_000), GpsSpeedSample(36f, 1_000))
        assertSummary(session, 0.005f, 1_000L, 18f, 36f)
        val beforeDuplicate = session.current
        session.offer(meter1(1_000), GpsSpeedSample(36f, 1_000))
        assertEquals(beforeDuplicate, session.current)
        session.offer(meter1(2_000), GpsSpeedSample(36f, 2_000))
        assertSummary(session, 0.015f, 2_000L, 27f, 36f)
        session.offer(meter1(3_000), GpsSpeedSample(36f, -1)) // GPS com 3001 ms: volta ao patinete.
        assertSummary(session, 0.025f, 3_000L, 30f, 36f)
        session.offer(meter1(4_000))
        assertSummary(session, 0.030f, 4_000L, 27f, 36f)
        session.offer(meter1(5_000), GpsSpeedSample(9f, 5_000))
        assertSummary(session, 0.035f, 5_000L, 25.2f, 36f)
        session.offer(meter1(6_000), GpsSpeedSample(9f, 6_000))
        // (18 + 36 + 36 + 18 + 18 + 9) / 3600 = 0,0375 km em 6 s, média 22,5.
        assertSummary(session, 0.0375f, 6_000L, 22.5f, 36f)
    }

    @Test fun `pausa entre fontes congela e primeira amostra retomada so reancora`() {
        val session = TripSession()
        session.offer(meter1(0))
        session.offer(meter1(1_000), GpsSpeedSample(36f, 1_000))
        session.offer(meter1(2_000), GpsSpeedSample(36f, 2_000))
        assertSummary(session, 0.015f, 2_000L, 27f, 36f)
        session.pause()
        session.offer(meter1(3_000), GpsSpeedSample(90f, 3_000))
        session.offer(meter1(10_000))
        assertSummary(session, 0.015f, 2_000L, 27f, 36f)
        session.play()
        session.offer(meter1(12_000))
        assertSummary(session, 0.015f, 2_000L, 27f, 36f)
        session.offer(meter1(13_000), GpsSpeedSample(9f, 13_000))
        assertSummary(session, 0.020f, 3_000L, 24f, 36f)
        session.offer(meter1(14_000))
        // 15 m antes da pausa + 5 m + 2,5 m depois; 2 s + 2 s ativos, média 20,25.
        assertSummary(session, 0.0225f, 4_000L, 20.25f, 36f)
        val saved = session.stop()!!
        assertEquals(0.0225f, saved.distanceKm, 0.000001f)
        assertEquals(4_000L, saved.durationMs)
        assertEquals(20.25f, saved.avgKmh, 0.0001f)
        assertEquals(36f, saved.maxKmh, 0.0001f)
    }
}

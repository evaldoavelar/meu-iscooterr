package br.dev.evaldo.meuscooter.domain

import br.dev.evaldo.meuscooter.protocol.Telemetry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * S1: acumulação da viagem (velocidade da amostra anterior mantida até a próxima).
 * Esperado à mão: (t=0,0 km/h) (t=60 s,30) (t=120 s,30) → 0×1 min + 30×1 min = 0,5 km em 2 min → média 15 km/h, máx. 30 km/h.
 */
class TripTrackerTest {
    private fun s(tMs: Long, kmh: Float?) = Telemetry(receivedAtMs = tMs, speedKmh = kmh)

    private fun trackerWithSample(): TripTracker = TripTracker().apply {
        onTelemetry(s(0, 0f)); onTelemetry(s(60_000, 30f)); onTelemetry(s(120_000, 30f))
    }

    @Test fun `sem amostras nao ha viagem`() {
        assertNull(TripTracker().current)
    }

    @Test fun `acumula distancia duracao media e maxima`() {
        val trip = trackerWithSample().current!!
        assertEquals(0.5f, trip.distanceKm, 0.001f)
        assertEquals(120_000L, trip.durationMs)
        assertEquals(15f, trip.avgKmh, 0.001f)
        assertEquals(30f, trip.maxKmh, 0.001f)
    }

    @Test fun `amostra sem velocidade e ignorada`() {
        val tracker = TripTracker().apply { onTelemetry(s(0, 10f)); onTelemetry(s(30_000, null)) }
        assertEquals(0L, tracker.current!!.durationMs)
    }

    @Test fun `zerar devolve a viagem e recomeca vazio`() {
        val tracker = trackerWithSample()
        val saved = tracker.reset()!!
        assertEquals(0.5f, saved.distanceKm, 0.001f)
        assertNull(tracker.current)
        assertNull(tracker.reset())
    }
}

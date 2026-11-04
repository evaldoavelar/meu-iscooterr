package br.dev.evaldo.meuscooter.domain

import br.dev.evaldo.meuscooter.protocol.Telemetry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * S1: acumulação da viagem (velocidade da amostra anterior mantida até a próxima).
 * Esperado à mão: (t=0,0 km/h) (t=1 s,30) (t=2 s,30) → 0×1 s + 30×1 s = 30/3600 km em 2 s → média 15 km/h, máx. 30 km/h. Intervalos > STALE_AFTER_MS (2 s) são lacuna e não somam.
 */
class TripTrackerTest {
    private fun s(tMs: Long, kmh: Float?) = Telemetry(receivedAtMs = tMs, speedKmh = kmh)

    private fun trackerWithSample(): TripTracker = TripTracker().apply {
        onTelemetry(s(0, 0f)); onTelemetry(s(1_000, 30f)); onTelemetry(s(2_000, 30f))
    }

    @Test fun `sem amostras nao ha viagem`() {
        assertNull(TripTracker().current)
    }

    @Test fun `acumula distancia duracao media e maxima`() {
        val trip = trackerWithSample().current!!
        assertEquals(30f / 3600f, trip.distanceKm, 0.00001f)
        assertEquals(2_000L, trip.durationMs)
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
        assertEquals(30f / 3600f, saved.distanceKm, 0.00001f)
        assertNull(tracker.current)
        assertNull(tracker.reset())
    }

    /** Lacuna > limite (reconexão): a velocidade antiga não vale pelo tempo sem dados. 25 km/h, 10 min sem amostra → 0 km somados. */
    @Test fun `lacuna sem dados nao soma distancia`() {
        val tracker = TripTracker().apply { onTelemetry(s(0, 25f)); onTelemetry(s(600_000, 25f)) }
        assertEquals(0f, tracker.current!!.distanceKm, 0.0001f)
    }

    @Test fun `amostra com tempo regressivo e ignorada`() {
        val tracker = TripTracker().apply { onTelemetry(s(10_000, 30f)); onTelemetry(s(5_000, 30f)); onTelemetry(s(11_000, 30f)) }
        assertEquals(30f / 3600f, tracker.current!!.distanceKm, 0.0001f)
    }

    /** Revisto após Codex r1 #3: a leitura nula invalida a velocidade anterior (25 km/h de 31 s atrás não vale), então o 1 s seguinte soma 0 km. */
    @Test fun `amostra sem velocidade reancora o tempo`() {
        val tracker = TripTracker().apply { onTelemetry(s(0, 25f)); onTelemetry(s(30_000, null)); onTelemetry(s(31_000, 25f)) }
        assertEquals(0f, tracker.current!!.distanceKm, 0.0001f)
    }

    @Test fun `apos zerar a viagem recomeca com a proxima amostra`() {
        val tracker = trackerWithSample().also { it.reset() }
        tracker.onTelemetry(s(500_000, 10f))
        assertEquals(0L, tracker.current!!.durationMs)
        assertEquals(0f, tracker.current!!.distanceKm, 0.0001f)
    }

    /** 600 W a 30 km/h durante 2 s: energia 600×2/3600 Wh ÷ distância 30×2/3600 km = 20 Wh/km (= 600÷30). */
    @Test fun `consumo medio e bateria inicial e final`() {
        fun full(t: Long, battery: Int) = Telemetry(receivedAtMs = t, speedKmh = 30f, batteryPercent = battery, voltageV = 60f, currentA = 10f)
        val tracker = TripTracker().apply { onTelemetry(full(0, 82)); onTelemetry(full(1_000, 81)); onTelemetry(full(2_000, 76)) }
        val trip = tracker.current!!
        assertEquals(20f, trip.avgWhPerKm!!, 0.001f)
        assertEquals(82, trip.batteryStartPercent)
        assertEquals(76, trip.batteryEndPercent)
    }

    @Test fun `sem leitura de potencia o consumo medio e nulo`() {
        assertNull(trackerWithSample().current!!.avgWhPerKm)
    }

    /** Codex r1 #3. 36 km/h em t=0, leitura nula em 10 s, 0 km/h em 11 s: a velocidade de 11 s atrás não vale → 0 km (e não 0,01 km). */
    @Test fun `leitura nula invalida a velocidade anterior`() {
        val tracker = TripTracker().apply { onTelemetry(s(0, 36f)); onTelemetry(s(10_000, null)); onTelemetry(s(11_000, 0f)) }
        assertEquals(0f, tracker.current!!.distanceKm, 0.00001f)
    }

    /** Mesma regra para a potência: sem velocidade em 1 s, a potência de 600 W da amostra anterior não soma energia. */
    @Test fun `leitura nula invalida a potencia anterior`() {
        fun full(t: Long, kmh: Float?) = Telemetry(receivedAtMs = t, speedKmh = kmh, voltageV = 60f, currentA = 10f)
        val tracker = TripTracker().apply { onTelemetry(full(0, 30f)); onTelemetry(full(1_000, null)); onTelemetry(full(2_000, 30f)) }
        assertNull(tracker.current!!.avgWhPerKm)
    }
}

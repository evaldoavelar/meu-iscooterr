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

    @Test fun `reset do tracker devolve a viagem e recomeca vazio`() {
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

    @Test fun `apos reset a viagem recomeca com a proxima amostra`() {
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

    /** Potência isolada da distância: 600 W a 30 km/h em 0 e 1 s (20 Wh/km); leitura nula em 2 s; 30 km/h em 3 s. O intervalo 2→3 s não pode somar energia (600 W velha) → continua 20 Wh/km (com o defeito daria 40). */
    @Test fun `leitura nula invalida a potencia anterior`() {
        fun full(t: Long, kmh: Float?) = Telemetry(receivedAtMs = t, speedKmh = kmh, voltageV = 60f, currentA = 10f)
        val tracker = TripTracker().apply { onTelemetry(full(0, 30f)); onTelemetry(full(1_000, 30f)); onTelemetry(full(2_000, null)); onTelemetry(full(3_000, 30f)) }
        assertEquals(20f, tracker.current!!.avgWhPerKm!!, 0.001f)
    }
}

/** Distância integrada a partir da velocidade do GPS (entrada já resolvida por [withGpsSpeed]). */
class TripTrackerGpsTest {
    @Test fun `integra distancia e maxima com a velocidade do GPS`() {
        val tr = TripTracker()
        val gps = GpsSpeedSample(36f, 0)
        fun feed(ms: Long) = tr.onTelemetry(Telemetry(receivedAtMs = ms, wheelMoving = true).withGpsSpeed(gps.copy(receivedAtMs = ms), ms, 3_000))
        feed(0); feed(1_000); feed(2_000)
        val c = tr.current!!
        assertEquals(0.02f, c.distanceKm, 1e-4f)
        assertEquals(36f, c.maxKmh, 0f)
    }
    @Test fun `sem GPS com a roda em movimento nao integra distancia`() {
        val tr = TripTracker()
        for (ms in listOf(0L, 1_000L, 2_000L)) tr.onTelemetry(Telemetry(receivedAtMs = ms, wheelMoving = true).withGpsSpeed(null, ms, 3_000))
        assertEquals(0f, tr.current?.distanceKm ?: 0f, 0f)
    }

    /**
     * Meter2 velho não soma energia: potência 600 W (Meter2 visto em t=0) com Meter1 chegando em 0, 1 s, 5 s, 6 s a 30 km/h.
     * Em 5 s e 6 s o Meter2 tem 5000/6000 ms (> 2000): sem potência. Meter2 fresco até 1,9 s; depois, lacuna 1,9→5 s (> 2 s) e 5→6 s sem potência.
     */
    @Test fun `Meter2 velho nao soma energia no tracker`() {
        val tracker = TripTracker()
        fun state(tMs: Long) = HomeState(
            telemetry = Telemetry(receivedAtMs = tMs, speedKmh = 30f, powerW = 600f, voltageV = 60f, currentA = 10f),
            meter1AtMs = tMs, meter2AtMs = 0L,
        )
        for (t in listOf(0L, 1_000L, 1_900L, 5_000L, 6_000L)) tracker.onTelemetry(state(t).tripTelemetry()!!)
        val trip = tracker.current!!
        // Energia só até 1,9 s: 600 W x 1,9 s = 1140 W.s. Distância: 30 km/h x 2,9 s (1,9 s + o intervalo 5→6 s, 1 s) = 87 km.h.s.
        // Wh/km = (1140/3600) / (87/3600) = 13,103. Com o defeito (potência velha integrada em 5→6 s) daria (1740/3600)/(87/3600) = 20.
        assertEquals(30f * 2.9f / 3600f, trip.distanceKm, 0.0001f)
        assertEquals(1140f / 87f, trip.avgWhPerKm!!, 0.001f)
    }

    @Test fun `Meter1 velho anula modo e tracao no tripTelemetry`() {
        val sFresh = HomeState(
            telemetry = Telemetry(receivedAtMs = 1_000L, speedModeRaw = 1, dualMotor = true, powerW = 200f),
            meter1AtMs = 1_000L, meter2AtMs = 1_000L,
        )
        val tFresh = sFresh.tripTelemetry()!!
        assertEquals(1, tFresh.speedModeRaw)
        assertEquals(true, tFresh.dualMotor)

        val sStale = HomeState(
            telemetry = Telemetry(receivedAtMs = 5_000L, speedModeRaw = 1, dualMotor = true, powerW = 200f),
            meter1AtMs = 1_000L, meter2AtMs = 5_000L, // Meter1 com 4s de atraso (> 2s)
        )
        val tStale = sStale.tripTelemetry()!!
        org.junit.Assert.assertNull(tStale.speedModeRaw)
        org.junit.Assert.assertNull(tStale.dualMotor)
        assertEquals(200f, tStale.powerW)
    }
}

package br.dev.evaldo.meuscooter.domain

import br.dev.evaldo.meuscooter.protocol.Telemetry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Velocidade do GPS: só exibição/viagem. Decisões de segurança seguem em `wheelMoving` (Meter1), nunca aqui. */
class GpsSpeedTest {
    private val max = 3_000L
    private fun t(wheel: Boolean?, kmh: Float? = null) = Telemetry(receivedAtMs = 10_000, speedKmh = kmh, wheelMoving = wheel)
    private fun s(kmh: Float, at: Long = 10_000) = GpsSpeedSample(kmh, at)

    @Test fun `roda em movimento com GPS fresco usa a velocidade do GPS`() {
        assertEquals(21.5f, t(true).withGpsSpeed(s(21.5f), 10_500, max).speedKmh)
    }
    @Test fun `limite de idade e inclusivo`() {
        assertEquals(5f, t(true).withGpsSpeed(s(5f, 7_000), 10_000, max).speedKmh)
        assertNull(t(true).withGpsSpeed(s(5f, 6_999), 10_000, max).speedKmh)
    }
    @Test fun `GPS velho com roda em movimento deixa a velocidade desconhecida`() {
        assertNull(t(true).withGpsSpeed(s(20f, 1_000), 10_000, max).speedKmh)
    }
    @Test fun `sem amostra de GPS com roda em movimento deixa a velocidade desconhecida`() {
        assertNull(t(true, 33f).withGpsSpeed(null, 10_000, max).speedKmh)
    }
    @Test fun `roda parada manda mesmo com GPS ruidoso`() {
        assertEquals(0f, t(false).withGpsSpeed(s(4.2f), 10_000, max).speedKmh)
        assertEquals(0f, t(false).withGpsSpeed(null, 10_000, max).speedKmh)
    }
    @Test fun `NaN infinito e negativo sao descartados`() {
        for (bad in listOf(Float.NaN, Float.POSITIVE_INFINITY, -1f)) assertNull(t(true).withGpsSpeed(s(bad), 10_000, max).speedKmh)
    }
    @Test fun `wheelMoving nulo mantem a velocidade original`() {
        assertEquals(18f, t(null, 18f).withGpsSpeed(s(40f), 10_000, max).speedKmh)
        assertNull(t(null, null).withGpsSpeed(s(40f), 10_000, max).speedKmh)
    }
    @Test fun `amostra mais nova que a telemetria conta como fresca`() {
        assertEquals(9f, t(true).withGpsSpeed(s(9f, 10_400), 10_000, max).speedKmh)
    }
    @Test fun `outros campos ficam intactos`() {
        val r = Telemetry(receivedAtMs = 1, batteryPercent = 70, wheelMoving = true).withGpsSpeed(s(8f, 1), 1, max)
        assertEquals(70, r.batteryPercent); assertEquals(true, r.wheelMoving); assertEquals(1L, r.receivedAtMs)
    }

    private fun tp(wheel: Boolean?, own: Float?) = Telemetry(receivedAtMs = 10_000, wheelMoving = wheel, scooterSpeedKmh = own)

    @Test fun `viagem - GPS fresco vence a velocidade do patinete`() {
        assertEquals(21f, tp(true, 18f).forTrip(s(21f), 10_000, max).speedKmh)
    }
    @Test fun `viagem - GPS ausente ou velho usa a velocidade do patinete`() {
        assertEquals(18f, tp(true, 18f).forTrip(null, 10_000, max).speedKmh)
        assertEquals(18f, tp(true, 18f).forTrip(s(20f, 1_000), 10_000, max).speedKmh)
    }
    @Test fun `viagem - patinete sem velocidade (mph ou duvida) continua sem amostra`() {
        assertNull(tp(true, null).forTrip(null, 10_000, max).speedKmh)
    }
    @Test fun `viagem - roda parada vale zero mesmo com velocidade do patinete`() {
        assertEquals(0f, tp(false, 18f).forTrip(null, 10_000, max).speedKmh)
    }
    @Test fun `viagem - wheelMoving nulo mantem speedKmh original`() {
        assertEquals(12f, Telemetry(receivedAtMs = 1, speedKmh = 12f, scooterSpeedKmh = 18f).forTrip(null, 1, max).speedKmh)
    }
    @Test fun `viagem - withGpsSpeed (exibicao) nao usa a velocidade do patinete`() {
        assertNull(tp(true, 18f).withGpsSpeed(null, 10_000, max).speedKmh)
    }
}

class GpsSampleOfTest {
    @Test fun `converte m por s em km por h`() { assertEquals(GpsSpeedSample(36f, 7), gpsSampleOf(true, 10f, 7)) }
    @Test fun `sem velocidade reportada ou invalida vira nulo`() {
        assertNull(gpsSampleOf(false, 10f, 0)); assertNull(gpsSampleOf(true, Float.NaN, 0)); assertNull(gpsSampleOf(true, -0.5f, 0))
    }

}

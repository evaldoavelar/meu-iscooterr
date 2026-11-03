package br.dev.evaldo.meuscooter.domain

import br.dev.evaldo.meuscooter.protocol.Telemetry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** S1: potência = V×I; Wh/km = W ÷ km/h. Valores calculados à mão: 54 V × 10 A = 540 W; 540 W ÷ 27 km/h = 20 Wh/km. */
class TelemetryMetricsTest {
    private fun t(v: Float? = null, i: Float? = null, speed: Float? = null) =
        Telemetry(receivedAtMs = 0, voltageV = v, currentA = i, speedKmh = speed)

    @Test fun `potencia e tensao vezes corrente`() {
        assertEquals(540f, TelemetryMetrics.powerW(t(v = 54f, i = 10f))!!, 0.001f)
    }

    @Test fun `potencia sem leitura de tensao ou corrente e nula`() {
        assertNull(TelemetryMetrics.powerW(t(v = 54f)))
        assertNull(TelemetryMetrics.powerW(t(i = 10f)))
    }

    @Test fun `consumo em Wh por km`() {
        assertEquals(20f, TelemetryMetrics.whPerKm(t(v = 54f, i = 10f, speed = 27f))!!, 0.001f)
    }

    @Test fun `consumo abaixo de 1 km por hora ou sem velocidade e nulo`() {
        assertNull(TelemetryMetrics.whPerKm(t(v = 54f, i = 10f, speed = 0.9f)))
        assertNull(TelemetryMetrics.whPerKm(t(v = 54f, i = 10f)))
    }
}

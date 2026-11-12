package br.dev.evaldo.meuscooter.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TripAltimetryTest {
    private fun route(vararg heights: Double?) = heights.mapIndexed { i, h -> GeoPoint(i * 0.001, 0.0, h) }

    @Test fun `sem altitude nao produz estatisticas nem perfil`() {
        assertNull(analyzeTripAltimetry(emptyList()))
        assertNull(analyzeTripAltimetry(route(null, null)))
        assertNull(analyzeTripAltimetry(route(Double.NaN, Double.POSITIVE_INFINITY)))
    }

    @Test fun `histerese de 3 m acumula variacoes lentas e ignora ruido menor`() {
        // Referência manual: 100→103 = +3; 103→106 = +3; 106→103 = −3. Sobras não contam.
        val a = analyzeTripAltimetry(route(100.0, 101.0, 102.0, 103.0, 102.0, 104.0, 106.0, 105.0, 103.0, 104.0))!!
        assertEquals(6.0, a.gainM!!, 0.0); assertEquals(3.0, a.lossM!!, 0.0)
        assertEquals(100.0, a.minM, 0.0); assertEquals(106.0, a.maxM, 0.0)
        // 0,001 grau de latitude = 111,195 m (R = 6371 km); nove intervalos.
        assertEquals(1_000.755, a.distanceM, 0.02)
        assertEquals(10, a.segments.single().size)
        assertEquals(0.0, a.segments.single().first().distanceM, 0.0)
        assertEquals(111.195, a.segments.single()[1].distanceM, 0.01)
    }

    @Test fun `fixture S48 preserva perfil mas filtra a oscilacao final de 2 m`() {
        // design/s48.md: soma bruta +18/−12; o limiar documentado ignora 747→749→748.
        val a = analyzeTripAltimetry(route(742.0, 746.0, 751.0, 747.0, 754.0, 750.0, 747.0, 749.0, 748.0))!!
        assertEquals(16.0, a.gainM!!, 0.0); assertEquals(11.0, a.lossM!!, 0.0)
        assertEquals(742.0, a.minM, 0.0); assertEquals(754.0, a.maxM, 0.0)
        assertEquals(listOf(742.0, 746.0, 751.0, 747.0, 754.0, 750.0, 747.0, 749.0, 748.0), a.segments.single().map { it.altitudeM })
    }

    @Test fun `lacuna interrompe linha e nao conta a diferenca de altitude ausente`() {
        val a = analyzeTripAltimetry(route(10.0, 14.0, null, 100.0, 96.0))!!
        assertEquals(4.0, a.gainM!!, 0.0); assertEquals(4.0, a.lossM!!, 0.0)
        assertEquals(listOf(2, 2), a.segments.map { it.size })
        assertEquals(333.585, a.segments[1][0].distanceM, 0.02)
        assertEquals(444.780, a.distanceM, 0.02)
        assertEquals(10.0, a.minM, 0.0); assertEquals(100.0, a.maxM, 0.0)
    }

    @Test fun `leitura isolada mostra limites reais sem inventar ganho ou perda`() {
        val a = analyzeTripAltimetry(route(null, -12.0, Double.NaN, -10.0, null))!!
        assertNull(a.gainM); assertNull(a.lossM)
        assertEquals(-12.0, a.minM, 0.0); assertEquals(-10.0, a.maxM, 0.0)
        assertEquals(listOf(1, 1), a.segments.map { it.size })
        org.junit.Assert.assertFalse(a.toString().contains("12"))
        org.junit.Assert.assertFalse(a.segments[0][0].toString().contains("12"))
    }

    @Test fun `terreno plano e ruido pequeno tem zeros reais e perfil plano`() {
        val a = analyzeTripAltimetry(route(0.0, 2.0, -2.0, 0.0))!!
        assertEquals(0.0, a.gainM!!, 0.0); assertEquals(0.0, a.lossM!!, 0.0)
        val flat = analyzeTripAltimetry(route(742.0, 742.0))!!
        assertEquals(742.0, flat.minM, 0.0); assertEquals(742.0, flat.maxM, 0.0)
        assertEquals(0.0, flat.gainM!!, 0.0); assertEquals(0.0, flat.lossM!!, 0.0)
    }
}

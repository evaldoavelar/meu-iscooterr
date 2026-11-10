package br.dev.evaldo.meuscooter.domain

import br.dev.evaldo.meuscooter.protocol.Telemetry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PowerHistoryTest {

    @Test
    fun `inicialmente sem dados retorna lista vazia`() {
        val history = PowerHistory()
        assertEquals(emptyList<Float>(), history.bars())
    }

    @Test
    fun `amostras dentro da janela de 30s sao divididas em 24 barras normalizadas pelo maximo`() {
        val history = PowerHistory()
        // Amostra a t=0s com 100W, a t=15s com 200W, e a t=30s com 400W
        history.offer(Telemetry(receivedAtMs = 0L, voltageV = 50f, currentA = 2f)) // 100W
        history.offer(Telemetry(receivedAtMs = 15_000L, voltageV = 50f, currentA = 4f)) // 200W
        history.offer(Telemetry(receivedAtMs = 30_000L, voltageV = 50f, currentA = 8f)) // 400W

        val bars = history.bars(n = 24)
        assertEquals(24, bars.size)
        // Fatia 0 (t=0..1250ms) -> 100W / 400W = 0.25f
        assertEquals(0.25f, bars[0], 0.01f)
        // Fatia do meio (t=15000ms -> slice 12: 15000/1250 = 12) -> 200W / 400W = 0.5f
        assertEquals(0.5f, bars[12], 0.01f)
        // Fatia final (t=30000ms -> slice 23) -> 400W / 400W = 1.0f
        assertEquals(1.0f, bars[23], 0.01f)
        // Fatias sem amostra devem ser 0f
        assertEquals(0f, bars[1], 0.001f)
        assertEquals(0f, bars[11], 0.001f)
        assertEquals(0f, bars[13], 0.001f)
    }

    @Test
    fun `amostras fora da janela de 30s caem fora`() {
        val history = PowerHistory()
        history.offer(Telemetry(receivedAtMs = 1_000L, voltageV = 50f, currentA = 2f)) // 100W
        // Amostra 35s depois (t=36_000ms): a amostra em 1_000ms (idade = 35s > 30s) cai fora
        history.offer(Telemetry(receivedAtMs = 36_000L, voltageV = 50f, currentA = 4f)) // 200W

        val bars = history.bars(n = 24)
        assertEquals(24, bars.size)
        // A amostra de 1_000ms caiu fora; o novo máximo é 200W na fatia final
        assertEquals(1.0f, bars[23], 0.01f)
        // A primeira fatia não tem a amostra antiga
        assertEquals(0f, bars[0], 0.001f)
    }

    @Test
    fun `tempo regressivo e ignorado`() {
        val history = PowerHistory()
        history.offer(Telemetry(receivedAtMs = 20_000L, voltageV = 50f, currentA = 4f)) // 200W
        // Chega pacote atrasado ou com relógio invertido
        history.offer(Telemetry(receivedAtMs = 10_000L, voltageV = 50f, currentA = 10f)) // 500W ignorado

        val bars = history.bars(n = 24)
        assertEquals(24, bars.size)
        // O máximo continua 200W, a amostra de 500W foi descartada
        assertEquals(1.0f, bars[23], 0.01f)
        assertEquals(0f, bars[8], 0.001f)
    }

    @Test
    fun `telemetria obsoleta ou vencida nao entra`() {
        val history = PowerHistory()
        // Amostra com stale=true
        history.offer(Telemetry(receivedAtMs = 10_000L, voltageV = 50f, currentA = 5f), stale = true)
        assertTrue(history.bars().isEmpty())

        // Amostra normal
        history.offer(Telemetry(receivedAtMs = 15_000L, voltageV = 50f, currentA = 2f), stale = false)
        assertEquals(24, history.bars(24).size)

        // Verificando janela quando o tempo atual expira todas as amostras
        val expiredBars = history.bars(n = 24, nowMs = 50_000L) // 35s depois da última amostra
        assertTrue(expiredBars.isEmpty())
    }

    @Test
    fun `NaN e infinito sao ignorados`() {
        val history = PowerHistory()
        history.offer(powerW = Float.NaN, receivedAtMs = 1_000L)
        history.offer(powerW = Float.POSITIVE_INFINITY, receivedAtMs = 2_000L)
        history.offer(powerW = Float.NEGATIVE_INFINITY, receivedAtMs = 3_000L)
        assertTrue(history.bars().isEmpty())
    }

    @Test
    fun `quando potencia for zero em todas as amostras barras retornam zero`() {
        val history = PowerHistory()
        history.offer(Telemetry(receivedAtMs = 1_000L, speedKmh = 0f, voltageV = 50f, currentA = 0f))
        val bars = history.bars(24)
        assertEquals(24, bars.size)
        assertTrue(bars.all { it == 0f })
    }
}

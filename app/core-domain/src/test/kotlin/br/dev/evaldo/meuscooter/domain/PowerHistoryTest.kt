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
    fun `amostras dentro da janela de 30s sao divididas em 24 barras em escala absoluta fixa 0-750 W`() {
        val history = PowerHistory()
        // Amostra a t=1,25s com 100W, a t=15s com 200W, e a t=30s com 400W (fatias absolutas 1, 12 e 24)
        history.offer(Telemetry(receivedAtMs = 1_250L, voltageV = 50f, currentA = 2f)) // 100W
        history.offer(Telemetry(receivedAtMs = 15_000L, voltageV = 50f, currentA = 4f)) // 200W
        history.offer(Telemetry(receivedAtMs = 30_000L, voltageV = 50f, currentA = 8f)) // 400W

        val bars = history.bars(n = 24)
        assertEquals(24, bars.size)
        // Janela = fatias absolutas 1..24; t=1250 ms (fatia 1) -> índice 0 -> 100 W / 750 W = 0,1333
        assertEquals(100f / 750f, bars[0], 0.0005f)
        // t=15000 ms (fatia 12) -> índice 11 -> 200 W / 750 W = 0,2667
        assertEquals(200f / 750f, bars[11], 0.0005f)
        // Fatia final (t=30000 ms -> fatia 24 -> índice 23) -> 400 W / 750 W = 0,5333 (não 1,0: sem normalizar pelo máximo)
        assertEquals(400f / 750f, bars[23], 0.0005f)
        // Fatias sem amostra devem ser 0f
        assertEquals(0f, bars[1], 0.001f)
        assertEquals(0f, bars[10], 0.001f)
        assertEquals(0f, bars[12], 0.001f)
    }

    @Test
    fun `amostras fora da janela de 30s caem fora`() {
        val history = PowerHistory()
        history.offer(Telemetry(receivedAtMs = 1_000L, voltageV = 50f, currentA = 2f)) // 100W
        // Amostra 35s depois (t=36_000ms): a amostra em 1_000ms (idade = 35s > 30s) cai fora
        history.offer(Telemetry(receivedAtMs = 36_000L, voltageV = 50f, currentA = 4f)) // 200W

        val bars = history.bars(n = 24)
        assertEquals(24, bars.size)
        // A amostra de 1_000ms caiu fora; a fatia final é 200 W / 750 W
        assertEquals(200f / 750f, bars[23], 0.0005f)
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
        // A amostra de 500 W foi descartada; fica 200 W / 750 W
        assertEquals(200f / 750f, bars[23], 0.0005f)
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

    /** Achado 3: rajada com o mesmo receivedAtMs (janela não poda) é limitada; as amostras mais antigas caem. */
    @Test
    fun `rajada com o mesmo carimbo respeita o teto de amostras e descarta as mais antigas`() {
        val history = PowerHistory()
        repeat(400) { history.offer(100f, 0L) }
        repeat(600) { history.offer(200f, 15_000L) }
        assertTrue(history.sampleCount <= PowerHistory.MAX_SAMPLES)
        val bars = history.bars(n = 24, nowMs = 30_000L)
        assertEquals(0f, bars[0], 0.001f) // as 400 do t=0 foram as mais antigas e caíram
        assertEquals(200f / 750f, bars[11], 0.0005f)
    }

    @Test
    fun `rajada enorme no mesmo instante nunca passa do teto`() {
        val history = PowerHistory()
        repeat(PowerHistory.MAX_SAMPLES * 5) { history.offer(50f, 1_000L) }
        assertEquals(PowerHistory.MAX_SAMPLES, history.sampleCount)
    }

    /** Achado 3: o índice das barras desliza com o relógio (nowMs) sem novas amostras. */
    @Test
    fun `bars desliza o indice das barras conforme nowMs avanca`() {
        val history = PowerHistory()
        history.offer(100f, 0L)
        history.offer(200f, 10_000L)
        val a = history.bars(n = 24, nowMs = 10_000L)
        assertEquals(100f / 750f, a[15], 0.0005f)
        assertEquals(200f / 750f, a[23], 0.0005f)
        val b = history.bars(n = 24, nowMs = 20_000L)
        assertEquals(100f / 750f, b[7], 0.0005f)
        assertEquals(200f / 750f, b[15], 0.0005f)
        assertEquals(0f, b[23], 0.001f)
        val c = history.bars(n = 24, nowMs = 35_000L) // a amostra de t=0 saiu da janela
        assertEquals(200f / 750f, c[3], 0.0005f)
        assertEquals(0f, c[7], 0.001f)
    }

    @Test
    fun `60 W constantes dao barras baixas e estaveis de 0,08`() {
        val history = PowerHistory()
        for (t in 0..30_000L step 500L) history.offer(60f, t)
        val bars = history.bars(24)
        assertEquals(24, bars.size)
        assertTrue(bars.all { kotlin.math.abs(it - 0.08f) < 0.0005f })
    }

    @Test
    fun `750 W enche a barra e acima de 750 W faz clamp em 1`() {
        val history = PowerHistory()
        history.offer(750f, 1_250L)
        history.offer(1500f, 15_000L)
        history.offer(375f, 30_000L)
        val bars = history.bars(24)
        assertEquals(1f, bars[0], 0f)
        assertEquals(1f, bars[11], 0f)   // 1500/750 = 2 -> clamp
        assertEquals(0.5f, bars[23], 0.0001f)
    }

    @Test
    fun `potencia negativa nao gera barra negativa`() {
        val history = PowerHistory()
        history.offer(-100f, 1_000L)
        assertTrue(history.bars(24).all { it == 0f })
    }

    /** S45-E: barras fechadas são imutáveis; avançar o relógio em passos < sliceMs só desloca posições. */
    @Test
    fun `barras fechadas mantem o valor exato ao avancar o relogio em passos menores que a fatia`() {
        val history = PowerHistory()
        // fatia absoluta 10 (12500..13749): 100 W e 300 W -> média 200 W; fatia 11 (13750..14999): 450 W
        history.offer(100f, 12_500L)
        history.offer(300f, 13_000L)
        history.offer(450f, 13_750L)
        val expected10 = 200f / 750f
        val expected11 = 450f / 750f

        var now = 15_000L // fatia 12 (parcial, vazia): fatias 10 e 11 fechadas
        var shift = 0
        repeat(6) {
            val bars = history.bars(n = 24, nowMs = now)
            // última barra = fatia de nowMs (índice 23); fatia 11 -> 23 - (fatiaAtual - 11)
            val cur = now / 1_250L
            assertEquals(expected10, bars[(23 - (cur - 10)).toInt()], 0.0001f)
            assertEquals(expected11, bars[(23 - (cur - 11)).toInt()], 0.0001f)
            now += 400L // < sliceMs
            shift++
        }
    }

    @Test
    fun `so a ultima fatia parcial muda quando chegam novas amostras`() {
        val history = PowerHistory()
        history.offer(150f, 1_250L) // fatia 1, fechada quando now está na fatia 2
        history.offer(75f, 2_500L)  // fatia 2 (parcial)
        val before = history.bars(n = 24, nowMs = 2_600L)
        history.offer(375f, 2_900L) // mesma fatia 2: média (75+375)/2 = 225 W
        val after = history.bars(n = 24, nowMs = 2_900L)
        assertEquals(150f / 750f, before[22], 0.0001f)
        assertEquals(150f / 750f, after[22], 0.0001f)
        assertEquals(75f / 750f, before[23], 0.0001f)
        assertEquals(225f / 750f, after[23], 0.0001f)
    }

    @Test
    fun `amostra futura em relacao a nowMs nao entra nas barras`() {
        val history = PowerHistory()
        history.offer(150f, 1_250L)
        history.offer(375f, 2_900L)
        val bars = history.bars(n = 24, nowMs = 2_600L)
        assertTrue(bars.none { it == 0.5f })
        assertEquals(150f / 750f, bars[22], 0.0001f)
        assertEquals(0f, bars[23], 0f)
    }

    /** n=29: sliceMs = 30000/29; 29.999 ms cai na fatia 28 e 30.000 ms na fatia 29 (exato, sem erro de Double). */
    @Test
    fun `n 29 separa 29999 e 30000 ms em fatias diferentes`() {
        val history = PowerHistory()
        history.offer(75f, 29_999L)
        history.offer(375f, 30_000L)
        val bars = history.bars(n = 29, nowMs = 30_000L)
        assertEquals(29, bars.size)
        assertEquals(75f / 750f, bars[27], 0.0001f)
        assertEquals(375f / 750f, bars[28], 0.0001f)
    }
}

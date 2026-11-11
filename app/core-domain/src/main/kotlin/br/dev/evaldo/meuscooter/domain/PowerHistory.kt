package br.dev.evaldo.meuscooter.domain

import br.dev.evaldo.meuscooter.protocol.Telemetry

/**
 * Janela deslizante de 30 s de consumo em tempo real para o gráfico de barras da telemetria.
 * Classe pura (sem dependência de relógio do sistema).
 * Amostras de [TelemetryMetrics.powerW] por [receivedAtMs] monotônico.
 */
class PowerHistory(
    private val windowMs: Long = WINDOW_MS,
) {
    private val samples = ArrayDeque<Sample>()

    /** Quantidade de amostras retidas (teste/diagnóstico). */
    val sampleCount: Int get() = samples.size
    private var lastReceivedAtMs: Long = Long.MIN_VALUE

    private data class Sample(val timeMs: Long, val powerW: Float)

    /**
     * Alimenta o histórico com uma leitura de [Telemetry].
     * Leituras obsoletas (stale), com tempo regressivo, sem potência ou inválidas (NaN/inf) não entram.
     */
    fun offer(telemetry: Telemetry, stale: Boolean = false) {
        val power = TelemetryMetrics.powerW(telemetry)
        offer(power, telemetry.receivedAtMs, stale)
    }

    /**
     * Alimenta o histórico com uma amostra bruta de potência em watts e timestamp monotônico.
     */
    fun offer(powerW: Float?, receivedAtMs: Long, stale: Boolean = false) {
        if (stale) return
        if (receivedAtMs < lastReceivedAtMs) return // tempo regressivo ignorado
        lastReceivedAtMs = receivedAtMs

        if (powerW == null || powerW.isNaN() || powerW.isInfinite()) return

        samples.add(Sample(receivedAtMs, powerW))
        prune(receivedAtMs)
        // Teto: rajada com o mesmo receivedAtMs não é podada pela janela; descarta as mais antigas.
        while (samples.size > MAX_SAMPLES) samples.removeFirst()
    }

    private fun prune(currentMs: Long) {
        val cutoff = currentMs - windowMs
        while (samples.isNotEmpty() && samples.first().timeMs < cutoff) samples.removeFirst()
    }

    /**
     * Retorna [n] barras em ESCALA ABSOLUTA FIXA (0..1) = potência média da fatia / [FULL_SCALE_W] (0–750 W),
     * com clamp em 0..1 (ADR 0013, adendo S43). Não depende do máximo da janela.
     * Média por fatia de ~1,25 s (para n=24 em 30 s).
     * Fatias sem amostra = 0f.
     * Retorna vazio se não houver amostras válidas na janela.
     */
    fun bars(n: Int = DEFAULT_BAR_COUNT, nowMs: Long? = null): List<Float> {
        val endMs = nowMs ?: lastReceivedAtMs
        if (endMs == Long.MIN_VALUE) return emptyList()

        val cutoff = endMs - windowMs
        val validSamples = samples.filter { it.timeMs in cutoff..endMs }
        if (validSamples.isEmpty()) return emptyList()

        val sliceMs = windowMs.toDouble() / n
        val sliceSums = DoubleArray(n)
        val sliceCounts = IntArray(n)

        for (s in validSamples) {
            val offset = s.timeMs - cutoff
            val idx = (offset / sliceMs).toInt().coerceIn(0, n - 1)
            sliceSums[idx] += s.powerW.toDouble()
            sliceCounts[idx]++
        }

        val sliceAverages = FloatArray(n) { i ->
            if (sliceCounts[i] > 0) (sliceSums[i] / sliceCounts[i]).toFloat() else 0f
        }

        return sliceAverages.map { (it / FULL_SCALE_W).coerceIn(0f, 1f) }
    }

    companion object {
        const val WINDOW_MS = 30_000L
        /** Fundo de escala fixo do gráfico: 750 W (altura da barra = W / 750). */
        const val FULL_SCALE_W = 750f
        const val DEFAULT_BAR_COUNT = 24
        /** Teto de amostras retidas (além da janela de 30 s): ~20 Hz já cabe em 600. */
        const val MAX_SAMPLES = 600
    }
}

package br.dev.evaldo.meuscooter.domain

import br.dev.evaldo.meuscooter.protocol.Telemetry

/** Métricas derivadas da telemetria; `null` = sem leitura suficiente. */
object TelemetryMetrics {
    /** Abaixo disso o consumo por km explodiria (divisão por ~0). */
    private const val MIN_SPEED_KMH = 1f

    fun powerW(t: Telemetry): Float? {
        t.powerW?.let { return it } // Meter2 dado[0..1] (real); senão V x I (Fake)
        val v = t.voltageV ?: return null
        val i = t.currentA ?: return null
        return v * i
    }

    fun whPerKm(t: Telemetry): Float? {
        val speed = t.speedKmh?.takeIf { it >= MIN_SPEED_KMH } ?: return null
        return powerW(t)?.div(speed)
    }
}

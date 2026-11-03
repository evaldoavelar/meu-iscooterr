package br.dev.evaldo.meuscooter.domain

import br.dev.evaldo.meuscooter.protocol.Telemetry

data class TripSummary(val distanceKm: Float, val durationMs: Long, val avgKmh: Float, val maxKmh: Float)

/** Acumula a viagem atual a partir da telemetria; não envia nada ao patinete. */
class TripTracker {
    private var firstMs: Long? = null
    private var lastMs = 0L
    private var lastKmh = 0f
    private var distanceKm = 0.0
    private var maxKmh = 0f

    val current: TripSummary?
        get() {
            val first = firstMs ?: return null
            val duration = lastMs - first
            val avg = if (duration > 0) (distanceKm / (duration / 3_600_000.0)).toFloat() else 0f
            return TripSummary(distanceKm.toFloat(), duration, avg, maxKmh)
        }

    fun onTelemetry(t: Telemetry) {
        val speed = t.speedKmh ?: return
        if (firstMs == null) firstMs = t.receivedAtMs else distanceKm += lastKmh * (t.receivedAtMs - lastMs) / 3_600_000.0
        lastMs = t.receivedAtMs
        lastKmh = speed
        if (speed > maxKmh) maxKmh = speed
    }

    /** Devolve a viagem acumulada (ou `null`) e recomeça vazio. */
    fun reset(): TripSummary? {
        val saved = current
        firstMs = null; lastMs = 0; lastKmh = 0f; distanceKm = 0.0; maxKmh = 0f
        return saved
    }
}

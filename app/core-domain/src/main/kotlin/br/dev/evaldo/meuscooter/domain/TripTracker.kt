package br.dev.evaldo.meuscooter.domain

import br.dev.evaldo.meuscooter.protocol.Telemetry

data class TripSummary(
    val distanceKm: Float,
    val durationMs: Long,
    val avgKmh: Float,
    val maxKmh: Float,
    val avgWhPerKm: Float? = null,
    val batteryStartPercent: Int? = null,
    val batteryEndPercent: Int? = null,
)

/** Acumula a viagem atual a partir da telemetria; não envia nada ao patinete. */
class TripTracker {
    private var firstMs: Long? = null
    private var lastMs = 0L
    private var lastKmh = 0f
    private var distanceKm = 0.0
    private var maxKmh = 0f
    private var lastPowerW: Float? = null
    private var energyWh = 0.0
    private var hasEnergy = false
    private var batteryStart: Int? = null
    private var batteryEnd: Int? = null

    val current: TripSummary?
        get() {
            val first = firstMs ?: return null
            val duration = lastMs - first
            val avg = if (duration > 0) (distanceKm / (duration / 3_600_000.0)).toFloat() else 0f
            val wh = if (hasEnergy && distanceKm > 0) (energyWh / distanceKm).toFloat() else null
            return TripSummary(distanceKm.toFloat(), duration, avg, maxKmh, wh, batteryStart, batteryEnd)
        }

    /** Âncora de integração: avança também em amostras sem velocidade, para não integrar lacunas. */
    private var anchorMs = 0L

    fun onTelemetry(t: Telemetry) {
        val now = t.receivedAtMs
        if (firstMs != null && now < anchorMs) return // tempo regressivo: ignora
        val speed = t.speedKmh
        if (speed == null) {
            if (firstMs != null) anchorMs = now
            return
        }
        if (firstMs == null) firstMs = now
        else if (now - anchorMs <= HomeRules.STALE_AFTER_MS) {
            val dt = now - anchorMs
            distanceKm += lastKmh * dt / 3_600_000.0
            lastPowerW?.let { energyWh += it * dt / 3_600_000.0; hasEnergy = true }
        }
        lastPowerW = TelemetryMetrics.powerW(t)
        t.batteryPercent?.let { if (batteryStart == null) batteryStart = it; batteryEnd = it }
        anchorMs = now
        lastMs = now
        lastKmh = speed
        if (speed > maxKmh) maxKmh = speed
    }

    /** Devolve a viagem acumulada (ou `null`) e recomeça vazio. */
    fun reset(): TripSummary? {
        val saved = current
        firstMs = null; lastMs = 0; anchorMs = 0; lastKmh = 0f; distanceKm = 0.0; maxKmh = 0f
        lastPowerW = null; energyWh = 0.0; hasEnergy = false; batteryStart = null; batteryEnd = null
        return saved
    }
}

package br.dev.evaldo.meuscooter.ui

import br.dev.evaldo.meuscooter.domain.TelemetryMetrics
import br.dev.evaldo.meuscooter.domain.TripRecState
import br.dev.evaldo.meuscooter.domain.TripSummary
import br.dev.evaldo.meuscooter.domain.Units
import br.dev.evaldo.meuscooter.protocol.Telemetry
import java.util.Locale
import kotlin.math.roundToInt

/** Textos prontos da tela de Telemetria (design/telemetry.py). Sem leitura = "--". */
data class TelemetryUiState(
    val speedText: String,
    val speedUnit: String,
    val gaugeMaxText: String,
    val gaugeFraction: Float,
    val batteryText: String,
    val powerText: String,
    val efficiencyText: String,
    val currentText: String,
    val voltageText: String,
    val motor1Text: String,
    val motor2Text: String,
    val motor2Note: String?,
    val tripOdometerText: String,
    val totalOdometerText: String,
    val avgText: String,
    val maxText: String,
    val durationText: String,
    /** "Contando" / "Pausada" / "Parada" (ao lado da Duração). */
    val recStateText: String = "Contando",
    val recState: TripRecState = TripRecState.Running,
    val altitudeText: String,
    /** Barras do perfil de altimetria (0..1); vazio = sem leitura. */
    val altitudeBars: List<Float> = emptyList(),
    /** Barras do gráfico de consumo dos últimos 30 s (0..1); vazio = sem leitura. */
    val powerBars: List<Float> = emptyList(),
    /** "Dados desatualizados" quando a telemetria envelheceu ou a conexão caiu; `null` caso contrário. */
    val staleNote: String? = null,
)

class TelemetryUiMapper {
    fun map(
        reading: Telemetry?,
        trip: TripSummary?,
        units: Units = Units.Metric,
        stale: Boolean = false,
        altitudesM: List<Float> = emptyList(),
        batteryStale: Boolean = false,
        meter2Stale: Boolean = false,
        recState: TripRecState = TripRecState.Running,
        powerBars: List<Float> = emptyList(),
    ): TelemetryUiState {
        // Leitura velha ou conexão caída não pode parecer atual: tudo que é "ao vivo" vira "--" (como a Home); a viagem fica.
        val t = reading.takeUnless { stale }
        // Meter2 (potência/tensão/corrente/odômetro/temp. motor) velho não aparece como atual; a bateria segue o Meter1.
        val m2 = t.takeUnless { meter2Stale }
        val speed = t?.speedKmh
        val power = m2?.let(TelemetryMetrics::powerW)
        val whPerKm = m2?.let(TelemetryMetrics::whPerKm)
        return TelemetryUiState(
            speedText = speed?.let { units.speed(it).roundToInt().toString() } ?: UNKNOWN,
            speedUnit = units.speedLabel,
            gaugeMaxText = units.speed(MAX_GAUGE_KMH).roundToInt().toString(),
            gaugeFraction = ((speed ?: 0f) / MAX_GAUGE_KMH).coerceIn(0f, 1f),
            batteryText = t?.batteryPercent?.takeUnless { batteryStale }?.let { "$it%" } ?: UNKNOWN,
            powerText = power?.let { "${it.roundToInt()} W" } ?: UNKNOWN,
            efficiencyText = when {
                m2 == null || speed == null || power == null -> UNKNOWN   // sem leitura ≠ parado
                whPerKm != null -> "${decimal(units.perDistance(whPerKm), 1)} Wh/${units.distanceLabel}"
                speed == 0f -> STOPPED_EFFICIENCY   // só roda parada; 0 < v < 1 km/h não tem Wh/km nem afirma "sem consumo"
                else -> ""
            },
            currentText = m2?.currentA?.let { decimal(it, 1) } ?: UNKNOWN,
            voltageText = m2?.voltageV?.let { decimal(it, 1) } ?: UNKNOWN,
            motor1Text = m2?.motor1TempC?.toString() ?: UNKNOWN,
            motor2Text = t?.motor2TempC?.toString() ?: UNKNOWN,
            motor2Note = if (t != null && t.motor2TempC == null) NO_READING else null,
            tripOdometerText = "${decimal(units.distance(trip?.distanceKm ?: 0f), 2)} ${units.distanceLabel}",
            totalOdometerText = m2?.totalKm?.let { "${KM_FORMAT.format(units.distance(it))} ${units.distanceLabel}" } ?: UNKNOWN,
            avgText = "${units.speed(trip?.avgKmh ?: 0f).roundToInt()} ${units.speedLabel}",
            maxText = "${units.speed(trip?.maxKmh ?: 0f).roundToInt()} ${units.speedLabel}",
            durationText = duration(trip?.durationMs ?: 0),
            recStateText = when (recState) { TripRecState.Running -> "Contando"; TripRecState.Paused -> "Pausada"; TripRecState.Stopped -> "Parada" },
            recState = recState,
            // Altimetria: o patinete não informa (`[HIPÓTESE]`: viria do GPS do celular). Sem perfil → "Sem leitura", nada inventado.
            altitudeText = if (altitudesM.size >= 2) "+${units.altitude(gain(altitudesM)).roundToInt()} ${units.altitudeLabel}" else NO_READING,
            altitudeBars = bars(altitudesM),
            powerBars = powerBars,
            staleNote = if (stale && reading != null) STALE_NOTE else null,
        )
    }

    private fun gain(a: List<Float>) = a.zipWithNext().sumOf { (x, y) -> maxOf(0f, y - x).toDouble() }.toFloat()

    private fun bars(a: List<Float>): List<Float> {
        if (a.size < 2) return emptyList()
        val lo = a.min(); val hi = a.max()
        return a.map { if (hi == lo) 0f else (it - lo) / (hi - lo) }
    }

    private fun decimal(v: Float, digits: Int) = String.format(PT_BR, "%.${digits}f", v)

    private fun duration(ms: Long): String {
        val s = ms / 1000
        return if (s >= 3600) String.format(PT_BR, "%d:%02d:%02d", s / 3600, s % 3600 / 60, s % 60)
        else String.format(PT_BR, "%02d:%02d", s / 60, s % 60)
    }

    private companion object {
        const val UNKNOWN = "--"
        const val NO_READING = "Sem leitura"
        const val STALE_NOTE = "Dados desatualizados"
        const val STOPPED_EFFICIENCY = "Parado · sem consumo"
        const val MAX_GAUGE_KMH = 60f
        val PT_BR: Locale = Locale.forLanguageTag("pt-BR")
        val KM_FORMAT = java.text.DecimalFormat("#,##0", java.text.DecimalFormatSymbols(PT_BR))
    }
}

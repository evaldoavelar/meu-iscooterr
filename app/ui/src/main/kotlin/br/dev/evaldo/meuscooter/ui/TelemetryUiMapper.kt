package br.dev.evaldo.meuscooter.ui

import br.dev.evaldo.meuscooter.domain.TelemetryMetrics
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
    val altitudeText: String,
    /** "Dados desatualizados" quando a telemetria envelheceu ou a conexão caiu; `null` caso contrário. */
    val staleNote: String? = null,
)

class TelemetryUiMapper {
    fun map(reading: Telemetry?, trip: TripSummary?, units: Units = Units.Metric, stale: Boolean = false): TelemetryUiState {
        // Leitura velha ou conexão caída não pode parecer atual: tudo que é "ao vivo" vira "--" (como a Home); a viagem fica.
        val t = reading.takeUnless { stale }
        val speed = t?.speedKmh
        val power = t?.let(TelemetryMetrics::powerW)
        val whPerKm = t?.let(TelemetryMetrics::whPerKm)
        return TelemetryUiState(
            speedText = speed?.let { units.speed(it).roundToInt().toString() } ?: UNKNOWN,
            speedUnit = units.speedLabel,
            gaugeMaxText = units.speed(MAX_GAUGE_KMH).roundToInt().toString(),
            gaugeFraction = ((speed ?: 0f) / MAX_GAUGE_KMH).coerceIn(0f, 1f),
            batteryText = t?.batteryPercent?.let { "$it%" } ?: UNKNOWN,
            powerText = power?.let { "${it.roundToInt()} W" } ?: UNKNOWN,
            efficiencyText = when {
                t == null -> UNKNOWN
                whPerKm != null -> "${decimal(units.perDistance(whPerKm), 1)} Wh/${units.distanceLabel}"
                else -> "Parado · sem consumo"
            },
            currentText = t?.currentA?.let { decimal(it, 1) } ?: UNKNOWN,
            voltageText = t?.voltageV?.let { decimal(it, 1) } ?: UNKNOWN,
            motor1Text = t?.motor1TempC?.toString() ?: UNKNOWN,
            motor2Text = t?.motor2TempC?.toString() ?: UNKNOWN,
            motor2Note = if (t != null && t.motor2TempC == null) NO_READING else null,
            tripOdometerText = "${decimal(units.distance(trip?.distanceKm ?: 0f), 2)} ${units.distanceLabel}",
            totalOdometerText = t?.totalKm?.let { "${KM_FORMAT.format(units.distance(it))} ${units.distanceLabel}" } ?: UNKNOWN,
            avgText = "${units.speed(trip?.avgKmh ?: 0f).roundToInt()} ${units.speedLabel}",
            maxText = "${units.speed(trip?.maxKmh ?: 0f).roundToInt()} ${units.speedLabel}",
            durationText = duration(trip?.durationMs ?: 0),
            altitudeText = NO_READING, // altimetria (GPS do celular) fica fora desta sprint `[HIPÓTESE]`
            staleNote = if (stale && reading != null) STALE_NOTE else null,
        )
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
        const val MAX_GAUGE_KMH = 60f
        val PT_BR: Locale = Locale.forLanguageTag("pt-BR")
        val KM_FORMAT = java.text.DecimalFormat("#,##0", java.text.DecimalFormatSymbols(PT_BR))
    }
}

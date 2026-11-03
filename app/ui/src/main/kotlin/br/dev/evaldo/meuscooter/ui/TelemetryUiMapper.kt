package br.dev.evaldo.meuscooter.ui

import br.dev.evaldo.meuscooter.domain.TelemetryMetrics
import br.dev.evaldo.meuscooter.domain.TripSummary
import br.dev.evaldo.meuscooter.protocol.Telemetry
import java.util.Locale
import kotlin.math.roundToInt

/** Textos prontos da tela de Telemetria (design/telemetry.py). Sem leitura = "--". */
data class TelemetryUiState(
    val speedText: String,
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
)

class TelemetryUiMapper {
    fun map(t: Telemetry?, trip: TripSummary?): TelemetryUiState {
        val speed = t?.speedKmh
        val power = t?.let(TelemetryMetrics::powerW)
        val whPerKm = t?.let(TelemetryMetrics::whPerKm)
        return TelemetryUiState(
            speedText = speed?.roundToInt()?.toString() ?: UNKNOWN,
            gaugeFraction = ((speed ?: 0f) / MAX_GAUGE_KMH).coerceIn(0f, 1f),
            batteryText = t?.batteryPercent?.let { "$it%" } ?: UNKNOWN,
            powerText = power?.let { "${it.roundToInt()} W" } ?: UNKNOWN,
            efficiencyText = when {
                t == null -> UNKNOWN
                whPerKm != null -> "${decimal(whPerKm, 1)} Wh/km"
                else -> "Parado · sem consumo"
            },
            currentText = t?.currentA?.let { decimal(it, 1) } ?: UNKNOWN,
            voltageText = t?.voltageV?.let { decimal(it, 1) } ?: UNKNOWN,
            motor1Text = t?.motor1TempC?.toString() ?: UNKNOWN,
            motor2Text = t?.motor2TempC?.toString() ?: UNKNOWN,
            motor2Note = if (t != null && t.motor2TempC == null) NO_READING else null,
            tripOdometerText = "${decimal(trip?.distanceKm ?: 0f, 2)} km",
            totalOdometerText = t?.totalKm?.let { "${KM_FORMAT.format(it)} km" } ?: UNKNOWN,
            avgText = "${(trip?.avgKmh ?: 0f).roundToInt()} km/h",
            maxText = "${(trip?.maxKmh ?: 0f).roundToInt()} km/h",
            durationText = duration(trip?.durationMs ?: 0),
            altitudeText = NO_READING, // altimetria (GPS do celular) fica fora desta sprint `[HIPÓTESE]`
        )
    }

    private fun decimal(v: Float, digits: Int) = String.format(PT_BR, "%.${digits}f", v)

    private fun duration(ms: Long): String {
        val s = ms / 1000
        return String.format(PT_BR, "%02d:%02d", s / 60, s % 60)
    }

    private companion object {
        const val UNKNOWN = "--"
        const val NO_READING = "Sem leitura"
        const val MAX_GAUGE_KMH = 60f
        val PT_BR: Locale = Locale.forLanguageTag("pt-BR")
        val KM_FORMAT = java.text.DecimalFormat("#,##0", java.text.DecimalFormatSymbols(PT_BR))
    }
}

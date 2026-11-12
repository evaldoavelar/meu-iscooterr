package br.dev.evaldo.meuscooter.ui

import br.dev.evaldo.meuscooter.domain.PowerHistory
import br.dev.evaldo.meuscooter.domain.SpeedSource
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
    /** Fonte do velocímetro (ícone e página ativa). */
    val speedSource: SpeedSource = SpeedSource.Scooter,
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
    /** Potência ATUAL na escala fixa 0–750 W (0..1; linha tracejada e resumo a11y); `null` = sem dado (sem barras nem linha). */
    val powerNowFraction: Float? = null,
    /** "Dados desatualizados" quando a telemetria envelheceu ou a conexão caiu; `null` caso contrário. */
    val staleNote: String? = null,
    /** Sem telemetria fresca (velha ou sem leitura): o patinete não está entregando dados. */
    val noScooter: Boolean = false,
    /** Viagem contando SEM telemetria fresca: o rótulo ("Parada" desde a S48) fica na cor de erro. */
    val recStateIsError: Boolean = false,
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
        speedSource: SpeedSource = SpeedSource.Scooter,
    ): TelemetryUiState {
        // Leitura velha ou conexão caída não pode parecer atual: tudo que é "ao vivo" vira "--" (como a Home); a viagem fica.
        val t = reading.takeUnless { stale }
        // Meter2 (potência/tensão/corrente/odômetro/temp. motor) velho não aparece como atual; a bateria segue o Meter1.
        val m2 = t.takeUnless { meter2Stale }
        val speed = t?.speedKmh   // GPS (withGpsSpeed): base do consumo e da viagem
        // Velocímetro: patinete (Meter1, velho = desconhecido) ou GPS. Desconhecida = "--" e arco vazio.
        val gaugeSpeed = when (speedSource) {
            SpeedSource.Scooter -> t?.scooterSpeedKmh?.takeUnless { batteryStale }
            SpeedSource.Gps -> speed
        }
        val noScooter = t == null   // sem telemetria fresca = velha OU sem leitura
        val power = m2?.let(TelemetryMetrics::powerW)
        val whPerKm = m2?.let(TelemetryMetrics::whPerKm)
        return TelemetryUiState(
            speedText = gaugeSpeed?.let { units.speed(it).roundToInt().toString() } ?: UNKNOWN,
            speedUnit = units.speedLabel,
            gaugeMaxText = units.speed(MAX_GAUGE_KMH).roundToInt().toString(),
            speedSource = speedSource,
            gaugeFraction = ((gaugeSpeed ?: 0f) / MAX_GAUGE_KMH).coerceIn(0f, 1f),
            batteryText = t?.batteryPercent?.takeUnless { batteryStale }?.let { "$it%" } ?: UNKNOWN,
            powerText = power?.let { "${it.roundToInt()} W" } ?: NO_POWER,
            powerNowFraction = power?.let { (it / PowerHistory.FULL_SCALE_W).coerceIn(0f, 1f) },
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
            // S48: sem telemetria fresca nada está sendo contado: o rótulo diz "Parada" (em vermelho, ver recStateIsError).
            recStateText = when (recState) { TripRecState.Running -> if (noScooter) "Parada" else "Contando"; TripRecState.Paused -> "Pausada"; TripRecState.Stopped -> "Parada" },
            recState = recState,
            // Altimetria: o patinete não informa (`[HIPÓTESE]`: viria do GPS do celular). Sem perfil → "Sem leitura", nada inventado.
            altitudeText = if (altitudesM.size >= 2) "+${units.altitude(gain(altitudesM)).roundToInt()} ${units.altitudeLabel}" else NO_READING,
            altitudeBars = bars(altitudesM),
            powerBars = if (power == null) emptyList() else powerBars,
            staleNote = if (stale && reading != null) STALE_NOTE else null,
            noScooter = noScooter,
            recStateIsError = recState == TripRecState.Running && noScooter,
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
        const val NO_POWER = "-- W"
        const val NO_READING = "Sem leitura"
        const val STALE_NOTE = "Dados desatualizados"
        const val STOPPED_EFFICIENCY = "Parado · sem consumo"
        const val MAX_GAUGE_KMH = 60f
        val PT_BR: Locale = Locale.forLanguageTag("pt-BR")
        val KM_FORMAT = java.text.DecimalFormat("#,##0", java.text.DecimalFormatSymbols(PT_BR))
    }
}

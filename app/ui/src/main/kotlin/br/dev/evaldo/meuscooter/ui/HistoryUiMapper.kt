package br.dev.evaldo.meuscooter.ui

import br.dev.evaldo.meuscooter.domain.HistorySummary
import br.dev.evaldo.meuscooter.domain.Trip
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import java.util.Locale
import kotlin.math.roundToInt

data class HistoryRowUi(val id: Long, val section: String?, val title: String, val headline: String, val subtitle: String)
data class HistorySummaryUi(val count: String, val distance: String, val time: String)
data class HistoryUiState(val summary: HistorySummaryUi, val rows: List<HistoryRowUi>, val empty: Boolean)

data class TripDetailUi(
    val title: String, val distance: String, val duration: String, val avg: String, val max: String,
    val altitude: String, val consumption: String, val battery: String?,
)

/** Textos do histórico (design/history.py). Altimetria fica "Sem leitura" `[HIPÓTESE]`. */
class HistoryUiMapper(private val zone: ZoneId = ZoneId.systemDefault()) {
    fun map(trips: List<Trip>, nowMs: Long): HistoryUiState {
        val today = LocalDate.now(java.time.Clock.fixed(Instant.ofEpochMilli(nowMs), zone))
        var previousKey: String? = null
        val rows = trips.sortedByDescending { it.startedAtMs }.map { t ->
            val key = groupOf(date(t), today)
            val row = HistoryRowUi(
                t.id, key.takeIf { it != previousKey }, title(t, today),
                "${km(t.distanceKm)} · ${minutes(t.durationMs)}", "média ${t.avgKmh.roundToInt()} km/h · máx ${t.maxKmh.roundToInt()} km/h",
            )
            previousKey = key
            row
        }
        val s = HistorySummary.of(trips, nowMs)
        return HistoryUiState(HistorySummaryUi(s.count.toString(), km(s.distanceKm), hoursMinutes(s.durationMs)), rows, trips.isEmpty())
    }

    fun detail(t: Trip, nowMs: Long): TripDetailUi {
        val today = LocalDate.now(java.time.Clock.fixed(Instant.ofEpochMilli(nowMs), zone))
        val start = t.batteryStartPercent
        val end = t.batteryEndPercent
        return TripDetailUi(
            title(t, today), km(t.distanceKm), minutes(t.durationMs), "${t.avgKmh.roundToInt()} km/h", "${t.maxKmh.roundToInt()} km/h",
            "Sem leitura", t.avgWhPerKm?.let { "${it.roundToInt()} Wh/km" } ?: "--",
            if (start != null && end != null) "$start% → $end%" else null,
        )
    }

    private fun date(t: Trip) = Instant.ofEpochMilli(t.startedAtMs).atZone(zone)

    private fun title(t: Trip, today: LocalDate): String {
        val d = date(t)
        val time = String.format(PT_BR, "%02d:%02d", d.hour, d.minute)
        return when (d.toLocalDate()) {
            today -> "Hoje, $time"
            today.minusDays(1) -> "Ontem, $time"
            else -> "${WEEKDAYS[d.dayOfWeek.value - 1]} ${d.dayOfMonth}, $time"
        }
    }

    private fun groupOf(d: java.time.ZonedDateTime, today: LocalDate): String {
        val day = d.toLocalDate()
        if (day == today) return "Hoje"
        if (day == today.minusDays(1)) return "Ontem"
        val monday = day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val thisMonday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        return when (monday) {
            thisMonday -> "Esta semana"
            thisMonday.minusWeeks(1) -> "Semana passada"
            else -> "Anteriores"
        }
    }

    private fun km(v: Float) = "${String.format(PT_BR, "%.1f", v)} km"
    private fun minutes(ms: Long) = "${(ms / 60_000.0).roundToInt()} min"
    private fun hoursMinutes(ms: Long): String {
        val total = ms / 60_000
        return if (total < 60) "$total min" else String.format(PT_BR, "%d h %02d", total / 60, total % 60)
    }

    private companion object {
        val PT_BR: Locale = Locale.forLanguageTag("pt-BR")
        val WEEKDAYS = listOf("Seg", "Ter", "Qua", "Qui", "Sex", "Sáb", "Dom")
    }
}

package br.dev.evaldo.meuscooter.ui

import br.dev.evaldo.meuscooter.domain.AppEvent
import br.dev.evaldo.meuscooter.domain.AppEventType
import br.dev.evaldo.meuscooter.domain.DaySummary
import br.dev.evaldo.meuscooter.domain.EventFilter
import br.dev.evaldo.meuscooter.domain.EventSeverity
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class EventRowUi(val id: Long, val title: String, val detail: String, val time: String, val severity: EventSeverity, val glyph: String)
data class EventDayUi(val header: String, val rows: List<EventRowUi>)
/** [empty] = não há NENHUM evento; [filteredEmpty] = há eventos, mas nenhum passa no filtro (o resumo do dia segue visível). */
data class EventLogUiState(val summary: String, val last: String?, val days: List<EventDayUi>, val empty: Boolean, val filteredEmpty: Boolean = false)

/** Textos da tela "Registro de eventos": filtro, resumo do dia local, grupos por data em ordem decrescente. */
class EventLogUiMapper(private val zone: ZoneId = ZoneId.systemDefault()) {
    private val timeFormat = DateTimeFormatter.ofPattern("HH:mm:ss")

    fun map(events: List<AppEvent>, filter: EventFilter, nowMs: Long): EventLogUiState {
        val summary = DaySummary.of(events, zone, nowMs)
        val rows = events.filter { filter.matches(it.type) }.sortedWith(compareByDescending<AppEvent> { it.atMs }.thenByDescending { it.id })
        val days = rows.groupBy { date(it.atMs) }.map { (day, list) -> EventDayUi(header(day), list.map(::row)) }
        return EventLogUiState(summaryText(summary), summary.lastAtMs?.let { time(it) }, days, events.isEmpty(), events.isNotEmpty() && rows.isEmpty())
    }

    private fun date(ms: Long): LocalDate = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
    private fun time(ms: Long): String = timeFormat.format(Instant.ofEpochMilli(ms).atZone(zone))
    private fun header(d: LocalDate) = "${d.dayOfMonth} ${MONTHS[d.monthValue - 1]} ${d.year}"

    private fun row(e: AppEvent) = EventRowUi(e.id, e.title, e.detail, time(e.atMs), e.severity, glyph(e.type))

    private fun glyph(t: AppEventType) = when (t) {
        AppEventType.Unlock -> "U"
        AppEventType.Lock -> "L"
        AppEventType.Connected, AppEventType.Reconnecting -> "B"
        AppEventType.Disconnected, AppEventType.LinkDropped -> "b"
        AppEventType.Refused, AppEventType.Error -> "!"
        AppEventType.WrongPin -> "×"
    }

    private fun summaryText(s: DaySummary): String {
        val parts = listOfNotNull(
            plural(s.unlocks, "desbloqueio", "desbloqueios"), plural(s.locks, "bloqueio", "bloqueios"), plural(s.drops, "queda", "quedas"),
        )
        return if (parts.isEmpty()) "Nenhum desbloqueio, bloqueio ou queda hoje" else parts.joinToString(" · ")
    }

    private fun plural(n: Int, one: String, many: String) = if (n == 0) null else "$n ${if (n == 1) one else many}"

    private companion object {
        val MONTHS = listOf("JAN", "FEV", "MAR", "ABR", "MAI", "JUN", "JUL", "AGO", "SET", "OUT", "NOV", "DEZ")
    }
}

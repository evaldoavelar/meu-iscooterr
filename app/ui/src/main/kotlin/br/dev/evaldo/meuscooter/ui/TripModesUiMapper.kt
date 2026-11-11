package br.dev.evaldo.meuscooter.ui

import br.dev.evaldo.meuscooter.domain.DriveModes
import br.dev.evaldo.meuscooter.domain.GeoPoint
import br.dev.evaldo.meuscooter.domain.Trip
import br.dev.evaldo.meuscooter.domain.analyzeTripModes
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import kotlin.math.roundToInt

/** Token de cor do tema (resolvido na UI: Walking=success, Eco=primary, Sport=pending, Race=error). */
enum class SegTone { Success, Primary, Pending, Error, Neutral }

/** Padrão do traço, para que a cor nunca seja a única pista (design/s43.py). */
enum class LinePattern { Dotted, Dashed, Solid, Thick }

enum class TripView { Mode, Motor, None }

data class TripSegUi(val points: List<GeoPoint>, val tone: SegTone, val pattern: LinePattern)
data class TripMarkerUi(val n: Int, val at: GeoPoint?, val tone: SegTone)
data class TripLegendUi(val label: String, val tone: SegTone, val pattern: LinePattern)
data class TripChangeUi(val n: Int, val time: String, val text: String, val tone: SegTone)
data class TripBlockUi(val label: String, val weight: Float, val tone: SegTone, val pattern: LinePattern)
data class TripTimeRowUi(val label: String, val tone: SegTone, val value: String, val fraction: Float)

/** Uma visão (por modo ou por motor) do detalhe da viagem. */
data class TripViewUi(
    val segments: List<TripSegUi>, val markers: List<TripMarkerUi>, val legend: List<TripLegendUi>,
    val changes: List<TripChangeUi>, val blocks: List<TripBlockUi>, val startTime: String, val endTime: String,
    val singleText: String?, val timesTitle: String, val timeRows: List<TripTimeRowUi>,
    val timelineDescription: String, val mapDescription: String,
)

/** `mode`/`motor` nulos = viagem sem eventos (seletor desabilitado, trajeto sem cores). */
data class TripModesUi(val mode: TripViewUi?, val motor: TripViewUi?) {
    val hasData: Boolean get() = mode != null && motor != null
    companion object { val None = TripModesUi(null, null) }
}

/** Mapeia a análise de domínio ([analyzeTripModes]) para dados de UI; sem Compose. */
object TripModesUiMapper {
    private val PT_BR: Locale = Locale.forLanguageTag("pt-BR")

    fun modeTone(mode: Int?) = when (mode) {
        DriveModes.WALKING -> SegTone.Success
        DriveModes.ECO -> SegTone.Primary
        DriveModes.SPORT -> SegTone.Pending
        DriveModes.RACE -> SegTone.Error
        else -> SegTone.Neutral
    }

    fun modePattern(mode: Int?) = when (mode) {
        DriveModes.WALKING -> LinePattern.Dotted
        DriveModes.ECO -> LinePattern.Dashed
        DriveModes.RACE -> LinePattern.Thick
        else -> LinePattern.Solid
    }

    fun motorTone(dual: Boolean?) = when (dual) { true -> SegTone.Primary; false -> SegTone.Pending; null -> SegTone.Neutral }
    fun motorPattern(dual: Boolean?) = if (dual == false) LinePattern.Dashed else LinePattern.Solid

    private fun modeName(mode: Int?) = if (mode == null) "Sem leitura" else DriveModes.labelOf(mode)
    private fun modeRank(mode: Int?) = when (mode) { DriveModes.WALKING -> 0; DriveModes.ECO -> 1; DriveModes.SPORT -> 2; DriveModes.RACE -> 3; else -> 4 }
    private fun motorRank(dual: Boolean?) = when (dual) { true -> 0; false -> 1; null -> 2 }

    fun map(t: Trip, zone: ZoneId): TripModesUi {
        if (t.modeEvents.isEmpty()) return TripModesUi.None
        val a = analyzeTripModes(t.route, t.modeEvents, t.durationMs)
        val total = maxOf(t.durationMs, t.modeEvents.maxOf { it.elapsedMs })
        val startTime = clock(t.startedAtMs, zone)
        val endTime = clock(t.startedAtMs + total, zone)
        fun at(elapsed: Long) = clock(t.startedAtMs + elapsed, zone)

        val modeView = build(
            ids = a.modeSegments.map { Seg(it.mode, it.points, it.durationMs, it.startElapsedMs) },
            name = { modeName(it as Int?) }, tone = { modeTone(it as Int?) }, pattern = { modePattern(it as Int?) }, rank = { modeRank(it as Int?) },
            total = total, startTime = startTime, endTime = endTime, at = ::at,
            timesTitle = "Tempo em cada modo", singleSuffix = "durante toda a viagem",
            kindLabel = "modo",
        )
        val motorView = build(
            ids = a.motorSegments.map { Seg(it.dualMotor, it.points, it.durationMs, it.startElapsedMs) },
            name = { DriveModes.motorLabelOf(it as Boolean?) }, tone = { motorTone(it as Boolean?) }, pattern = { motorPattern(it as Boolean?) },
            rank = { motorRank(it as Boolean?) },
            total = total, startTime = startTime, endTime = endTime, at = ::at,
            timesTitle = "Tempo com cada motor", singleSuffix = "durante toda a viagem",
            kindLabel = "motor",
        )
        return TripModesUi(modeView, motorView)
    }

    private class Seg(val key: Any?, val points: List<GeoPoint>, val durationMs: Long, val startElapsedMs: Long)

    private fun build(
        ids: List<Seg>, name: (Any?) -> String, tone: (Any?) -> SegTone, pattern: (Any?) -> LinePattern, rank: (Any?) -> Int,
        total: Long, startTime: String, endTime: String, at: (Long) -> String, timesTitle: String, singleSuffix: String, kindLabel: String,
    ): TripViewUi {
        val segments = ids.filter { it.points.size >= 2 }.map { TripSegUi(it.points, tone(it.key), pattern(it.key)) }
        val changes = ids.drop(1).mapIndexed { i, s ->
            val from = ids[i].key
            TripChangeUi(i + 1, at(s.startElapsedMs), "${name(from)} → ${name(s.key)}", tone(s.key))
        }
        val markers = ids.drop(1).mapIndexed { i, s -> TripMarkerUi(i + 1, s.points.firstOrNull().takeIf { ids[i].points.size >= 2 || s.points.size >= 2 }, tone(s.key)) }
            .filter { it.at != null && segments.isNotEmpty() }
        val blocks = ids.map { TripBlockUi(name(it.key), it.durationMs.coerceAtLeast(1L).toFloat(), tone(it.key), pattern(it.key)) }
        val sums = LinkedHashMap<Any?, Long>()
        ids.forEach { sums[it.key] = (sums[it.key] ?: 0L) + it.durationMs }
        val sumTotal = sums.values.sum()
        val rows = sums.entries.sortedBy { rank(it.key) }.map { (k, d) ->
            val pct = if (sumTotal > 0) ((d.toDouble() / sumTotal) * 100.0).roundToInt() else 0
            TripTimeRowUi(name(k), tone(k), "${minutes(d)} · $pct %", pct / 100f)
        }
        val legend = sums.keys.sortedBy { rank(it) }.map { TripLegendUi(name(it), tone(it), pattern(it)) }
        val single = if (sums.size == 1 && ids.size == 1) "Sem trocas: ${name(ids[0].key)} $singleSuffix." else null
        val timelineDesc = "Linha do tempo de $startTime a $endTime: " +
            ids.joinToString("; ") { "${name(it.key)} das ${at(it.startElapsedMs)} (${minutes(it.durationMs)})" } + "."
        val mapDesc = "Trajeto colorido por $kindLabel. " + legend.joinToString(", ") { it.label } +
            if (changes.isEmpty()) ". Sem trocas." else ". ${changes.size} ${if (changes.size == 1) "troca" else "trocas"}: " + changes.joinToString("; ") { "${it.time} ${it.text}" } + "."
        return TripViewUi(segments, markers, legend, changes, blocks, startTime, endTime, single, timesTitle, rows, timelineDesc, mapDesc)
    }

    private fun clock(ms: Long, zone: ZoneId): String {
        val d = Instant.ofEpochMilli(ms).atZone(zone)
        return String.format(PT_BR, "%02d:%02d", d.hour, d.minute)
    }

    private fun minutes(ms: Long) = if (ms < 30_000) "< 1 min" else "${(ms / 60_000.0).roundToInt()} min"
}

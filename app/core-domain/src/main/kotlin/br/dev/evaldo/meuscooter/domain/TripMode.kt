package br.dev.evaldo.meuscooter.domain

/** Evento pontual de modo de condução e tração na viagem (ADR 0016). */
data class TripModeEvent(
    val elapsedMs: Long,
    val mode: Int?,
    val dualMotor: Boolean?,
)

/** Segmento de trajeto associado a um modo de condução (ADR 0016). */
data class ModeSegment(
    val mode: Int?,
    val points: List<GeoPoint>,
    val durationMs: Long,
    val startElapsedMs: Long,
    val endElapsedMs: Long,
)

/** Segmento de trajeto associado à tração (1 motor vs. 2 motores) (ADR 0016). */
data class MotorSegment(
    val dualMotor: Boolean?,
    val points: List<GeoPoint>,
    val durationMs: Long,
    val startElapsedMs: Long,
    val endElapsedMs: Long,
)

/** Resumo de tempo acumulado e porcentagem em um modo de condução (ADR 0016). */
data class ModeTimeSummary(
    val mode: Int?,
    val durationMs: Long,
    val percent: Int,
)

/** Resultado consolidado da análise de modos e tração da viagem para a UI (ADR 0016). */
data class TripModeAnalysis(
    val modeSegments: List<ModeSegment>,
    val motorSegments: List<MotorSegment>,
    val modeTimes: List<ModeTimeSummary>,
)

object DriveModes {
    const val WALKING = 0
    const val ECO = 1
    const val RACE = 2
    const val SPORT = 3

    val LABELS = mapOf(
        WALKING to "Walking",
        ECO to "Eco",
        RACE to "Race",
        SPORT to "Sport",
    )

    fun labelOf(mode: Int?): String = mode?.let { LABELS[it] } ?: "Modo não registrado"
    fun motorLabelOf(dualMotor: Boolean?): String = when (dualMotor) {
        true -> "2 motores"
        false -> "1 motor"
        null -> "Sem leitura"
    }
}

/**
 * Função pura que, dados o trajeto e os eventos de modo da viagem, devolve:
 * 1. Os segmentos do trajeto recortados por modo de condução;
 * 2. Os segmentos do trajeto contíguos por tração (motor);
 * 3. O tempo (em ms e %) decorrido em cada modo.
 * Viagens sem eventos devolvem listas vazias ("Modo não registrado").
 */
fun analyzeTripModes(
    route: List<GeoPoint>,
    events: List<TripModeEvent>,
    totalDurationMs: Long,
): TripModeAnalysis {
    if (events.isEmpty()) {
        return TripModeAnalysis(
            modeSegments = emptyList(),
            motorSegments = emptyList(),
            modeTimes = emptyList(),
        )
    }

    val sorted = events.sortedBy { it.elapsedMs }
    val duration = maxOf(totalDurationMs, sorted.last().elapsedMs)

    // Constrói os intervalos temporais elementares
    data class Interval(
        val start: Long,
        val end: Long,
        val dur: Long,
        val mode: Int?,
        val dualMotor: Boolean?,
    )

    val intervals = sorted.indices.map { i ->
        val start = sorted[i].elapsedMs
        val end = if (i + 1 < sorted.size) sorted[i + 1].elapsedMs else duration
        val dur = maxOf(0L, end - start)
        Interval(start, end, dur, sorted[i].mode, sorted[i].dualMotor)
    }

    // 1. Segmentos de modo: funde intervalos contíguos com o mesmo modo
    val modeSegments = ArrayList<ModeSegment>()
    var currentMode: Int? = intervals.first().mode
    var segStart = intervals.first().start
    var segDur = 0L

    fun closeModeSegment(segEnd: Long) {
        val f0 = if (duration > 0) segStart.toDouble() / duration else 0.0
        val f1 = if (duration > 0) segEnd.toDouble() / duration else 1.0
        val pts = sliceRoute(route, f0, f1)
        modeSegments.add(ModeSegment(currentMode, pts, segDur, segStart, segEnd))
    }

    intervals.forEachIndexed { i, interval ->
        if (i > 0 && interval.mode != currentMode) {
            closeModeSegment(interval.start)
            currentMode = interval.mode
            segStart = interval.start
            segDur = 0L
        }
        segDur += interval.dur
    }
    closeModeSegment(intervals.last().end)

    // 2. Segmentos de motor: funde intervalos contíguos com a mesma tração
    val motorSegments = ArrayList<MotorSegment>()
    var currentMotor: Boolean? = intervals.first().dualMotor
    var motorSegStart = intervals.first().start
    var motorSegDur = 0L

    fun closeMotorSegment(segEnd: Long) {
        val f0 = if (duration > 0) motorSegStart.toDouble() / duration else 0.0
        val f1 = if (duration > 0) segEnd.toDouble() / duration else 1.0
        val pts = sliceRoute(route, f0, f1)
        motorSegments.add(MotorSegment(currentMotor, pts, motorSegDur, motorSegStart, segEnd))
    }

    intervals.forEachIndexed { i, interval ->
        if (i > 0 && interval.dualMotor != currentMotor) {
            closeMotorSegment(interval.start)
            currentMotor = interval.dualMotor
            motorSegStart = interval.start
            motorSegDur = 0L
        }
        motorSegDur += interval.dur
    }
    closeMotorSegment(intervals.last().end)

    // 3. Resumo de tempo e porcentagem por modo
    val durationsByMode = LinkedHashMap<Int?, Long>()
    intervals.forEach {
        durationsByMode[it.mode] = (durationsByMode[it.mode] ?: 0L) + it.dur
    }

    // Método do maior resto: percentuais inteiros somam exatamente 100 (empate: ordem de aparição).
    val total = durationsByMode.values.sum()
    val percents = IntArray(durationsByMode.size)
    if (total > 0) {
        val durs = durationsByMode.values.toList()
        val floors = durs.map { (it * 100L / total).toInt() }
        floors.forEachIndexed { i, f -> percents[i] = f }
        val leftover = 100 - floors.sum()
        durs.indices.sortedWith(compareByDescending<Int> { durs[it] * 100L % total }.thenBy { it }).take(leftover).forEach { percents[it]++ }
    }
    val modeTimes = durationsByMode.entries.mapIndexed { i, (mode, dur) ->
        ModeTimeSummary(mode = mode, durationMs = dur, percent = percents[i])
    }

    return TripModeAnalysis(
        modeSegments = modeSegments,
        motorSegments = motorSegments,
        modeTimes = modeTimes,
    )
}

/**
 * Recorta a rota geográfica [route] entre as frações de extensão [f0] e [f1] (0.0..1.0),
 * aplicando interpolação linear nos limites (algoritmo alinhado ao FigCraft `design/s43.py`).
 */
fun sliceRoute(route: List<GeoPoint>, f0: Double, f1: Double): List<GeoPoint> {
    if (route.isEmpty()) return emptyList()
    if (route.size == 1) return route

    val cum = DoubleArray(route.size)
    for (i in 1 until route.size) {
        cum[i] = cum[i - 1] + distanceMeters(route[i - 1], route[i])
    }
    val tot = cum.last()
    if (tot <= 0.0) return route

    val startFrac = f0.coerceIn(0.0, 1.0)
    val endFrac = f1.coerceIn(startFrac, 1.0)

    fun sample(frac: Double): GeoPoint {
        val target = frac * tot
        for (i in 1 until route.size) {
            if (cum[i] >= target || i == route.size - 1) {
                val span = cum[i] - cum[i - 1]
                val u = if (span <= 0.0) 0.0 else ((target - cum[i - 1]) / span).coerceIn(0.0, 1.0)
                val p0 = route[i - 1]
                val p1 = route[i]
                return GeoPoint(p0.lat + u * (p1.lat - p0.lat), p0.lon + u * (p1.lon - p0.lon))
            }
        }
        return route.last()
    }

    val out = ArrayList<GeoPoint>()
    out.add(sample(startFrac))
    val d0 = startFrac * tot
    val d1 = endFrac * tot
    for (i in route.indices) {
        if (cum[i] > d0 && cum[i] < d1) {
            out.add(route[i])
        }
    }
    out.add(sample(endFrac))
    return out
}

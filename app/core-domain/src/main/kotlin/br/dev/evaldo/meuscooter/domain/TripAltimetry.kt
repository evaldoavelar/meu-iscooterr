package br.dev.evaldo.meuscooter.domain

/** Amostra sensível; distância acumulada no trajeto GPS, em metros (ADR 0012). */
data class AltitudeSample(val distanceM: Double, val altitudeM: Double) {
    override fun toString() = "AltitudeSample(<redigido>)"
}

/** Ganho/perda ausentes sem um par consecutivo de leituras; segmentos nunca atravessam lacunas. */
data class TripAltimetry(
    val gainM: Double?,
    val lossM: Double?,
    val minM: Double,
    val maxM: Double,
    val distanceM: Double,
    val segments: List<List<AltitudeSample>>,
) {
    override fun toString() = "TripAltimetry(<redigido>)"
}

/**
 * Histerese simples de 3 m: compara cada altitude com a última contabilizada; ao atingir o limiar,
 * soma a diferença inteira e troca a referência. Variações menores acumulam até cruzar o limiar;
 * a sobra final é ignorada. Mín./máx. e perfil usam as leituras reais, sem esse filtro.
 * Ausência/valor não finito interrompe o segmento e reinicia a referência; não integra ganho/perda
 * nem desenha linha sobre lacunas. Distância horizontal = soma haversine de TODOS os pontos da rota.
 */
fun analyzeTripAltimetry(route: List<GeoPoint>): TripAltimetry? {
    val segments = mutableListOf<MutableList<AltitudeSample>>()
    var segment: MutableList<AltitudeSample>? = null
    var anchor: Double? = null
    var previous: GeoPoint? = null
    var distance = 0.0
    var gain = 0.0
    var loss = 0.0
    var hasPair = false
    for (point in route) {
        previous?.let { distance += distanceMeters(it, point) }
        previous = point
        val altitude = point.altitudeM?.takeIf { it.isFinite() }
        if (altitude == null) {
            segment = null
            anchor = null
            continue
        }
        if (segment == null) {
            segment = mutableListOf()
            segments.add(segment)
        } else hasPair = true
        segment.add(AltitudeSample(distance, altitude))
        val delta = anchor?.let { altitude - it }
        if (delta == null || kotlin.math.abs(delta) >= 3.0) {
            if (delta != null) {
                if (delta > 0) gain += delta else loss -= delta
            }
            anchor = altitude
        }
    }
    val samples = segments.flatten()
    if (samples.isEmpty()) return null
    return TripAltimetry(
        gain.takeIf { hasPair }, loss.takeIf { hasPair }, samples.minOf { it.altitudeM }, samples.maxOf { it.altitudeM },
        distance, segments.map { it.toList() },
    )
}

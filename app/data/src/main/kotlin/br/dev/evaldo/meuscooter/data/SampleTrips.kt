package br.dev.evaldo.meuscooter.data

import br.dev.evaldo.meuscooter.domain.GeoPoint
import br.dev.evaldo.meuscooter.domain.Trip

/** Viagens SIMULADAS para o app com Fake (nenhum dado real; o GPS do celular só entra na Fase 5). */
object SampleTrips {
    private val HOUR = 3_600_000L

    // (horas atrás, km, min, média, máx, Wh/km, bateria início, bateria fim, semente do trajeto)
    private data class Spec(val agoH: Long, val km: Float, val min: Int, val avg: Float, val max: Float, val wh: Float, val b0: Int, val b1: Int, val seed: Int)

    private val specs = listOf(
        Spec(1, 4.8f, 21, 14f, 27f, 24f, 82, 76, 11), Spec(12, 3.1f, 14, 13f, 25f, 22f, 90, 86, 23),
        Spec(25, 7.9f, 32, 15f, 31f, 27f, 100, 90, 37), Spec(74, 5.2f, 24, 13f, 26f, 23f, 78, 72, 41),
        Spec(120, 12.6f, 48, 16f, 34f, 28f, 95, 79, 59),
    )

    fun build(nowMs: Long): List<Trip> = specs.mapIndexed { i, s ->
        Trip(
            id = i + 1L, startedAtMs = nowMs - s.agoH * HOUR, distanceKm = s.km, durationMs = s.min * 60_000L, avgKmh = s.avg, maxKmh = s.max,
            avgWhPerKm = s.wh, batteryStartPercent = s.b0, batteryEndPercent = s.b1, route = route(s.seed),
        )
    }

    /** Trajeto determinístico perto de São Paulo (coordenadas fictícias; os tiles do OSM mostram o local simulado, aceito pelo proprietário nesta fase). */
    private fun route(seed: Int): List<GeoPoint> {
        val r = java.util.Random(seed.toLong())
        var lat = -23.5500; var lon = -46.6300
        return List(12) {
            lat += r.nextDouble() * 0.0016 - 0.0006
            lon += r.nextDouble() * 0.0030 + 0.0004
            GeoPoint(lat, lon)
        }
    }
}

package br.dev.evaldo.meuscooter.domain

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/** Coordenada. Dado de localização SENSÍVEL (ADR 0012): `toString` não expõe lat/lon (log, exceção, falha de teste). */
data class GeoPoint(val lat: Double, val lon: Double) {
    override fun toString() = "GeoPoint(<redigido>)"
}

/** Coordenada finita e dentro da faixa geográfica. */
fun isValidCoordinate(lat: Double, lon: Double): Boolean = lat.isFinite() && lon.isFinite() && lat in -90.0..90.0 && lon in -180.0..180.0

/** Viagem salva. [startedAtMs] é relógio de parede (epoch); [route] vazia = sem trajeto (GPS do celular só na Fase 5). */
data class Trip(
    val id: Long,
    val startedAtMs: Long,
    val distanceKm: Float,
    val durationMs: Long,
    val avgKmh: Float,
    val maxKmh: Float,
    val avgWhPerKm: Float? = null,
    val batteryStartPercent: Int? = null,
    val batteryEndPercent: Int? = null,
    val route: List<GeoPoint> = emptyList(),
) {
    companion object {
        /** Id 0 = ainda não salva; o repositório atribui. */
        fun from(s: TripSummary, startedAtMs: Long) = Trip(
            0, startedAtMs, s.distanceKm, s.durationMs, s.avgKmh, s.maxKmh, s.avgWhPerKm, s.batteryStartPercent, s.batteryEndPercent,
        )
    }
}

/** Armazenamento de viagens. Implementação persistente (Room/DataStore) vem depois; só dados locais. */
interface TripRepository {
    /** Da mais recente para a mais antiga. */
    val trips: Flow<List<Trip>>
    suspend fun save(trip: Trip): Long
    suspend fun get(id: Long): Trip?
    suspend fun clear()
}

class InMemoryTripRepository(initial: List<Trip> = emptyList()) : TripRepository {
    private val all = MutableStateFlow(initial)
    private var nextId = (initial.maxOfOrNull { it.id } ?: 0L) + 1

    override val trips: Flow<List<Trip>> = all.map { list -> list.sortedByDescending { it.startedAtMs } }

    override suspend fun save(trip: Trip): Long {
        val saved = if (trip.id == 0L) trip.copy(id = nextId++) else trip
        all.value = all.value.filterNot { it.id == saved.id } + saved
        return saved.id
    }

    override suspend fun get(id: Long): Trip? = all.value.firstOrNull { it.id == id }
    override suspend fun clear() { all.value = emptyList() }
}

/** Resumo da Home do histórico: viagens dos últimos 30 dias. */
data class HistorySummary(val count: Int, val distanceKm: Float, val durationMs: Long) {
    companion object {
        const val WINDOW_MS = 30L * 86_400_000L

        fun of(trips: List<Trip>, nowMs: Long): HistorySummary {
            val recent = trips.filter { nowMs - it.startedAtMs <= WINDOW_MS }
            return HistorySummary(recent.size, recent.sumOf { it.distanceKm.toDouble() }.toFloat(), recent.sumOf { it.durationMs })
        }
    }
}

package br.dev.evaldo.meuscooter.domain

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** S1: histórico. Esperado à mão: 4,8 + 3,1 = 7,9 km; 21 + 14 = 35 min; item de 40 dias fica fora dos 30 dias. */
class TripHistoryTest {
    private val day = 86_400_000L
    private val now = 100 * day

    private fun trip(id: Long, agoDays: Double, km: Float, min: Int) = Trip(
        id = id, startedAtMs = now - (agoDays * day).toLong(), distanceKm = km, durationMs = min * 60_000L,
        avgKmh = km / (min / 60f), maxKmh = 27f,
    )

    @Test fun `lista vem da mais recente para a mais antiga`() = runTest {
        val repo = InMemoryTripRepository()
        repo.save(trip(1, 3.0, 1f, 5)); repo.save(trip(2, 0.1, 1f, 5)); repo.save(trip(3, 1.0, 1f, 5))
        assertEquals(listOf(2L, 3L, 1L), repo.trips.first().map { it.id })
    }

    @Test fun `save atribui id quando zero e get encontra`() = runTest {
        val repo = InMemoryTripRepository()
        val id = repo.save(trip(0, 0.5, 2f, 10))
        assertEquals(id, repo.get(id)!!.id)
        assertNull(repo.get(999))
    }

    @Test fun `clear apaga tudo`() = runTest {
        val repo = InMemoryTripRepository()
        repo.save(trip(1, 1.0, 1f, 5)); repo.clear()
        assertEquals(emptyList<Trip>(), repo.trips.first())
    }

    @Test fun `resumo de 30 dias soma so as viagens recentes`() {
        val s = HistorySummary.of(listOf(trip(1, 0.2, 4.8f, 21), trip(2, 1.0, 3.1f, 14), trip(3, 40.0, 10f, 60)), now)
        assertEquals(2, s.count)
        assertEquals(7.9f, s.distanceKm, 0.001f)
        assertEquals(35 * 60_000L, s.durationMs)
    }

    @Test fun `resumo vazio`() {
        val s = HistorySummary.of(emptyList(), now)
        assertEquals(0, s.count); assertEquals(0f, s.distanceKm, 0f); assertEquals(0L, s.durationMs)
    }

    @Test fun `de uma viagem zerada para Trip preserva os numeros e a bateria`() {
        val summary = TripSummary(distanceKm = 2.5f, durationMs = 760_000, avgKmh = 16f, maxKmh = 27f, batteryStartPercent = 82, batteryEndPercent = 76)
        val t = Trip.from(summary, startedAtMs = 5_000)
        assertEquals(2.5f, t.distanceKm, 0f); assertEquals(5_000L, t.startedAtMs)
        assertEquals(82, t.batteryStartPercent); assertEquals(76, t.batteryEndPercent)
        assertEquals(emptyList<GeoPoint>(), t.route)
    }
}

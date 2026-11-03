package br.dev.evaldo.meuscooter.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Viagens simuladas (só para o app com Fake). Distâncias e horários vêm de design/history.py: 4,8 · 3,1 · 7,9 · 5,2 · 12,6 km. */
class SampleTripsTest {
    private val day = 86_400_000L
    private val now = 100 * day + 20 * 3_600_000L

    @Test fun `cinco viagens com as distancias do prototipo, da mais recente para a mais antiga`() {
        val trips = SampleTrips.build(now)
        assertEquals(listOf(4.8f, 3.1f, 7.9f, 5.2f, 12.6f), trips.map { it.distanceKm })
        assertEquals(trips.sortedByDescending { it.startedAtMs }, trips)
        assertTrue(trips.all { it.startedAtMs <= now })
    }

    @Test fun `todas tem trajeto de varios pontos e bateria`() {
        SampleTrips.build(now).forEach {
            assertTrue(it.route.size >= 5)
            assertTrue(it.batteryStartPercent!! > it.batteryEndPercent!!)
        }
    }
}

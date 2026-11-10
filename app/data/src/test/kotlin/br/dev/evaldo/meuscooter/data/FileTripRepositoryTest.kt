package br.dev.evaldo.meuscooter.data

import br.dev.evaldo.meuscooter.domain.GeoPoint
import br.dev.evaldo.meuscooter.domain.Trip
import java.io.File
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FileTripRepositoryTest {
    @get:Rule val tmp = TemporaryFolder()
    private fun trip(id: Long = 0, at: Long = 1_000, km: Float = 3.5f) =
        Trip(id, at, km, 600_000, 21f, 25.5f, 12.5f, 90, 80, route = listOf(GeoPoint(-23.5, -46.6)))

    @Test fun `ausente le vazio`() = runBlocking {
        assertTrue(FileTripRepository(File(tmp.root, "trips.v1")).trips.first().isEmpty())
    }

    @Test fun `round trip sem gravar o trajeto`() = runBlocking {
        val f = File(tmp.root, "trips.v1")
        val id = FileTripRepository(f).save(trip())
        val got = FileTripRepository(f).get(id)!!
        assertEquals(trip(id).copy(route = emptyList()), got)
        assertFalse(f.readText().contains("-23.5"))
    }

    @Test fun `campos opcionais nulos sobrevivem`() = runBlocking {
        val f = File(tmp.root, "trips.v1")
        val t = trip().copy(avgWhPerKm = null, batteryStartPercent = null, batteryEndPercent = null, route = emptyList())
        val id = FileTripRepository(f).save(t)
        assertEquals(t.copy(id = id), FileTripRepository(f).get(id))
    }

    @Test fun `ids unicos e nextId correto apos reabrir`() = runBlocking {
        val f = File(tmp.root, "trips.v1")
        val a = FileTripRepository(f)
        val i1 = a.save(trip()); val i2 = a.save(trip())
        assertEquals(listOf(1L, 2L), listOf(i1, i2))
        val i3 = FileTripRepository(f).save(trip())
        assertEquals(3L, i3)
    }

    @Test fun `save com id existente substitui`() = runBlocking {
        val f = File(tmp.root, "trips.v1")
        val r = FileTripRepository(f)
        val id = r.save(trip(km = 1f))
        r.save(trip(id, km = 9f))
        assertEquals(1, FileTripRepository(f).trips.first().size)
        assertEquals(9f, FileTripRepository(f).get(id)!!.distanceKm, 0f)
    }

    @Test fun `lista da mais recente para a mais antiga`() = runBlocking {
        val r = FileTripRepository(File(tmp.root, "trips.v1"))
        r.save(trip(at = 100)); r.save(trip(at = 300)); r.save(trip(at = 200))
        assertEquals(listOf(300L, 200L, 100L), r.trips.first().map { it.startedAtMs })
    }

    @Test fun `corrompido le vazio sem crashar`() = runBlocking {
        val f = File(tmp.root, "trips.v1")
        f.writeBytes(byteArrayOf(0, 1, 2, -1, -2))
        val r = FileTripRepository(f)
        assertTrue(r.trips.first().isEmpty())
        assertEquals(1L, r.save(trip()))
    }

    @Test fun `linha invalida e ignorada e as boas ficam`() = runBlocking {
        val f = File(tmp.root, "trips.v1")
        f.writeText("TRIPS1\n5|1000|1.0|1000|1.0|2.0|-|-|-\nlixo\n6|1000|NaN|1000|1.0|2.0|-|-|-\n7|1000|1.0|1000|1.0|Infinity|-|-|-\n5|2000|9.0|1000|1.0|2.0|-|-|-\n")
        val r = FileTripRepository(f)
        assertEquals(listOf(5L), r.trips.first().map { it.id })
        assertEquals(6L, r.save(trip()))
    }

    @Test fun `valores nao finitos nao sao salvos`() = runBlocking {
        val f = File(tmp.root, "trips.v1")
        val r = FileTripRepository(f)
        assertEquals(0L, r.save(trip().copy(distanceKm = Float.NaN)))
        assertEquals(0L, r.save(trip().copy(maxKmh = Float.POSITIVE_INFINITY)))
        assertEquals(0L, r.save(trip().copy(avgWhPerKm = Float.NaN)))
        assertTrue(r.trips.first().isEmpty())
    }

    @Test fun `clear apaga memoria e arquivo`() = runBlocking {
        val f = File(tmp.root, "trips.v1")
        val r = FileTripRepository(f)
        r.save(trip()); r.clear()
        assertTrue(r.trips.first().isEmpty())
        assertTrue(FileTripRepository(f).trips.first().isEmpty())
        assertNull(r.get(1))
        assertEquals(1L, r.save(trip()))
    }

    @Test fun `saves concorrentes geram ids distintos e todos persistem`() = runBlocking {
        val f = File(tmp.root, "trips.v1")
        val r = FileTripRepository(f)
        val ids = (1..50).map { async(kotlinx.coroutines.Dispatchers.Default) { r.save(trip(at = it.toLong())) } }.awaitAll()
        assertEquals(50, ids.toSet().size)
        assertEquals(50, FileTripRepository(f).trips.first().size)
    }

    @Test fun `diretorio inexistente e criado e falha de escrita nao crasha`() = runBlocking {
        val r = FileTripRepository(File(tmp.root, "sub/dir/trips.v1"))
        assertEquals(1L, r.save(trip()))
        val bad = FileTripRepository(File(tmp.root, "trips.v1").also { it.mkdirs() }) // é diretório: escrita falha
        assertEquals(1L, bad.save(trip()))
        assertEquals(1, bad.trips.first().size)
    }
}

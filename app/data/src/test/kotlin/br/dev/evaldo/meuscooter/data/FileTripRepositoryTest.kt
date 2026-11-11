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
        assertFalse(f.readText().contains("-23.5") || f.readText().contains("-46.6"))
    }

    @Test fun `round trip com distancia zero e media zero salva e recupera`() = runBlocking {
        val f = File(tmp.root, "trips.v1")
        val zeroTrip = Trip(
            id = 0,
            startedAtMs = 1_000,
            distanceKm = 0f,
            durationMs = 60_000,
            avgKmh = 0f,
            maxKmh = 15f,
            avgWhPerKm = null,
            batteryStartPercent = 85,
            batteryEndPercent = 82,
            route = emptyList(),
        )
        val id = FileTripRepository(f).save(zeroTrip)
        val got = FileTripRepository(f).get(id)!!
        assertEquals(zeroTrip.copy(id = id), got)
        assertEquals(0f, got.distanceKm, 0.0001f)
        assertEquals(0f, got.avgKmh, 0.0001f)
        assertEquals(15f, got.maxKmh, 0.0001f)
        assertNull(got.avgWhPerKm)
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
        f.writeText("TRIPS1|1\n5|1000|1.0|1000|1.0|2.0|-|-|-\nlixo\n6|1000|NaN|1000|1.0|2.0|-|-|-\n7|1000|1.0|1000|1.0|Infinity|-|-|-\n5|2000|9.0|1000|1.0|2.0|-|-|-\n")
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
        assertEquals(2L, r.save(trip()))                       // contador monotônico: não reutiliza id após clear
    }

    @Test fun `delete apaga apenas a escolhida e persiste ao reabrir`() = runBlocking {
        val f = File(tmp.root, "trips.v1")
        val r = FileTripRepository(f)
        r.save(trip(at = 100))
        val id2 = r.save(trip(at = 200))
        r.save(trip(at = 300))
        r.delete(id2)
        assertEquals(listOf(300L, 100L), r.trips.first().map { it.startedAtMs })
        assertNull(r.get(id2))
        // Persistência ao reabrir
        val reloaded = FileTripRepository(f)
        assertEquals(listOf(300L, 100L), reloaded.trips.first().map { it.startedAtMs })
        assertNull(reloaded.get(id2))
    }

    @Test fun `delete nao reutiliza id mesmo apos deletar o ultimo e reabrir`() = runBlocking {
        val f = File(tmp.root, "trips.v1")
        val r = FileTripRepository(f)
        r.save(trip(at = 100))
        val id2 = r.save(trip(at = 200))
        r.delete(id2)
        val id3 = r.save(trip(at = 300))
        assertEquals(3L, id3)
        // Ao reabrir, nextId continua monotônico
        val reloaded = FileTripRepository(f)
        val id4 = reloaded.save(trip(at = 400))
        assertEquals(4L, id4)
    }

    @Test fun `delete de viagem inexistente nao quebra e mantem existentes`() = runBlocking {
        val f = File(tmp.root, "trips.v1")
        val r = FileTripRepository(f)
        val id1 = r.save(trip())
        r.delete(999L)
        assertEquals(1, r.trips.first().size)
        assertEquals(id1, r.trips.first().first().id)
    }

    @Test fun `contador sobrevive a clear e reabrir`() = runBlocking {
        val f = File(tmp.root, "trips.v1")
        val r = FileTripRepository(f)
        r.save(trip()); r.save(trip()); r.clear()
        assertEquals(3L, FileTripRepository(f).save(trip()))
    }

    @Test fun `clear seguido de save nao herda trajeto orfao do FileRouteStore`() = runBlocking {
        val routes = FileRouteStore(tmp.newFolder("routes"))
        val r = FileTripRepository(File(tmp.root, "trips.v1"))
        val old = r.save(trip()); routes.write(old, listOf(GeoPoint(-23.5, -46.6)))
        routes.clear(); r.clear()
        val fresh = r.save(trip())
        assertTrue(routes.read(fresh).isEmpty())
        assertTrue(fresh != old)
    }

    @Test fun `valores fora da faixa sao rejeitados`() = runBlocking {
        val f = File(tmp.root, "trips.v1")
        val r = FileTripRepository(f)
        assertEquals(0L, r.save(trip().copy(durationMs = -1)))
        assertEquals(0L, r.save(trip().copy(batteryStartPercent = 101)))
        assertEquals(0L, r.save(trip().copy(batteryEndPercent = -1)))
        assertEquals(0L, r.save(trip().copy(startedAtMs = -5)))
        f.writeText("TRIPS1|1\n1|-1|1.0|1000|1.0|2.0|-|-|-\n2|1|1.0|-5|1.0|2.0|-|-|-\n3|1|1.0|5|1.0|2.0|-|150|-\n")
        assertTrue(FileTripRepository(f).trips.first().isEmpty())
    }

    @Test fun `tmp orfao e removido ao abrir`() = runBlocking {
        val f = File(tmp.root, "trips.v1"); val t = File(tmp.root, "trips.v1.tmp")
        t.writeText("lixo")
        FileTripRepository(f).trips.first()   // a leitura é adiada até o primeiro uso
        assertFalse(t.exists())
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

    @Test fun `formato legado TRIPS1 sem contador le as viagens e o proximo id passa do maior`() = runBlocking {
        val f = File(tmp.root, "trips.v1")
        f.writeText("TRIPS1\n7|1000|3.5|600000|21.0|25.5|12.5|90|80\n3|2000|1.0|60000|10.0|12.0|-|-|-\n")
        val repo = FileTripRepository(f)
        assertEquals(listOf(3L, 7L), repo.trips.first().map { it.id }.sorted())
        assertEquals(8L, repo.save(trip()))
        assertEquals(9L, FileTripRepository(f).save(trip()))   // regravado já no formato novo, contador preservado
    }

    @Test fun `migracao retrocompativel de arquivo legado TRIPS1 com 9 colunas le sem eventos e regrava em TRIPS2`() = runBlocking {
        val f = File(tmp.root, "trips.v1")
        f.writeText("TRIPS1|10\n1|1000|3.5|600000|21.0|25.5|12.5|90|80\n2|2000|1.0|60000|10.0|12.0|-|-|-\n")
        val repo = FileTripRepository(f)
        val initial = repo.trips.first()
        assertEquals(2, initial.size)
        assertTrue(initial.all { it.modeEvents.isEmpty() })

        val newTrip = trip(
            id = 0,
            at = 3_000,
        ).copy(
            modeEvents = listOf(
                br.dev.evaldo.meuscooter.domain.TripModeEvent(0L, 0, false),
                br.dev.evaldo.meuscooter.domain.TripModeEvent(4_000L, 1, true),
            ),
        )
        val id3 = repo.save(newTrip)
        assertEquals(10L, id3)

        val text = f.readText()
        assertTrue(text.startsWith("TRIPS2|11\n"))
        assertTrue(text.contains("0:0:0,4000:1:1"))

        val reloaded = FileTripRepository(f)
        val trips = reloaded.trips.first()
        val t1 = trips.first { it.id == 1L }
        val t3 = trips.first { it.id == 10L }
        assertTrue(t1.modeEvents.isEmpty())
        assertEquals(2, t3.modeEvents.size)
        assertEquals(br.dev.evaldo.meuscooter.domain.TripModeEvent(0L, 0, false), t3.modeEvents[0])
        assertEquals(br.dev.evaldo.meuscooter.domain.TripModeEvent(4_000L, 1, true), t3.modeEvents[1])
    }

    @Test fun `eventos com valores nulos sobrevivem a persistencia`() = runBlocking {
        val f = File(tmp.root, "trips.v1")
        val repo = FileTripRepository(f)
        val t = trip().copy(
            modeEvents = listOf(
                br.dev.evaldo.meuscooter.domain.TripModeEvent(0L, null, null),
                br.dev.evaldo.meuscooter.domain.TripModeEvent(2_000L, 2, null),
            ),
        )
        val id = repo.save(t)
        val got = FileTripRepository(f).get(id)!!
        assertEquals(2, got.modeEvents.size)
        assertEquals(br.dev.evaldo.meuscooter.domain.TripModeEvent(0L, null, null), got.modeEvents[0])
        assertEquals(br.dev.evaldo.meuscooter.domain.TripModeEvent(2_000L, 2, null), got.modeEvents[1])
    }
}

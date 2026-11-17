package br.dev.evaldo.meuscooter.data

import br.dev.evaldo.meuscooter.domain.GeoPoint
import br.dev.evaldo.meuscooter.domain.backup.BackupContents
import java.io.File
import java.io.IOException
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BackupFilesTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var root: File
    private lateinit var trips: File
    private lateinit var routes: File
    private val oldTrips = "TRIPS2|2\n1|1700000000000|1.5|600000|15.0|25.0|-|80|70|-\n"
    private val newTrips = "TRIPS1\n7|1700000000000|3.0|600000|15.0|25.0|-|-|-\n8|1700100000000|1.0|60000|5.0|9.0|-|-|-\n"

    @Before fun setUp() {
        root = tmp.newFolder("files"); trips = File(root, "trips.v1"); routes = File(root, "routes")
        trips.writeText(oldTrips)
        FileRouteStore(routes).write(1, listOf(GeoPoint(-23.55, -46.63), GeoPoint(-23.56, -46.64)))
    }

    private fun routeBytes(tripId: Long, lat: Double): ByteArray {
        val d = tmp.newFolder(); FileRouteStore(d).write(tripId, listOf(GeoPoint(lat, -46.0), GeoPoint(lat + 0.01, -46.01)))
        return File(d, "$tripId.route").readBytes()
    }
    private fun valid() = BackupContents(mapOf("theme" to "Dark", "x" to "y"), newTrips.toByteArray(), mapOf("7" to routeBytes(7, -10.0)), null)
    private fun files(move: (File, File) -> Unit = { a, b -> java.nio.file.Files.move(a.toPath(), b.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE) }) = BackupFiles(trips, routes, move)
    private fun assertUntouched() {
        assertEquals(oldTrips, trips.readText())
        assertEquals(listOf("1.route"), routes.list()!!.sorted())
        assertEquals(2, FileRouteStore(routes).read(1).size)
        assertEquals(setOf("trips.v1", "routes"), root.list()!!.toSet())
    }

    @Test fun `snapshot le trips e rotas atuais`() {
        val s = files().snapshot(mapOf("a" to "b"), "1".toCharArray())
        assertEquals(oldTrips, String(s.tripsFile!!)); assertEquals(setOf("1"), s.routes.keys); assertEquals(mapOf("a" to "b"), s.preferences)
    }

    @Test fun `aplicar valido troca viagens e rotas e saneia preferencias`() {
        val r = files().apply(valid()) as BackupFiles.ApplyResult.Applied
        assertEquals(newTrips, trips.readText()); assertEquals(listOf("7.route"), routes.list()!!.sorted())
        assertEquals(2, FileRouteStore(routes).read(7).size)
        assertEquals(2, r.tripCount); assertEquals(1, r.routeCount)
        assertEquals("Dark", r.preferences["theme"]); assertFalse("x" in r.preferences)
        assertEquals(setOf("trips.v1", "routes"), root.list()!!.toSet())
        // o repositório real lê o resultado (TRIPS1 legado migra)
        assertEquals(setOf(7L, 8L), kotlinx.coroutines.runBlocking { FileTripRepository(trips).trips.first() }.map { it.id }.toSet())
    }

    @Test fun `aplicar sem viagens no backup remove o historico`() {
        files().apply(BackupContents(emptyMap(), null, emptyMap(), null))
        assertFalse(trips.exists()); assertEquals(0, routes.list()!!.size)
    }

    @Test fun `trips invalido nao altera nada`() {
        for (bad in listOf("LIXO\n1|2\n", "TRIPS2|3\nnao-e-viagem\n", "TRIPS2|3\n${newTrips.lines()[1]}\n${newTrips.lines()[1]}\n", "")) {
            assertEquals(bad, BackupFiles.ApplyResult.Invalid, files().apply(BackupContents(emptyMap(), bad.toByteArray(), emptyMap(), null)))
            assertUntouched()
        }
        assertEquals(BackupFiles.ApplyResult.Invalid, files().apply(BackupContents(emptyMap(), byteArrayOf(0xC3.toByte(), 0x28), emptyMap(), null)))
        assertUntouched()
    }

    @Test fun `rota invalida ou id invalido nao altera nada`() {
        val badRoute = BackupContents(emptyMap(), newTrips.toByteArray(), mapOf("7" to byteArrayOf(1, 2, 3)), null)
        assertEquals(BackupFiles.ApplyResult.Invalid, files().apply(badRoute)); assertUntouched()
        val truncated = routeBytes(7, 5.0).let { it.copyOf(it.size - 3) }
        assertEquals(BackupFiles.ApplyResult.Invalid, files().apply(BackupContents(emptyMap(), null, mapOf("7" to truncated), null))); assertUntouched()
        for (id in listOf("abc", "0", "-1")) assertEquals(id, BackupFiles.ApplyResult.Invalid, files().apply(BackupContents(emptyMap(), null, mapOf(id to routeBytes(7, 1.0)), null)))
        assertUntouched()
    }

    @Test fun `falha em qualquer etapa da troca mantem o estado anterior`() {
        for (failAt in 1..4) {
            var n = 0
            val f = files { a, b -> if (++n == failAt) throw IOException("falha simulada") else java.nio.file.Files.move(a.toPath(), b.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE) }
            assertEquals("etapa $failAt", BackupFiles.ApplyResult.IoFailure, f.apply(valid()))
            assertUntouched()
        }
    }

    @Test fun `validate nao altera nada`() {
        assertEquals(2 to 1, files().validate(valid())); assertUntouched()
        assertNull(files().validate(BackupContents(emptyMap(), null, mapOf("7" to byteArrayOf(1)), null))); assertUntouched()
    }

    @Test fun `sobra de execucao interrompida e restaurada`() {
        File(routes.path).renameTo(File(routes.path + ".bkold"))
        File(trips.path).renameTo(File(trips.path + ".bkold"))
        files().apply(valid())
        assertEquals(newTrips, trips.readText())
        assertFalse(File(routes.path + ".bkold").exists())
    }

    @Test fun `bytes das rotas aplicadas sao iguais aos do backup`() {
        val rb = routeBytes(7, -10.0)
        files().apply(BackupContents(emptyMap(), null, mapOf("7" to rb), null))
        assertArrayEquals(rb, File(routes, "7.route").readBytes())
    }
}

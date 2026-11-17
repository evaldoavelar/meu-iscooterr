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

    private class Death : Error()
    private val realMove = { a: File, b: File -> java.nio.file.Files.move(a.toPath(), b.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE); Unit }

    /** Morte do processo logo depois do [after]-ésimo move (0 = antes de qualquer troca, com o marcador já criado). */
    private fun dieAfter(after: Int, c: BackupContents = valid()) {
        var n = 0
        val f = BackupFiles(trips, routes, { a, b -> if (n == after) throw Death(); realMove(a, b); n++; if (n == after && after == 4) throw Death() })
        // after = 0..3: morre ANTES do move (after+1); after = 4: morre depois do 4º move (troca completa, sem limpeza).
        try { f.apply(c); org.junit.Assert.fail("deveria morrer") } catch (_: Death) { }
    }
    private fun isOld() = trips.isFile && routes.isDirectory && trips.readText() == oldTrips && routes.list()!!.sorted() == listOf("1.route")
    private fun isNew() = trips.isFile && routes.isDirectory && trips.readText() == newTrips && routes.list()!!.sorted() == listOf("7.route")

    @Test fun `morte do processo em cada ponto e recover deixa tudo antigo ou tudo novo`() {
        for (after in 0..4) {
            setUp2()
            dieAfter(after)
            files().recover()
            if (after == 4) assertTrue("etapa $after", isNew()) else assertTrue("etapa $after", isOld())
            assertEquals("etapa $after", setOf("trips.v1", "routes"), root.list()!!.toSet())
            // idempotente e não apaga histórico válido (nem uma viagem nova gravada depois)
            val before = trips.readText()
            files().recover(); files().recover()
            assertEquals(before, trips.readText())
            trips.appendText("9|1700200000000|1.0|60000|5.0|9.0|-|-|-|-\n")
            val withNew = trips.readText()
            files().recover()
            assertEquals(withNew, trips.readText())
            assertEquals(setOf("trips.v1", "routes"), root.list()!!.toSet())
        }
    }

    private fun setUp2() { tmp.root.listFiles()!!.forEach { it.deleteRecursively() }; setUp() }

    @Test fun `morte durante a limpeza final completa e nunca apaga so um lado`() {
        dieAfter(4)
        File(routes.path + ".bkold").deleteRecursively() // limpeza parcial: sobrou só o .bkold das viagens
        files().recover()
        assertTrue(isNew()); assertEquals(setOf("trips.v1", "routes"), root.list()!!.toSet())
    }

    @Test fun `sem viagens anteriores a morte tambem volta ao estado antigo`() {
        trips.delete()
        dieAfter(2) // moves: rotas→.bkold, stage→routes, [morre antes de] novo trips
        files().recover()
        assertFalse(trips.exists()); assertEquals(listOf("1.route"), routes.list()!!.sorted())
    }

    @Test fun `sem marcador stage orfao e descartado e estado integro mantido`() {
        File(routes.path + ".bkstage").mkdirs(); File(trips.path + ".bkstage").writeText("x")
        files().recover()
        assertUntouched()
    }

    @Test fun `sem marcador bkold orfao so e descartado com estado integro`() {
        File(trips.path + ".bkold").writeText("copia")
        File(trips.path).writeText("lixo") // estado atual inválido: mantém o .bkold
        files().recover()
        assertTrue(File(trips.path + ".bkold").exists())
        trips.writeText(oldTrips) // íntegro: descarta
        files().recover()
        assertFalse(File(trips.path + ".bkold").exists()); assertUntouched()
    }

    @Test fun `bkold orfao sem o lado atual e restaurado`() {
        trips.renameTo(File(trips.path + ".bkold")); routes.renameTo(File(routes.path + ".bkold"))
        assertFalse(trips.exists()) // sem recuperação o histórico estaria sumido
        files().recover()
        assertUntouched()
    }

    @Test fun `falha do rollback deixa marcador e recover posterior restaura`() {
        var n = 0
        val f = BackupFiles(trips, routes, { a, b -> if (++n == 3) throw IOException("x") else realMove(a, b) }, { _, _ -> throw IOException("rollback") })
        assertEquals(BackupFiles.ApplyResult.IoFailure, f.apply(valid()))
        assertTrue(File(trips.path + ".bkcommit").exists())
        files().recover()
        assertUntouched()
    }

    @Test fun `apply recupera sobra pendente antes de aplicar`() {
        dieAfter(2)
        assertTrue(files().apply(valid()) is BackupFiles.ApplyResult.Applied)
        assertTrue(isNew()); assertEquals(setOf("trips.v1", "routes"), root.list()!!.toSet())
    }

    @Test fun `validate com trips invalido devolve null e id nao canonico e rejeitado`() {
        assertNull(files().validate(BackupContents(emptyMap(), "LIXO\n".toByteArray(), emptyMap(), null)))
        assertNull(files().validate(BackupContents(emptyMap(), null, mapOf("007" to routeBytes(7, 1.0)), null)))
        assertEquals(BackupFiles.ApplyResult.Invalid, files().apply(BackupContents(emptyMap(), null, mapOf("007" to routeBytes(7, 1.0)), null)))
        assertUntouched()
    }

    @Test fun `bytes das rotas aplicadas sao iguais aos do backup`() {
        val rb = routeBytes(7, -10.0)
        files().apply(BackupContents(emptyMap(), null, mapOf("7" to rb), null))
        assertArrayEquals(rb, File(routes, "7.route").readBytes())
    }
}

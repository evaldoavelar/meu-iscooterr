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
    private fun deathMover(afterMoves: Int): (File, File) -> Unit { var n = 0; return { a, b -> if (afterMoves == 0) throw Death(); realMove(a, b); if (++n == afterMoves) throw Death() } }

    /** Morte do processo logo depois do [after]-ésimo move da troca (0 = depois do marcador, antes de qualquer move). */
    private fun dieAfter(after: Int, c: BackupContents = valid()) {
        try { BackupFiles(trips, routes, deathMover(after)).apply(c); org.junit.Assert.fail("deveria morrer") } catch (_: Death) { }
    }
    /** Conteúdo byte a byte de tudo sob [root]. */
    private fun state(): Map<String, List<Byte>?> = root.walkTopDown().filter { it != root }.associate { it.relativeTo(root).path to (if (it.isFile) it.readBytes().toList() else null) }
    private fun isOld() = trips.isFile && routes.isDirectory && trips.readText() == oldTrips && routes.list()!!.sorted() == listOf("1.route")
    private fun isNew() = trips.isFile && routes.isDirectory && trips.readText() == newTrips && routes.list()!!.sorted() == listOf("7.route")
    private fun reset(prepare: () -> Unit) { tmp.root.listFiles()!!.forEach { it.deleteRecursively() }; setUp(); prepare() }

    private class Scenario(val name: String, val prepare: () -> Unit, val contents: () -> BackupContents, val moves: Int)

    @Test fun `morte do processo em cada ponto e recover deixa tudo antigo ou tudo novo byte a byte`() {
        val scenarios = listOf(
            Scenario("completo", {}, ::valid, 4),
            Scenario("backup sem trips (newTrips=0)", {}, { BackupContents(emptyMap(), null, mapOf("7" to routeBytes(7, -10.0)), null) }, 3),
            Scenario("sem rotas anteriores", { routes.deleteRecursively() }, ::valid, 3),
            Scenario("sem viagens anteriores", { trips.delete() }, ::valid, 3),
        )
        for (sc in scenarios) {
            // referência independente: estado "novo" = apply completo; "antigo" = antes de aplicar
            reset(sc.prepare); val oldState = state()
            val c = sc.contents(); assertTrue(files().apply(c) is BackupFiles.ApplyResult.Applied); val newState = state()
            for (after in 0..sc.moves) {
                reset(sc.prepare); dieAfter(after, c)
                files().recover()
                val expected = if (after == sc.moves) newState else oldState
                assertEquals("${sc.name} etapa $after", expected, state())
                // idempotente e não apaga histórico válido (nem uma viagem nova gravada depois)
                files().recover(); files().recover()
                assertEquals("${sc.name} etapa $after", expected, state())
                if (trips.exists()) {
                    trips.appendText("9|1700200000000|1.0|60000|5.0|9.0|-|-|-|-\n")
                    val withNew = state(); files().recover(); assertEquals(withNew, state())
                }
            }
        }
    }

    @Test fun `morte durante o proprio recover em cada move do rollback e recover seguinte restaura tudo antigo`() {
        val scenarios = listOf<Pair<String, () -> Unit>>("completo" to {}, "sem rotas anteriores" to { routes.deleteRecursively() }, "sem viagens anteriores" to { trips.delete() })
        for ((name, prep) in scenarios) {
            reset(prep); val oldState = state()
            for (forward in 1..2) for (k in 1..5) {
                reset(prep); dieAfter(forward)
                var died = false
                try { BackupFiles(trips, routes, rollbackMove = deathMover(k)).recover() } catch (_: Death) { died = true }
                if (died) files().recover()
                assertEquals("$name forward=$forward rollbackMove=$k", oldState, state())
                files().recover()
                assertEquals("$name forward=$forward rollbackMove=$k (2)", oldState, state())
                if (!died) break
            }
        }
        // rollback depois do move 3 (rotas novas já no lugar, viagens novas ainda no stage)
        reset {}; val oldState = state()
        for (k in 1..5) {
            reset {}; dieAfter(3)
            var died = false
            try { BackupFiles(trips, routes, rollbackMove = deathMover(k)).recover() } catch (_: Death) { died = true }
            if (died) files().recover()
            assertEquals("forward=3 rollbackMove=$k", oldState, state())
            if (!died) break
        }
    }

    @Test fun `marcador ilegivel vazio ou corrompido e tratado sem derrubar`() {
        for (garbage in listOf("", "lixo", "newTrips=2", "newTrips=1\u0000")) {
            reset {}; val oldState = state()
            dieAfter(2)
            File(trips.path + ".bkcommit").writeText(garbage)
            files().recover()
            assertEquals(garbage, oldState, state())
            // morte depois do move 4 com marcador corrompido: conclui (viagens novas já no lugar e stage consumido)
            reset {}; dieAfter(4); File(trips.path + ".bkcommit").writeText(garbage)
            files().recover()
            assertTrue(garbage, isNew()); assertEquals(setOf("trips.v1", "routes"), root.list()!!.toSet())
        }
        // marcador como diretório: leitura falha, recover não lança
        reset {}; val oldState0 = state(); dieAfter(1); File(trips.path + ".bkcommit").delete(); File(trips.path + ".bkcommit").mkdir()
        files().recover()
        assertEquals(oldState0, state())
    }

    @Test fun `mortes encadeadas no recover (k1 e k2) e depois recover limpo restauram tudo antigo`() {
        reset {}; val oldState = state()
        for (forward in 1..3) for (k1 in 1..4) for (k2 in 1..4) {
            reset {}; dieAfter(forward)
            try { BackupFiles(trips, routes, rollbackMove = deathMover(k1)).recover() } catch (_: Death) { }
            try { BackupFiles(trips, routes, rollbackMove = deathMover(k2)).recover() } catch (_: Death) { }
            files().recover()
            assertEquals("forward=$forward k1=$k1 k2=$k2", oldState, state())
            files().recover()
            assertEquals("forward=$forward k1=$k1 k2=$k2 (2)", oldState, state())
        }
    }

    @Test fun `apply devolve IoFailure e preserva o bkold orfao de viagens quando o trips atual esta corrompido`() {
        File(trips.path + ".bkold").writeText(oldTrips); trips.writeText("lixo")
        val before = state()
        assertEquals(BackupFiles.ApplyResult.IoFailure, files().apply(valid()))
        assertEquals(before, state())
        assertEquals(oldTrips, File(trips.path + ".bkold").readText())
    }

    @Test fun `routes vazio nao e integro se o bkold tem rotas, e apply devolve IoFailure`() {
        File(routes.path + ".bkold").mkdirs(); File(routes.path + ".bkold", "5.route").writeBytes(routeBytes(5, 1.0))
        File(routes, "1.route").delete() // routes/ existe e está vazio
        val before = state()
        files().recover()
        assertEquals(before, state())
        assertEquals(BackupFiles.ApplyResult.IoFailure, files().apply(valid()))
        assertEquals(before, state())
    }

    @Test fun `ramo trips novo para bknew nao move o trips quando o stage de rotas ainda existe`() {
        // marcador + stage ainda presente: rollback não pode tirar o trips atual do lugar achando que é o "novo"
        reset {}; val oldState = state()
        dieAfter(0)
        files().recover()
        assertEquals(oldState, state())
        assertTrue(isOld())
    }

    @Test fun `recoverQuietly devolve true quando recupera e false sem lancar quando trips e ilegivel`() {
        dieAfter(2)
        assertTrue(files().recoverQuietly()); assertTrue(isOld())
        reset {}
        File(trips.path + ".bkold").writeText(oldTrips); trips.delete(); trips.mkdir(); File(trips, "x").writeText("x") // trips.v1 é um diretório
        assertFalse(files().recoverQuietly())
    }

    @Test fun `bkold de rotas orfao nao e apagado se as rotas atuais estao corrompidas`() {
        File(routes.path + ".bkold").mkdirs(); File(routes.path + ".bkold", "5.route").writeBytes(byteArrayOf(1))
        File(routes, "1.route").writeBytes(byteArrayOf(9, 9))
        files().recover()
        assertTrue(File(routes.path + ".bkold").exists())
    }

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

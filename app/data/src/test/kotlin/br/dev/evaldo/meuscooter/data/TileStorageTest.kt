package br.dev.evaldo.meuscooter.data

import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.net.InetSocketAddress
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Disco em pasta temporária; HTTP só em loopback (127.0.0.1), nunca na internet. */
class TileStorageTest {
    @get:Rule val tmp = TemporaryFolder()
    private var server: HttpServer? = null
    @After fun stop() { server?.stop(0) }

    @Test fun `disco grava, le, lista e apaga`() {
        val s = DiskTileCacheStore(tmp.newFolder("tiles"))
        assertNull(s.read("1_0_0"))
        s.write("1_0_0", byteArrayOf(1, 2, 3), nowMs = 5_000)
        val r = s.read("1_0_0")!!
        assertArrayEquals(byteArrayOf(1, 2, 3), r.bytes); assertEquals(5_000L, r.storedAtMs)
        val e = s.entries().single()
        assertEquals("1_0_0", e.key); assertEquals(3L, e.bytes); assertEquals(5_000L, e.storedAtMs)
        s.delete("1_0_0")
        assertNull(s.read("1_0_0")); assertTrue(s.entries().isEmpty())
    }

    @Test fun `touch muda so o ultimo acesso e nao a data de gravacao`() {
        val s = DiskTileCacheStore(tmp.newFolder("tiles"))
        s.write("a", byteArrayOf(1), nowMs = 1_000)
        s.touch("a", nowMs = 9_000)
        val e = s.entries().single()
        assertEquals(1_000L, e.storedAtMs); assertEquals(9_000L, e.lastAccessMs)
        assertEquals(1_000L, s.read("a")!!.storedAtMs)
    }

    @Test fun `arquivo corrompido vira miss`() {
        val dir = tmp.newFolder("tiles")
        java.io.File(dir, "x.tile").writeBytes(byteArrayOf(1, 2))
        assertNull(DiskTileCacheStore(dir).read("x"))
    }

    private fun serve(status: Int, body: ByteArray, seenUa: MutableList<String?> = mutableListOf()): String {
        val srv = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        srv.createContext("/t.png") { ex ->
            seenUa += ex.requestHeaders.getFirst("User-Agent")
            ex.sendResponseHeaders(status, body.size.toLong()); ex.responseBody.use { it.write(body) }
        }
        srv.start(); server = srv
        return "http://127.0.0.1:${srv.address.port}/t.png"
    }

    @Test fun `http devolve o corpo e envia o User-Agent`() = runTest {
        val ua = mutableListOf<String?>()
        val url = serve(200, byteArrayOf(7, 8), ua)
        assertArrayEquals(byteArrayOf(7, 8), HttpTileNetwork().fetch(url, "Meu iScooter/1 (uso pessoal)"))
        assertEquals(listOf<String?>("Meu iScooter/1 (uso pessoal)"), ua)
    }

    @Test fun `http com status diferente de 200 lanca IOException`() = runTest {
        val url = serve(404, byteArrayOf())
        try { HttpTileNetwork().fetch(url, "ua"); fail() } catch (_: IOException) { }
    }

    @Test fun `http sem servidor lanca IOException`() = runTest {
        val url = serve(200, byteArrayOf()); server!!.stop(0)
        try { HttpTileNetwork().fetch(url, "ua"); fail() } catch (_: IOException) { }
    }

    @Test fun `http recusa corpo maior que o limite`() = runTest {
        val url = serve(200, ByteArray(2_000))
        try { HttpTileNetwork(maxBytes = 1_000).fetch(url, "ua"); fail() } catch (_: IOException) { }
    }
}

package br.dev.evaldo.meuscooter.domain

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** S1/S2 com rede e disco falsos: nenhuma rede real. Datas: 1 dia = 86_400_000 ms (política de 30 dias, decisão do proprietário). */
@OptIn(ExperimentalCoroutinesApi::class)
class TileLoaderTest {
    private val day = 86_400_000L
    private val tile = TileId(10, 512, 340)

    private class FakeNetwork : TileNetwork {
        val calls = mutableListOf<Pair<String, String>>()
        var fail = false
        var delayMs = 0L
        var inFlight = 0
        var maxInFlight = 0
        var bytes = byteArrayOf(1, 2, 3)
        override suspend fun fetch(url: String, userAgent: String): ByteArray {
            calls += url to userAgent
            inFlight++; maxInFlight = maxOf(maxInFlight, inFlight)
            try {
                if (delayMs > 0) delay(delayMs)
                if (fail) throw IOException("sem rede")
                return bytes
            } finally { inFlight-- }
        }
    }

    private class FakeStore : TileCacheStore {
        val data = mutableMapOf<String, Pair<ByteArray, Long>>()
        val access = mutableMapOf<String, Long>()
        override fun read(key: String) = data[key]?.let { StoredTile(it.first, it.second) }
        override fun write(key: String, bytes: ByteArray, nowMs: Long) { data[key] = bytes to nowMs; access[key] = nowMs }
        override fun touch(key: String, nowMs: Long) { access[key] = nowMs }
        override fun entries() = data.map { (k, v) -> CacheEntry(k, v.first.size.toLong(), v.second, access[k] ?: v.second) }
        override fun delete(key: String) { data.remove(key); access.remove(key) }
    }

    private val net = FakeNetwork()
    private val store = FakeStore()
    private var now = 100 * day

    private fun TestScope.loader(policy: TileCachePolicy = TileCachePolicy(), maxConcurrent: Int = 2) =
        CachingTileLoader(OsmTileSource("0.2.0"), net, store, policy, { now }, backgroundScope, maxConcurrent)

    @Test fun `miss baixa com URL e User-Agent da fonte e grava no cache`() = runTest {
        val r = loader().load(tile)
        r as TileResult.Loaded
        assertEquals(listOf<Byte>(1, 2, 3), r.bytes.toList()); assertFalse(r.stale)
        assertEquals(listOf("https://tile.openstreetmap.org/10/512/340.png" to "Meu iScooter/0.2.0 (uso pessoal)"), net.calls)
        assertEquals(1, store.data.size)
    }

    @Test fun `tile fresco vem do cache sem tocar na rede`() = runTest {
        store.write("10_512_340", byteArrayOf(9), now - 29 * day)
        val r = loader().load(tile) as TileResult.Loaded
        assertEquals(listOf<Byte>(9), r.bytes.toList()); assertFalse(r.stale)
        assertTrue(net.calls.isEmpty())
        assertEquals(now, store.access["10_512_340"])
    }

    @Test fun `tile expirado e rebaixado quando ha rede`() = runTest {
        store.write("10_512_340", byteArrayOf(9), now - 31 * day)
        val r = loader().load(tile) as TileResult.Loaded
        assertEquals(listOf<Byte>(1, 2, 3), r.bytes.toList()); assertFalse(r.stale)
        assertEquals(1, net.calls.size)
    }

    @Test fun `tile expirado so e usado se a rede falhar e vem marcado como obsoleto`() = runTest {
        store.write("10_512_340", byteArrayOf(9), now - 31 * day)
        net.fail = true
        val r = loader().load(tile) as TileResult.Loaded
        assertEquals(listOf<Byte>(9), r.bytes.toList()); assertTrue(r.stale)
    }

    @Test fun `sem cache e sem rede o resultado e indisponivel e nao lanca`() = runTest {
        net.fail = true
        assertEquals(TileResult.Unavailable, loader().load(tile))
    }

    @Test fun `cancelamento nao vira indisponivel`() = runTest {
        net.delayMs = 1_000
        val l = loader()
        val job = async { l.load(tile) }
        delay(10); job.cancel()
        try { job.await(); fail() } catch (_: CancellationException) { }
    }

    @Test fun `mesmo tile em voo e pedido uma unica vez`() = runTest {
        net.delayMs = 100
        val l = loader()
        val rs = List(5) { async { l.load(tile) } }.awaitAll()
        assertEquals(1, net.calls.size)
        assertTrue(rs.all { it is TileResult.Loaded })
    }

    @Test fun `falha nao fica presa e a proxima tentativa vai a rede de novo`() = runTest {
        net.fail = true
        val l = loader()
        l.load(tile)
        net.fail = false
        assertTrue(l.load(tile) is TileResult.Loaded)
        assertEquals(2, net.calls.size)
    }

    @Test fun `no maximo N downloads simultaneos`() = runTest {
        net.delayMs = 100
        val l = loader(maxConcurrent = 2)
        List(6) { i -> async { l.load(TileId(10, i, 0)) } }.awaitAll()
        assertEquals(6, net.calls.size)
        assertEquals(2, net.maxInFlight)
    }

    @Test fun `apos gravar despeja o excedente do limite de tamanho`() = runTest {
        val l = loader(TileCachePolicy(maxBytes = 4))
        store.write("antigo", byteArrayOf(7, 7, 7), now - day) // lastAccess antigo
        l.load(tile) // 3 bytes novos → total 6 > 4 → sai o menos acessado
        assertEquals(setOf("10_512_340"), store.data.keys)
    }
}

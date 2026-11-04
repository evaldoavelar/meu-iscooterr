package br.dev.evaldo.meuscooter.domain

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.withContext
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

    private open class FakeStore : TileCacheStore {
        var failDisk = false
        val data = mutableMapOf<String, Pair<ByteArray, Long>>()
        val access = mutableMapOf<String, Long>()
        override fun read(key: String) = data[key]?.let { StoredTile(it.first, it.second) }
        override fun write(key: String, bytes: ByteArray, nowMs: Long) { if (failDisk) throw IOException("disco cheio"); data[key] = bytes to nowMs; access[key] = nowMs }
        override fun touch(key: String, nowMs: Long) { access[key] = nowMs }
        override fun entries() = data.map { (k, v) -> CacheEntry(k, v.first.size.toLong(), v.second, access[k] ?: v.second) }
        override fun delete(key: String) { data.remove(key); access.remove(key) }
    }

    private val net = FakeNetwork()
    private val store = FakeStore()
    private var now = 100 * day

    private fun TestScope.loader(policy: TileCachePolicy = TileCachePolicy(), maxConcurrent: Int = 2) =
        CachingTileLoader(OsmTileSource("0.2.0"), net, store, policy, { now }, backgroundScope, maxConcurrent,
            retryClockMs = { testScheduler.currentTime })

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

    @Test fun `last cancelled waiter stops the download and leaves no cache or retry state`() = runTest {
        net.delayMs = 1_000
        val l = loader()
        val request = async { l.load(tile) }
        runCurrent()
        assertEquals(1, net.inFlight)
        request.cancelAndJoin()
        runCurrent()
        assertEquals(0, net.inFlight)
        advanceTimeBy(1_000)
        runCurrent()
        assertTrue(store.data.isEmpty())
        net.delayMs = 0
        assertTrue(l.load(tile) is TileResult.Loaded)
        assertEquals(2, net.calls.size)
    }

    @Test fun `one cancelled waiter preserves a shared download for the other waiter`() = runTest {
        net.delayMs = 1_000
        val l = loader()
        val first = async { l.load(tile) }
        val second = async { l.load(tile) }
        runCurrent()
        first.cancelAndJoin()
        runCurrent()
        assertEquals(1, net.inFlight)
        assertTrue(second.await() is TileResult.Loaded)
        assertEquals(1, net.calls.size)
        assertEquals(0, net.inFlight)
    }

    @Test fun `cancelled queued downloads never fetch and release their permits`() = runTest {
        net.delayMs = 1_000
        val l = loader(maxConcurrent = 1)
        val first = async { l.load(tile) }
        runCurrent()
        val queued = async { l.load(TileId(10, 513, 340)) }
        runCurrent()
        queued.cancelAndJoin()
        first.cancelAndJoin()
        runCurrent()
        net.delayMs = 0
        assertTrue(l.load(TileId(10, 514, 340)) is TileResult.Loaded)
        assertEquals(2, net.calls.size)
        assertEquals(1, net.maxInFlight)
        assertEquals(setOf("10_514_340"), store.data.keys)
    }

    @Test fun `late cancelled downloads cannot overwrite a replacement or set its backoff`() = runTest {
        for (lateFailure in listOf(false, true)) {
            val gate = CompletableDeferred<Unit>()
            val isolatedStore = FakeStore()
            var calls = 0
            val network = object : TileNetwork {
                override suspend fun fetch(url: String, userAgent: String): ByteArray {
                    calls++
                    if (calls == 1) {
                        // Model a blocking network implementation which returns after cancellation.
                        withContext(NonCancellable) { gate.await() }
                        if (lateFailure) throw IOException("late failure")
                        return byteArrayOf(1)
                    }
                    return byteArrayOf(9)
                }
            }
            val l = CachingTileLoader(OsmTileSource("0.2.0"), network, isolatedStore, TileCachePolicy(),
                { now }, backgroundScope, retryClockMs = { testScheduler.currentTime })
            val obsolete = async { l.load(tile) }
            runCurrent()
            obsolete.cancelAndJoin()
            assertTrue(l.load(tile) is TileResult.Loaded)
            gate.complete(Unit)
            runCurrent()
            assertEquals(listOf<Byte>(9), isolatedStore.data.getValue("10_512_340").first.toList())
            l.invalidate(tile)
            assertTrue(l.load(tile) is TileResult.Loaded)
            assertEquals(3, calls)
        }
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
        advanceTimeBy(30_000) // The failed tile becomes eligible again after the retry window.
        assertTrue(l.load(tile) is TileResult.Loaded)
        assertEquals(2, net.calls.size)
    }

    @Test fun `network failure backs off across reopenings until the retry deadline`() = runTest {
        val l = loader()
        net.fail = true
        assertEquals(TileResult.Unavailable, l.load(tile))
        net.fail = false
        repeat(3) { assertEquals(TileResult.Unavailable, l.load(tile)) }
        advanceTimeBy(29_999)
        assertEquals(TileResult.Unavailable, l.load(tile))
        assertEquals(1, net.calls.size)
        advanceTimeBy(1)
        assertTrue(l.load(tile) is TileResult.Loaded)
        assertEquals(2, net.calls.size)
    }

    @Test fun `backoff serves stale cache and does not suppress other tiles`() = runTest {
        store.write("10_512_340", byteArrayOf(9), now - 31 * day)
        val l = loader()
        net.fail = true
        assertTrue((l.load(tile) as TileResult.Loaded).stale)
        net.fail = false
        val stale = l.load(tile) as TileResult.Loaded
        assertTrue(stale.stale)
        assertEquals(listOf<Byte>(9), stale.bytes.toList())
        assertEquals(1, net.calls.size)
        assertTrue(l.load(TileId(10, 513, 340)) is TileResult.Loaded)
        assertEquals(2, net.calls.size)
        advanceTimeBy(30_000)
        assertFalse((l.load(tile) as TileResult.Loaded).stale)
        assertEquals(3, net.calls.size)
    }

    @Test fun `no maximo N downloads simultaneos`() = runTest {
        net.delayMs = 100
        val l = loader(maxConcurrent = 2)
        List(6) { i -> async { l.load(TileId(10, i, 0)) } }.awaitAll()
        assertEquals(6, net.calls.size)
        assertEquals(2, net.maxInFlight)
    }

    @Test fun `falha ao gravar no disco nao descarta o tile baixado nem repete o download`() = runTest {
        store.failDisk = true
        val r = loader().load(tile)
        assertTrue(r is TileResult.Loaded && !r.stale)
        assertEquals(1, net.calls.size)
    }

    @Test fun `invalidar apaga o tile do cache para o proximo pedido ir a rede`() = runTest {
        val l = loader()
        l.load(tile)
        l.invalidate(tile)
        assertTrue(store.data.isEmpty())
        l.load(tile)
        assertEquals(2, net.calls.size)
    }

    @Test fun `apos gravar despeja o excedente do limite de tamanho`() = runTest {
        val l = loader(TileCachePolicy(maxBytes = 4))
        store.write("antigo", byteArrayOf(7, 7, 7), now - day) // lastAccess antigo
        l.load(tile) // 3 bytes novos → total 6 > 4 → sai o menos acessado
        assertEquals(setOf("10_512_340"), store.data.keys)
    }
}

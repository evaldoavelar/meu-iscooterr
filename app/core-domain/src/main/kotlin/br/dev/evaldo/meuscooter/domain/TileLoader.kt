package br.dev.evaldo.meuscooter.domain

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/** Resultado de carregar um tile. [Loaded.stale] = expirado, usado só porque a rede falhou. */
sealed interface TileResult {
    class Loaded(val bytes: ByteArray, val stale: Boolean) : TileResult
    data object Unavailable : TileResult
}

/** Carrega tiles (cache + rede). Nunca lança por falha de rede; cancelamento propaga. */
interface TileLoader {
    suspend fun load(tile: TileId): TileResult

    /** Descarta o tile do cache (ex.: bytes que não decodificam como imagem), para o próximo pedido ir à rede. */
    fun invalidate(tile: TileId)
}

/** Rede HTTP atrás de interface (implementação Android/JVM em `data`). Lança [IOException] em qualquer falha. */
interface TileNetwork {
    suspend fun fetch(url: String, userAgent: String): ByteArray
}

class StoredTile(val bytes: ByteArray, val storedAtMs: Long)

/** Cache em disco atrás de interface. [nowMs] é relógio de parede. */
interface TileCacheStore {
    fun read(key: String): StoredTile?
    fun write(key: String, bytes: ByteArray, nowMs: Long)
    fun touch(key: String, nowMs: Long)
    fun entries(): List<CacheEntry>
    fun delete(key: String)
}

/**
 * Cache conforme [TileCachePolicy]; expirado só serve se a rede falhar. Pedidos do mesmo tile em voo são
 * compartilhados e no máximo [maxConcurrent] downloads correm juntos (política de uso do OSM).
 * Downloads run in [scope]; cancelling one caller preserves other waiters, while the last waiter cancels the download.
 * Network failures wait 30 seconds before retrying the same tile.
 */
class CachingTileLoader(
    private val source: MapTileSource,
    private val network: TileNetwork,
    private val store: TileCacheStore,
    private val policy: TileCachePolicy,
    private val wallClockMs: () -> Long,
    private val scope: CoroutineScope,
    maxConcurrent: Int = 2,
    private val retryClockMs: () -> Long = { System.nanoTime() / 1_000_000 },
) : TileLoader {
    private val permits = Semaphore(maxConcurrent)
    private val lock = Mutex()
    private class PendingTile(val download: Deferred<TileResult>, var waiters: Int = 0)
    private val inFlight = HashMap<TileId, PendingTile>()
    private val retryAfter = HashMap<TileId, Long>()

    override suspend fun load(tile: TileId): TileResult {
        val pending = lock.withLock {
            inFlight.getOrPut(tile) {
                PendingTile(scope.async(start = CoroutineStart.LAZY) { permits.withPermit { resolve(tile) } })
            }.also { it.waiters++ }
        }
        try {
            return pending.download.await()
        } finally {
            withContext(NonCancellable) {
                lock.withLock {
                    pending.waiters--
                    if (pending.waiters == 0) {
                        if (inFlight[tile] === pending) inFlight.remove(tile)
                        pending.download.cancel()
                    }
                }
            }
        }
    }

    override fun invalidate(tile: TileId) {
        try { store.delete(key(tile)) } catch (_: Exception) { }
    }

    private fun key(tile: TileId) = "${tile.zoom}_${tile.x}_${tile.y}"

    private suspend fun resolve(tile: TileId): TileResult {
        val key = key(tile)
        val now = wallClockMs()
        val cached = store.read(key)
        if (cached != null && policy.isFresh(cached.storedAtMs, now)) {
            store.touch(key, now)
            return TileResult.Loaded(cached.bytes, stale = false)
        }
        val backingOff = lock.withLock {
            val retryNow = retryClockMs()
            retryAfter.entries.removeAll { it.value <= retryNow }
            tile in retryAfter
        }
        if (backingOff) return if (cached != null) TileResult.Loaded(cached.bytes, stale = true) else TileResult.Unavailable
        val bytes = try {
            network.fetch(source.urlFor(tile), source.userAgent)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            lock.withLock { retryAfter[tile] = retryClockMs() + 30_000 }
            return if (cached != null) TileResult.Loaded(cached.bytes, stale = true) else TileResult.Unavailable
        }
        currentCoroutineContext().ensureActive()
        // Falha de disco não invalida o tile baixado: mostra e segue sem cache.
        try {
            store.write(key, bytes, now)
            policy.evict(store.entries(), now).forEach(store::delete)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) { }
        return TileResult.Loaded(bytes, stale = false)
    }
}

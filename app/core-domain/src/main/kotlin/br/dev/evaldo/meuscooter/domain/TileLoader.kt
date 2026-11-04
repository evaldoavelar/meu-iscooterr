package br.dev.evaldo.meuscooter.domain

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

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
 * O download roda em [scope] (do carregador), então cancelar um chamador não derruba os demais.
 */
class CachingTileLoader(
    private val source: MapTileSource,
    private val network: TileNetwork,
    private val store: TileCacheStore,
    private val policy: TileCachePolicy,
    private val wallClockMs: () -> Long,
    private val scope: CoroutineScope,
    maxConcurrent: Int = 2,
) : TileLoader {
    private val permits = Semaphore(maxConcurrent)
    private val lock = Mutex()
    private val inFlight = HashMap<TileId, Deferred<TileResult>>()

    override suspend fun load(tile: TileId): TileResult {
        val job = lock.withLock {
            inFlight.getOrPut(tile) {
                scope.async { try { permits.withPermit { resolve(tile) } } finally { lock.withLock { inFlight.remove(tile) } } }
            }
        }
        return job.await()
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
        val bytes = try {
            network.fetch(source.urlFor(tile), source.userAgent)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return if (cached != null) TileResult.Loaded(cached.bytes, stale = true) else TileResult.Unavailable
        }
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

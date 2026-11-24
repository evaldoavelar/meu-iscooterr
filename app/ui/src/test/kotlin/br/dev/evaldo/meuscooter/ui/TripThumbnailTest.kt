package br.dev.evaldo.meuscooter.ui

import androidx.compose.ui.graphics.ImageBitmap
import br.dev.evaldo.meuscooter.domain.GeoPoint
import br.dev.evaldo.meuscooter.domain.TileId
import br.dev.evaldo.meuscooter.domain.TileLoader
import br.dev.evaldo.meuscooter.domain.TileResult
import br.dev.evaldo.meuscooter.ui.history.MapTiles
import br.dev.evaldo.meuscooter.ui.history.ThumbnailMap
import br.dev.evaldo.meuscooter.ui.history.TripThumbnailCache
import br.dev.evaldo.meuscooter.ui.history.loadThumbnailMap
import br.dev.evaldo.meuscooter.ui.history.thumbnailPlacements
import br.dev.evaldo.meuscooter.ui.history.thumbnailViewport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * S52: enquadramento e fallback sem cache da miniatura do Histórico. Valores esperados calculados à parte (Web Mercator,
 * script Python independente): rota de 3 pontos em São Paulo, miniatura 216x216 px, margem 30 px => zoom 15,03; tiles z15
 * x 12139..12140, y 18590..18591; primeiro ponto em (36,50; 30,00) e último em (179,50; 186,00).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class TripThumbnailTest {
    private val route = listOf(GeoPoint(-23.55, -46.63), GeoPoint(-23.553, -46.627), GeoPoint(-23.556, -46.624))
    private val expectedTiles = setOf(TileId(15, 12139, 18590), TileId(15, 12140, 18590), TileId(15, 12139, 18591), TileId(15, 12140, 18591))

    private class CacheOnlyLoader(val cached: Set<TileId>, val online: Boolean = false) : TileLoader {
        val cachedCalls = mutableListOf<TileId>()
        val networkCalls = mutableListOf<TileId>()
        override suspend fun load(tile: TileId): TileResult {
            networkCalls += tile
            return if (online) TileResult.Loaded(byteArrayOf(1), stale = false) else TileResult.Unavailable
        }
        override suspend fun loadCached(tile: TileId): TileResult {
            cachedCalls += tile
            return if (tile in cached) TileResult.Loaded(byteArrayOf(1), stale = false) else TileResult.Unavailable
        }
        override fun invalidate(tile: TileId) = Unit
    }

    private fun tiles(loader: TileLoader, decodes: Boolean = true) =
        MapTiles(loader, "attr", decode = { if (decodes) ImageBitmap(2, 2) else null }, decodeDispatcher = Dispatchers.Unconfined)

    @Test fun `enquadra a rota inteira com a margem e escolhe o zoom que cabe`() {
        val placements = thumbnailPlacements(route, 216f, 216f, 30f)
        assertEquals(expectedTiles, placements.map { it.tile }.toSet())
        assertEquals(4, placements.size)
        val viewport = thumbnailViewport(route, 216f, 216f, 30f)!!
        assertEquals(15.0326, viewport.zoom, 0.001)
        val first = viewport.project(route.first()); val last = viewport.project(route.last())
        assertEquals(36.50f, first.x, 0.1f); assertEquals(30.00f, first.y, 0.1f)
        assertEquals(179.50f, last.x, 0.1f); assertEquals(186.00f, last.y, 0.1f)
    }

    @Test fun `sem rota ou tamanho desconhecido nao ha enquadramento`() {
        assertNull(thumbnailViewport(listOf(GeoPoint(-23.55, -46.63)), 216f, 216f, 30f))
        assertNull(thumbnailViewport(route, 0f, 216f, 30f))
        assertTrue(thumbnailPlacements(emptyList(), 216f, 216f, 30f).isEmpty())
    }

    @Test fun `todos os tiles em cache montam o mapa, lendo so o cache`() = runBlocking {
        val loader = CacheOnlyLoader(expectedTiles)
        val placements = thumbnailPlacements(route, 216f, 216f, 30f)
        val map = loadThumbnailMap(tiles(loader), placements) { it }
        assertNotNull(map)
        assertEquals(expectedTiles, map!!.bitmaps.keys)
        assertTrue(loader.networkCalls.isEmpty())
    }

    @Test fun `tile fora do cache e baixado pelo carregador e o mapa monta`() = runBlocking {
        val missing = TileId(15, 12140, 18591)
        val loader = CacheOnlyLoader(expectedTiles - missing, online = true)
        val map = loadThumbnailMap(tiles(loader), thumbnailPlacements(route, 216f, 216f, 30f)) { it }
        assertNotNull(map)
        assertEquals(expectedTiles, map!!.bitmaps.keys)
        assertEquals(listOf(missing), loader.networkCalls)
    }

    @Test fun `offline com tile faltando cai no fallback`() = runBlocking {
        val loader = CacheOnlyLoader(expectedTiles - TileId(15, 12140, 18591))
        assertNull(loadThumbnailMap(tiles(loader), thumbnailPlacements(route, 216f, 216f, 30f)) { it })
        assertEquals(1, loader.networkCalls.size)
    }

    @Test fun `tile que nao decodifica cai no fallback`() = runBlocking {
        val loader = CacheOnlyLoader(expectedTiles)
        assertNull(loadThumbnailMap(tiles(loader, decodes = false), thumbnailPlacements(route, 216f, 216f, 30f)) { it })
        assertTrue(loader.networkCalls.isEmpty())
    }

    @Test fun `cache em memoria por viagem e tamanho, com limite LRU`() {
        val cache = TripThumbnailCache(max = 2)
        fun key(id: Long, px: Int) = TripThumbnailCache.Key(id, px, px)
        val m = ThumbnailMap(emptyList(), emptyMap())
        cache.put(key(1, 216), m); cache.put(key(2, 216), m)
        assertNotNull(cache.get(key(1, 216)))
        assertNull(cache.get(key(1, 144)))   // outro tamanho = outra entrada
        cache.put(key(3, 216), m)            // 2 é o menos recente (1 foi lido)
        assertNotNull(cache.get(key(1, 216)))
        assertNull(cache.get(key(2, 216)))
        assertNotNull(cache.get(key(3, 216)))
        assertEquals(2, cache.size())
    }
}

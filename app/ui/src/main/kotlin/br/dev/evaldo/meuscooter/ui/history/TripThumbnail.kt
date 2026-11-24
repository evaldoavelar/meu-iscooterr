package br.dev.evaldo.meuscooter.ui.history

import androidx.compose.ui.graphics.ImageBitmap
import br.dev.evaldo.meuscooter.domain.GeoPoint
import br.dev.evaldo.meuscooter.domain.MapViewport
import br.dev.evaldo.meuscooter.domain.TileId
import br.dev.evaldo.meuscooter.domain.TilePlacement
import br.dev.evaldo.meuscooter.domain.TileResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Enquadramento da rota inteira na miniatura (mesmo [MapViewport.fit] do detalhe, sem câmera); null sem rota. */
internal fun thumbnailViewport(route: List<GeoPoint>, widthPx: Float, heightPx: Float, paddingPx: Float): MapViewport? =
    if (route.size < 2 || widthPx <= 0f || heightPx <= 0f) null else MapViewport.fit(route, widthPx, heightPx, paddingPx)

/** Tiles do enquadramento e sua posição na miniatura (px). */
internal fun thumbnailPlacements(route: List<GeoPoint>, widthPx: Float, heightPx: Float, paddingPx: Float): List<TilePlacement> =
    thumbnailViewport(route, widthPx, heightPx, paddingPx)?.tiles().orEmpty()

/** Tiles decodificados (já suavizados em API < 31) prontos para desenhar sob o trajeto. */
internal class ThumbnailMap(val placements: List<TilePlacement>, val bitmaps: Map<TileId, ImageBitmap>)

/**
 * Decodifica os tiles do enquadramento: cache em disco primeiro; o que faltar vem do carregador (rede + cache + política OSM,
 * S53), pois o zoom da miniatura difere do detalhe e seus tiles não chegam ao cache por outro caminho. Qualquer tile
 * indisponível (offline) ou que não decodifica = null (o chamador volta ao fundo liso; nunca erro nem spinner). Mapa parcial não é mostrado.
 */
internal suspend fun loadThumbnailMap(tiles: MapTiles, placements: List<TilePlacement>, soften: (ImageBitmap) -> ImageBitmap): ThumbnailMap? {
    if (placements.isEmpty()) return null
    val bitmaps = HashMap<TileId, ImageBitmap>()
    for (p in placements) {
        if (p.tile in bitmaps) continue
        val r = withContext(Dispatchers.IO) {
            tiles.loader.loadCached(p.tile).takeIf { it is TileResult.Loaded } ?: tiles.loader.load(p.tile)
        } as? TileResult.Loaded ?: return null
        val bmp = withContext(tiles.decodeDispatcher) { tiles.decode(r.bytes)?.let(soften) }
        if (bmp == null) { tiles.loader.invalidate(p.tile); return null }   // bytes inválidos não ficam presos no cache
        bitmaps[p.tile] = bmp
    }
    return ThumbnailMap(placements, bitmaps)
}

/** Cache LRU em memória por viagem e tamanho (px); só guarda sucesso, para um tile que chegar ao cache depois ser usado. */
internal class TripThumbnailCache(private val max: Int = 24) {
    data class Key(val tripId: Long, val widthPx: Int, val heightPx: Int)

    private val map = object : LinkedHashMap<Key, ThumbnailMap>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, ThumbnailMap>?) = size > max
    }

    @Synchronized fun get(key: Key): ThumbnailMap? = map[key]
    @Synchronized fun put(key: Key, value: ThumbnailMap) { map[key] = value }
    @Synchronized fun size() = map.size
}

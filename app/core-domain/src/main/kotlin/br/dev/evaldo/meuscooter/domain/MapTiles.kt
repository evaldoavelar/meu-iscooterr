package br.dev.evaldo.meuscooter.domain

import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.tan
import kotlin.math.cos

data class TileId(val zoom: Int, val x: Int, val y: Int)

/** Esquema "slippy map" (Web Mercator) do OSM. */
object TileMath {
    fun tileFor(p: GeoPoint, zoom: Int): TileId {
        val n = 1 shl zoom
        val x = floor((p.lon + 180.0) / 360.0 * n).toInt().coerceIn(0, n - 1)
        val latRad = Math.toRadians(p.lat)
        val y = floor((1.0 - ln(tan(latRad) + 1.0 / cos(latRad)) / PI) / 2.0 * n).toInt().coerceIn(0, n - 1)
        return TileId(zoom, x, y)
    }
}

/** Fonte de tiles do mapa (decisão: OSM online + cache). Outras fontes (offline) podem implementar a mesma interface. */
interface MapTileSource {
    val attribution: String
    val userAgent: String
    fun urlFor(tile: TileId): String
}

/** Tiles oficiais do OSM: exigem atribuição visível e User-Agent próprio; uso pessoal e leve (respeitar a política de uso de tiles). */
class OsmTileSource(override val userAgent: String = "MeuPatinete/0.1 (uso pessoal)") : MapTileSource {
    override val attribution = "© colaboradores do OpenStreetMap"
    override fun urlFor(tile: TileId) = "https://tile.openstreetmap.org/${tile.zoom}/${tile.x}/${tile.y}.png"
}

data class CacheEntry(val key: String, val bytes: Long, val storedAtMs: Long, val lastAccessMs: Long)

/** Validade de 30 dias (decisão do proprietário); limite de tamanho 50 MB `[HIPÓTESE]`. */
class TileCachePolicy(
    val maxAgeMs: Long = 30L * 86_400_000L,
    val maxBytes: Long = 50L * 1024 * 1024,
) {
    fun isFresh(storedAtMs: Long, nowMs: Long) = nowMs - storedAtMs <= maxAgeMs

    /** Chaves a remover: expirados primeiro, depois os menos acessados até caber em [maxBytes]. */
    fun evict(entries: List<CacheEntry>, nowMs: Long): List<String> {
        val (expired, alive) = entries.partition { !isFresh(it.storedAtMs, nowMs) }
        val out = expired.map { it.key }.toMutableList()
        var total = alive.sumOf { it.bytes }
        for (e in alive.sortedBy { it.lastAccessMs }) {
            if (total <= maxBytes) break
            out += e.key; total -= e.bytes
        }
        return out
    }
}

data class ProjectedPoint(val x: Float, val y: Float)

/** Projeção simples do trajeto num retângulo (escala igual nos eixos, centralizado, norte em cima). Aproximação para trajetos curtos. */
object RouteProjection {
    fun project(route: List<GeoPoint>, width: Float, height: Float, padding: Float): List<ProjectedPoint> {
        if (route.isEmpty()) return emptyList()
        val minLat = route.minOf { it.lat }; val maxLat = route.maxOf { it.lat }
        val minLon = route.minOf { it.lon }; val maxLon = route.maxOf { it.lon }
        val spanLon = (maxLon - minLon).toFloat(); val spanLat = (maxLat - minLat).toFloat()
        val availW = width - 2 * padding; val availH = height - 2 * padding
        val scale = when {
            spanLon <= 0f && spanLat <= 0f -> 0f
            spanLon <= 0f -> availH / spanLat
            spanLat <= 0f -> availW / spanLon
            else -> min(availW / spanLon, availH / spanLat)
        }
        val offX = padding + (availW - spanLon * scale) / 2f
        val offY = padding + (availH - spanLat * scale) / 2f
        return route.map {
            ProjectedPoint(offX + (it.lon - minLon).toFloat() * scale, offY + (maxLat - it.lat).toFloat() * scale)
        }
    }
}

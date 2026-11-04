package br.dev.evaldo.meuscooter.domain

import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.tan
import kotlin.math.cos

/** Tile posicionado na tela (px): canto superior esquerdo e lado. */
data class TilePlacement(val tile: TileId, val left: Float, val top: Float, val size: Float)

/**
 * Enquadramento Web Mercator de um trajeto numa área [width]×[height] px. [zoom] é fracionário (a rota cabe com [padding]);
 * Tiles use an integer zoom at or below [zoom], enlarged to keep at most 24 requests per viewport.
 * O traçado projetado aqui alinha com os tiles (diferente de [RouteProjection]).
 */
class MapViewport private constructor(
    val zoom: Double,
    private val centerX: Double,
    private val centerY: Double,
    private val width: Float,
    private val height: Float,
) {
    private val scale = 256.0 * Math.pow(2.0, zoom) // px por unidade de mundo normalizado

    fun project(p: GeoPoint): ProjectedPoint =
        ProjectedPoint(((nx(p.lon) - centerX) * scale + width / 2.0).toFloat(), ((ny(p.lat) - centerY) * scale + height / 2.0).toFloat())

    fun tiles(): List<TilePlacement> {
        val halfW = width / 2.0 / scale; val halfH = height / 2.0 / scale
        fun bounds(n: Int): IntArray = intArrayOf(
            max(0, floor((centerX - halfW) * n).toInt()), min(n - 1, floor((centerX + halfW) * n).toInt()),
            max(0, floor((centerY - halfH) * n).toInt()), min(n - 1, floor((centerY + halfH) * n).toInt()),
        )
        var tz = floor(zoom).toInt()
        var limits = bounds(1 shl tz)
        while (tz > 0 && (limits[1] - limits[0] + 1).coerceAtLeast(0).toLong() *
            (limits[3] - limits[2] + 1).coerceAtLeast(0) > 24
        ) {
            tz--
            limits = bounds(1 shl tz)
        }
        val n = 1 shl tz
        val (x0, x1, y0, y1) = limits
        val size = scale / n
        return (y0..y1).flatMap { ty ->
            (x0..x1).map { tx ->
                TilePlacement(
                    TileId(tz, tx, ty),
                    ((tx.toDouble() / n - centerX) * scale + width / 2.0).toFloat(),
                    ((ty.toDouble() / n - centerY) * scale + height / 2.0).toFloat(),
                    size.toFloat(),
                )
            }
        }
    }

    companion object {
        const val MAX_ZOOM = 16
        private const val MAX_LAT = 85.0511287798

        private fun nx(lon: Double) = (lon + 180.0) / 360.0
        private fun ny(lat: Double): Double {
            val r = Math.toRadians(lat.coerceIn(-MAX_LAT, MAX_LAT))
            return (1.0 - ln(tan(r) + 1.0 / cos(r)) / PI) / 2.0
        }

        fun fit(route: List<GeoPoint>, width: Float, height: Float, padding: Float): MapViewport? {
            if (route.isEmpty()) return null
            val xs = route.map { nx(it.lon) }; val ys = route.map { ny(it.lat) }
            val dx = xs.max() - xs.min(); val dy = ys.max() - ys.min()
            val availW = max(width - 2 * padding, 1f).toDouble(); val availH = max(height - 2 * padding, 1f).toDouble()
            val s = min(if (dx > 0) availW / dx else Double.MAX_VALUE, if (dy > 0) availH / dy else Double.MAX_VALUE)
            val z = if (s == Double.MAX_VALUE) MAX_ZOOM.toDouble() else (ln(s / 256.0) / ln(2.0)).coerceIn(0.0, MAX_ZOOM.toDouble())
            return MapViewport(z, (xs.max() + xs.min()) / 2, (ys.max() + ys.min()) / 2, width, height)
        }
    }
}

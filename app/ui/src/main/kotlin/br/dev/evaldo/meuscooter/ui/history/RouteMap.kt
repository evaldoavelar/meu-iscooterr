package br.dev.evaldo.meuscooter.ui.history

import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import br.dev.evaldo.meuscooter.domain.GeoPoint
import br.dev.evaldo.meuscooter.domain.MapViewport
import br.dev.evaldo.meuscooter.domain.ProjectedPoint
import br.dev.evaldo.meuscooter.domain.RouteProjection
import br.dev.evaldo.meuscooter.domain.TileId
import br.dev.evaldo.meuscooter.domain.TileLoader
import br.dev.evaldo.meuscooter.domain.TileResult
import br.dev.evaldo.meuscooter.ui.theme.AppTheme
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Tiles do mapa injetados de fora (carregador + texto de atribuição da fonte); `null` = só traçado sobre fundo neutro. */
class MapTiles(val loader: TileLoader, val attribution: String)

val LocalMapTiles = staticCompositionLocalOf<MapTiles?> { null }

/**
 * Trajeto sobre tiles do OSM (se [LocalMapTiles] houver) ou sobre fundo neutro. Miniaturas nunca usam tiles
 * (lista com várias miniaturas geraria muitas requisições; política de uso do OSM). A atribuição aparece sempre que
 * houver ao menos um tile desenhado; sem tiles (carregando/offline) não há atribuição, e offline mostra um aviso discreto.
 */
@Composable
fun RouteMap(route: List<GeoPoint>, modifier: Modifier = Modifier, thumb: Boolean = false) {
    val line = MaterialTheme.colorScheme.primary
    val start = AppTheme.extended.success
    val card = MaterialTheme.colorScheme.surface
    val radius = if (thumb) 14.dp else 20.dp
    val tiles = if (thumb || route.size < 2) null else LocalMapTiles.current
    Box(modifier.clip(RoundedCornerShape(radius)).background(AppTheme.extended.track), contentAlignment = Alignment.Center) {
        if (route.size < 2) {
            if (!thumb) Text("Sem trajeto registrado", Modifier.testTag("route-empty"), style = MaterialTheme.typography.bodyMedium, color = AppTheme.extended.textSecondary)
        } else {
            val padPx = with(androidx.compose.ui.platform.LocalDensity.current) { (if (thumb) 10.dp else 24.dp).toPx() }
            var size by remember { mutableStateOf(IntSize.Zero) }
            val viewport = if (tiles != null && size.width > 0) MapViewport.fit(route, size.width.toFloat(), size.height.toFloat(), padPx) else null
            val bitmaps = remember { mutableStateMapOf<TileId, ImageBitmap>() }
            var failed by remember { mutableStateOf(false) }
            val placements = remember(viewport) { viewport?.tiles().orEmpty() }
            if (tiles != null) {
                LaunchedEffect(placements) {
                    failed = false
                    coroutineScope {
                        placements.forEach { p ->
                            launch {
                                val bmp = (tiles.loader.load(p.tile) as? TileResult.Loaded)?.let { r ->
                                    withContext(Dispatchers.Default) { BitmapFactory.decodeByteArray(r.bytes, 0, r.bytes.size)?.asImageBitmap() }
                                }
                                if (bmp != null) bitmaps[p.tile] = bmp else failed = true
                            }
                        }
                    }
                }
            }
            Canvas(Modifier.matchParentSize().testTag("route-map").onSizeChanged { size = it }) {
                val pts: List<ProjectedPoint> = when {
                    tiles == null -> RouteProjection.project(route, this.size.width, this.size.height, padPx)
                    viewport != null -> route.map(viewport::project)
                    else -> return@Canvas // tamanho ainda desconhecido: evita desenhar com projeção que depois mudaria
                }
                placements.forEach { p ->
                    val img = bitmaps[p.tile] ?: return@forEach
                    val l = p.left.roundToInt(); val t = p.top.roundToInt()
                    drawImage(img, dstOffset = IntOffset(l, t), dstSize = IntSize((p.left + p.size).roundToInt() - l, (p.top + p.size).roundToInt() - t))
                }
                val path = Path().apply {
                    moveTo(pts.first().x, pts.first().y)
                    pts.drop(1).forEach { lineTo(it.x, it.y) }
                }
                drawPath(path, line.copy(alpha = 0.3f), style = Stroke((if (thumb) 6.dp else 11.dp).toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                drawPath(path, line, style = Stroke((if (thumb) 3.dp else 5.dp).toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                val r = (if (thumb) 5.dp else 8.dp).toPx()
                drawCircle(card, r + 2.dp.toPx(), Offset(pts.first().x, pts.first().y))
                drawCircle(start, r, Offset(pts.first().x, pts.first().y))
                drawCircle(card, r + 2.dp.toPx(), Offset(pts.last().x, pts.last().y))
                drawCircle(line, r, Offset(pts.last().x, pts.last().y))
            }
            if (tiles != null && bitmaps.isNotEmpty()) {
                Text(
                    tiles.attribution,
                    Modifier.align(Alignment.BottomEnd).padding(4.dp).background(card.copy(alpha = 0.8f), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp).testTag("map-attribution"),
                    style = MaterialTheme.typography.labelSmall, color = AppTheme.extended.textSecondary,
                )
            } else if (tiles != null && failed) {
                Text(
                    "Mapa indisponível sem internet",
                    Modifier.align(Alignment.BottomStart).padding(8.dp).testTag("map-offline"),
                    style = MaterialTheme.typography.labelSmall, color = AppTheme.extended.textSecondary,
                )
            }
        }
    }
}

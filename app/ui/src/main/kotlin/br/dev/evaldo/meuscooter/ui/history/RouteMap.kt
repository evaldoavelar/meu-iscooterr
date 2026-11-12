package br.dev.evaldo.meuscooter.ui.history

import android.graphics.BitmapFactory
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.IconButton
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import br.dev.evaldo.meuscooter.ui.formatMapScale
import br.dev.evaldo.meuscooter.ui.mapScaleDistance
import br.dev.evaldo.meuscooter.ui.RouteMapCamera
import kotlinx.coroutines.delay
import kotlin.math.pow
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.ui.text.style.TextAlign
import br.dev.evaldo.meuscooter.ui.theme.AppIcons
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.text.drawText
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import br.dev.evaldo.meuscooter.ui.LinePattern
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
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/** Tiles do mapa injetados de fora (carregador + texto de atribuição da fonte); `null` = só traçado sobre fundo neutro. */
class MapTiles(
    val loader: TileLoader,
    val attribution: String,
    val decode: (ByteArray) -> ImageBitmap? = ::decodeTile,
    val decodeDispatcher: CoroutineDispatcher = Dispatchers.Default,
)

private fun decodeTile(bytes: ByteArray): ImageBitmap? = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()

/** Página de licença/atribuição do OSM, aberta ao tocar na atribuição. */
const val COPYRIGHT_URL = "https://www.openstreetmap.org/copyright"

/** Trecho colorido do trajeto; [pattern] repete a distinção da cor (pontilhado/tracejado/sólido/grosso). */
class MapSegment(val points: List<GeoPoint>, val color: Color, val pattern: LinePattern)

/** Marcador numerado de troca, desenhado com [color] e o número em texto. */
class MapMarker(val n: Int, val at: GeoPoint, val color: Color)

/** Trajeto por segmentos + marcadores; [description] vira o contentDescription do mapa. */
class MapOverlay(val segments: List<MapSegment>, val markers: List<MapMarker>, val description: String)

val LocalMapTiles = staticCompositionLocalOf<MapTiles?> { null }

/**
 * Trajeto sobre tiles do OSM (se [LocalMapTiles] houver) ou sobre fundo neutro. Miniaturas nunca usam tiles
 * (lista com várias miniaturas geraria muitas requisições; política de uso do OSM). A atribuição aparece sempre que
 * houver ao menos um tile desenhado; sem tiles (carregando/offline) não há atribuição, e offline mostra um aviso discreto.
 */
@Composable
fun RouteMap(route: List<GeoPoint>, modifier: Modifier = Modifier, thumb: Boolean = false, overlay: MapOverlay? = null) {
    val line = MaterialTheme.colorScheme.primary
    val start = AppTheme.extended.success
    val card = MaterialTheme.colorScheme.surface
    val ink = MaterialTheme.colorScheme.onSurface
    val radius = if (thumb) 14.dp else 20.dp
    val tiles = if (thumb || route.size < 2) null else LocalMapTiles.current
    Box(modifier.clip(RoundedCornerShape(radius)).background(AppTheme.extended.track), contentAlignment = Alignment.Center) {
        if (route.size < 2) {
            var backdrop by remember { mutableStateOf(BackdropState(resolved = false, hasLocation = false, mapShown = false, failed = false)) }
            // S45: sem percurso, tiles desfocados da última posição conhecida (ADR 0012); sem dado, fundo neutro.
            LastKnownBackdrop(thumb = thumb, attribution = !thumb) { backdrop = it }
            if (!thumb) {
                // Como design/mockups/viagem-sem-percurso-{mapa,sem-coordenada}-v1: véu sobre o mapa e selo com pino.
                if (!backdrop.mapShown) Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.35f)))
                Surface(Modifier.padding(16.dp).testTag("route-empty"), shape = RoundedCornerShape(16.dp), color = card, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)) {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp).widthIn(max = 200.dp), verticalArrangement = Arrangement.spacedBy(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(AppIcons.Pin, contentDescription = null, tint = AppTheme.extended.textSecondary)
                        Text("Sem percurso gravado", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                        Text("GPS sem leitura", style = MaterialTheme.typography.bodySmall, color = AppTheme.extended.textSecondary, textAlign = TextAlign.Center)
                        if (backdrop.showNoLocationNote) {
                            Text("Sem localização para mostrar o mapa", Modifier.testTag("route-empty-no-location"), style = MaterialTheme.typography.bodySmall, color = AppTheme.extended.textSecondary, textAlign = TextAlign.Center)
                        }
                    }
                }
            }
        } else {
            val padPx = with(androidx.compose.ui.platform.LocalDensity.current) { (if (thumb) 10.dp else 24.dp).toPx() }
            var size by remember { mutableStateOf(IntSize.Zero) }
            // A câmera é local ao mapa; trocar modo/motor não altera o enquadramento.
            var camera by remember(route, size) { mutableStateOf(RouteMapCamera()) }
            val baseViewport = remember(route, size, padPx) {
                if (size.width > 0 && size.height > 0) MapViewport.fit(route, size.width.toFloat(), size.height.toFloat(), padPx) else null
            }
            val maxScale = baseViewport?.let { minOf(16f, 2.0.pow(MapViewport.MAX_CAMERA_ZOOM - it.zoom).toFloat()) } ?: 16f
            fun move(factor: Float, pan: Offset = Offset.Zero, focus: Offset = Offset(size.width / 2f, size.height / 2f)) {
                camera = camera.transform(factor, pan.x, pan.y, size.width.toFloat(), size.height.toFloat(), focus.x, focus.y, maxScale)
            }
            val viewport = remember(baseViewport, camera) { baseViewport?.withCamera(camera.scale, camera.offsetX, camera.offsetY) }
            val placements = remember(viewport, tiles) { if (tiles != null) viewport?.tiles().orEmpty() else emptyList() }
            val bitmaps = remember(route, size, tiles) { mutableStateMapOf<TileId, ImageBitmap>() }
            val failures = remember(route, size, tiles) { mutableStateMapOf<TileId, Boolean>() }
            val visibleIds by rememberUpdatedState(placements.map { it.tile }.toSet())
            // Uma tarefa por tile: pan sem cruzar sua borda não reinicia E/S nem decodificação.
            // Espera 120 ms de permanência do tile; não baixa vizinhos nem níveis futuros (política OSM).
            if (tiles != null) placements.forEach { p ->
                key(route, size, tiles, p.tile) {
                    LaunchedEffect(Unit) {
                        if (p.tile in bitmaps || p.tile in failures) return@LaunchedEffect
                        delay(120)
                        val result = tiles.loader.load(p.tile)
                        val bmp = (result as? TileResult.Loaded)?.let { r ->
                            withContext(tiles.decodeDispatcher) { tiles.decode(r.bytes) }
                        }
                        if (bmp != null) {
                            // Até dois enquadramentos decodificados em memória; cache persistente continua no loader.
                            if (bitmaps.size >= 48) bitmaps.keys.firstOrNull { it !in visibleIds }?.let { bitmaps.remove(it) }
                            bitmaps[p.tile] = bmp
                        } else {
                            if (failures.size >= 48) failures.keys.firstOrNull { it !in visibleIds }?.let { failures.remove(it) }
                            failures[p.tile] = true
                            if (result is TileResult.Loaded) tiles.loader.invalidate(p.tile)
                        }
                    }
                }
            }
            val hasTiles = placements.any { it.tile in bitmaps }
            val failed = placements.any { it.tile in failures }
            val overlaySegs = overlay?.segments.orEmpty()
            val mapModifier = Modifier.matchParentSize().testTag("route-map").onSizeChanged { size = it }
                .let { m -> if (overlay != null) m.semantics { contentDescription = overlay.description } else m }
                .let { m -> if (thumb) m else m
                    .pointerInput(baseViewport) {
                        detectTransformGestures { centroid, pan, zoom, _ -> move(zoom, pan, centroid) }
                    }
                    .pointerInput(baseViewport) { detectTapGestures(onDoubleTap = { move(2f, focus = it) }) }
                }
            val measurer = androidx.compose.ui.text.rememberTextMeasurer()
            val markerStyle = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, color = AppTheme.extended.onColor)
            Canvas(mapModifier) {
                // Projeta rota + pontos dos segmentos/marcadores juntos (mesma transformação; os extras ficam dentro da extensão da rota).
                val extra = overlaySegs.flatMap { it.points } + overlay?.markers.orEmpty().map { it.at }
                val all = route + extra
                val projected: List<ProjectedPoint> = when {
                    thumb -> RouteProjection.project(all, this.size.width, this.size.height, padPx)
                    viewport != null -> all.map(viewport::project)
                    else -> return@Canvas // tamanho ainda desconhecido: evita desenhar com projeção que depois mudaria
                }
                val pts = projected.take(route.size)
                placements.forEach { p ->
                    val img = bitmaps[p.tile] ?: return@forEach
                    val l = p.left.roundToInt(); val t = p.top.roundToInt()
                    drawImage(img, dstOffset = IntOffset(l, t), dstSize = IntSize((p.left + p.size).roundToInt() - l, (p.top + p.size).roundToInt() - t))
                }
                if (overlay == null) {
                    val path = Path().apply {
                        moveTo(pts.first().x, pts.first().y)
                        pts.drop(1).forEach { lineTo(it.x, it.y) }
                    }
                    drawPath(path, line.copy(alpha = 0.3f), style = Stroke((if (thumb) 6.dp else 11.dp).toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                    drawPath(path, line, style = Stroke((if (thumb) 3.dp else 5.dp).toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                } else {
                    var cursor = route.size
                    overlaySegs.forEach { seg ->
                        val sp = projected.subList(cursor, cursor + seg.points.size); cursor += seg.points.size
                        val path = Path().apply { moveTo(sp.first().x, sp.first().y); sp.drop(1).forEach { lineTo(it.x, it.y) } }
                        val w = (if (seg.pattern == LinePattern.Thick) 8.dp else 5.dp).toPx()
                        drawPath(path, card, style = Stroke(w + 4.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                        val dash = when (seg.pattern) {
                            LinePattern.Dotted -> PathEffect.dashPathEffect(floatArrayOf(0.1f, 10.dp.toPx()))
                            LinePattern.Dashed -> PathEffect.dashPathEffect(floatArrayOf(12.dp.toPx(), 8.dp.toPx()))
                            else -> null
                        }
                        drawPath(path, seg.color, style = Stroke(w, cap = if (seg.pattern == LinePattern.Dashed) StrokeCap.Butt else StrokeCap.Round, join = StrokeJoin.Round, pathEffect = dash))
                    }
                    projected.drop(cursor).forEachIndexed { i, o ->
                        val m = overlay.markers[i]
                        val rr = 11.dp.toPx()
                        drawCircle(card, rr + 2.dp.toPx(), Offset(o.x, o.y))
                        drawCircle(m.color, rr, Offset(o.x, o.y))
                        val tl = measurer.measure("${m.n}", markerStyle)
                        drawText(tl, topLeft = Offset(o.x - tl.size.width / 2f, o.y - tl.size.height / 2f))
                    }
                }
                val r = (if (thumb) 5.dp else 8.dp).toPx()
                if (overlay == null) {
                    drawCircle(card, r + 2.dp.toPx(), Offset(pts.first().x, pts.first().y))
                    drawCircle(start, r, Offset(pts.first().x, pts.first().y))
                    drawCircle(card, r + 2.dp.toPx(), Offset(pts.last().x, pts.last().y))
                    drawCircle(line, r, Offset(pts.last().x, pts.last().y))
                } else {
                    // Início/fim NEUTROS (tokens de texto/cartão, design/s43.py): nunca usam cor de modo.
                    drawCircle(card, r + 2.dp.toPx(), Offset(pts.first().x, pts.first().y))
                    drawCircle(ink, r, Offset(pts.first().x, pts.first().y))
                    drawCircle(ink, r + 1.dp.toPx(), Offset(pts.last().x, pts.last().y))
                    drawCircle(card, r - 2.dp.toPx(), Offset(pts.last().x, pts.last().y))
                }
            }
            if (!thumb) {
                Column(Modifier.align(Alignment.TopEnd).padding(8.dp)) {
                    MapControl("Ampliar mapa", "map-zoom-in", enabled = camera.scale < maxScale, onClick = { move(2f) }) {
                        ZoomSign(plus = true)
                    }
                    MapControl("Reduzir mapa", "map-zoom-out", enabled = camera.scale > 1f, onClick = { move(0.5f) }) {
                        ZoomSign(plus = false)
                    }
                    Spacer(Modifier.height(8.dp))
                    MapControl("Centralizar trajeto inteiro", "map-recenter", enabled = camera.canRecenter,
                        recenter = true, onClick = { camera = camera.recenter() }) {
                        Icon(AppIcons.Gps, contentDescription = null, modifier = Modifier.size(24.dp))
                    }
                }
                Row(Modifier.align(Alignment.BottomStart).padding(8.dp), verticalAlignment = Alignment.Bottom) {
                    viewport?.let { MapScale(it.metersPerPixel) }
                    Spacer(Modifier.width(8.dp))
                    if (tiles != null && hasTiles) {
                        val uri = androidx.compose.ui.platform.LocalUriHandler.current
                        Text(
                            tiles.attribution,
                            Modifier.weight(1f).background(card, RoundedCornerShape(6.dp))
                                .clickable { uri.openUri(COPYRIGHT_URL) }.padding(horizontal = 6.dp, vertical = 2.dp).testTag("map-attribution"),
                            style = MaterialTheme.typography.labelSmall, color = AppTheme.extended.textSecondary, textAlign = TextAlign.End,
                        )
                    } else if (tiles != null && failed) {
                        Text("Mapa indisponível sem internet", Modifier.weight(1f).background(card, RoundedCornerShape(6.dp))
                            .padding(4.dp).testTag("map-offline"),
                            style = MaterialTheme.typography.labelSmall, color = AppTheme.extended.textSecondary)
                    }
                }
            }
        }
    }
}


/** Alvo fixo de 48 dp; sinais vetoriais não cortam ao dobrar a fonte. */
@Composable
private fun MapControl(description: String, tag: String, enabled: Boolean, recenter: Boolean = false,
    onClick: () -> Unit, content: @Composable () -> Unit) {
    val ext = AppTheme.extended
    Surface(shape = RoundedCornerShape(10.dp),
        color = if (recenter) { if (enabled) MaterialTheme.colorScheme.primary else ext.disabled } else MaterialTheme.colorScheme.surface,
        contentColor = if (recenter) { if (enabled) MaterialTheme.colorScheme.onPrimary else ext.onDisabled } else MaterialTheme.colorScheme.onSurface,
        border = if (recenter) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outline)) {
        IconButton(onClick, Modifier.size(48.dp).testTag(tag).semantics { contentDescription = description }, enabled = enabled) { content() }
    }
}

@Composable
private fun ZoomSign(plus: Boolean) {
    val color = androidx.compose.material3.LocalContentColor.current
    val large = LocalDensity.current.fontScale >= 1.5f
    Canvas(Modifier.size(if (large) 28.dp else 20.dp)) {
        val inset = 4.dp.toPx()
        val stroke = (if (large) 3.dp else 2.dp).toPx()
        drawLine(color, Offset(inset, center.y), Offset(size.width - inset, center.y), stroke)
        if (plus) drawLine(color, Offset(center.x, inset), Offset(center.x, size.height - inset), stroke)
    }
}

@Composable
private fun MapScale(metersPerPixel: Double) {
    val density = LocalDensity.current
    val target = with(density) { 78.dp.toPx() } * metersPerPixel
    val distance = mapScaleDistance(target)
    val barWidth = with(density) { (distance / metersPerPixel).toFloat().toDp() }
    val color = AppTheme.extended.textSecondary
    Surface(Modifier.width(94.dp).testTag("map-scale"), shape = RoundedCornerShape(6.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)) {
        Column(Modifier.padding(horizontal = 6.dp, vertical = 2.dp)) {
            Text(formatMapScale(distance), style = MaterialTheme.typography.labelSmall, color = color)
            Canvas(Modifier.padding(start = 2.dp).width(barWidth).height(9.dp)) {
                val y = size.height - 3.dp.toPx()
                drawLine(color, Offset(0f, y), Offset(size.width, y), 1.5.dp.toPx())
                drawLine(color, Offset(0f, y - 4.dp.toPx()), Offset(0f, y), 1.5.dp.toPx())
                drawLine(color, Offset(size.width, y - 4.dp.toPx()), Offset(size.width, y), 1.5.dp.toPx())
            }
        }
    }
}

package br.dev.evaldo.meuscooter.ui.history

import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import br.dev.evaldo.meuscooter.domain.LastKnownLocation
import br.dev.evaldo.meuscooter.domain.LastKnownLocationSource
import br.dev.evaldo.meuscooter.domain.LastKnownMap
import br.dev.evaldo.meuscooter.domain.TileId
import br.dev.evaldo.meuscooter.domain.TileResult
import br.dev.evaldo.meuscooter.ui.theme.AppIcons
import br.dev.evaldo.meuscooter.ui.theme.AppTheme
import kotlin.math.roundToInt
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

val LocalLastKnownLocationSource = staticCompositionLocalOf<LastKnownLocationSource> { LastKnownLocationSource.None }

/** Descrição única do mapa desfocado (só existe quando há tile desenhado). */
const val BLURRED_MAP_DESCRIPTION = "Mapa desfocado da última localização conhecida"

/** Estado do fundo de viagem sem percurso: `locationKnown` só é definido após a consulta; `mapShown` = há ao menos um tile desenhado. */
class BackdropState(val resolved: Boolean, val hasLocation: Boolean, val mapShown: Boolean, val failed: Boolean) {
    /** Mostrar "Sem localização para mostrar o mapa": consulta resolvida e sem mapa (sem coordenada, sem tiles ou falha). */
    val showNoLocationNote get() = resolved && !mapShown && (!hasLocation || failed)
}

/**
 * Tiles OSM desfocados centrados na última posição conhecida (só em memória, ADR 0012). Preenche o [Box] pai; o pino fica no centro.
 * Consulta e tiles em coroutines: sem coordenada = fallback imediato. [onState] informa o estado ao chamador (nota do detalhe).
 */
@Composable
fun BoxScope.LastKnownBackdrop(thumb: Boolean, attribution: Boolean, onState: (BackdropState) -> Unit) {
    val tiles = LocalMapTiles.current
    val source = LocalLastKnownLocationSource.current
    val resolved by produceState<Resolved?>(null, source) { value = Resolved(source.lastKnown()) }
    val location = if (tiles != null) resolved?.location else null
    var size by remember { mutableStateOf(IntSize.Zero) }
    val density = LocalDensity.current.density
    val placements = remember(location, size, density) {
        if (location == null || size.width <= 0 || size.height <= 0) emptyList()
        else LastKnownMap.placements(location.point, size.width.toFloat(), size.height.toFloat(), density)
    }
    val bitmaps = remember(placements) { mutableStateMapOf<TileId, ImageBitmap>() }
    var failed by remember(placements) { mutableStateOf(false) }
    if (tiles != null) {
        LaunchedEffect(placements) {
            coroutineScope {
                placements.forEach { p ->
                    launch {
                        val result = tiles.loader.load(p.tile)
                        val bmp = (result as? TileResult.Loaded)?.let { r -> withContext(tiles.decodeDispatcher) { tiles.decode(r.bytes) } }
                        if (bmp != null) bitmaps[p.tile] = bmp else failed = true
                        if (bmp == null && result is TileResult.Loaded) tiles.loader.invalidate(p.tile)
                    }
                }
            }
        }
    }
    val state = BackdropState(resolved != null, location != null, bitmaps.isNotEmpty(), failed)
    LaunchedEffect(state.resolved, state.hasLocation, state.mapShown, state.failed) { onState(state) }
    val blurApi = Build.VERSION.SDK_INT >= 31
    Box(Modifier.matchParentSize().onSizeChanged { size = it }) {
        if (state.mapShown) {
            val radius = if (thumb) 6.dp else 12.dp
            Canvas(
                Modifier.matchParentSize().then(if (blurApi) Modifier.blur(radius, BlurredEdgeTreatment.Rectangle) else Modifier)
                    .semantics { contentDescription = BLURRED_MAP_DESCRIPTION },
            ) {
                placements.forEach { p ->
                    val img = bitmaps[p.tile] ?: return@forEach
                    val l = p.left.roundToInt(); val t = p.top.roundToInt()
                    drawImage(img, dstOffset = IntOffset(l, t), dstSize = IntSize((p.left + p.size).roundToInt() - l, (p.top + p.size).roundToInt() - t))
                }
            }
            // API < 31 não tem blur real: véu mais forte esconde o detalhe da rua.
            Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = if (blurApi) 0.12f else 0.45f)))
            if (attribution) {
                val uri = LocalUriHandler.current
                Text(
                    tiles!!.attribution,
                    Modifier.align(Alignment.BottomEnd).padding(4.dp).background(MaterialTheme.colorScheme.surface.copy(alpha = 0.8f), RoundedCornerShape(6.dp))
                        .clickable { uri.openUri(COPYRIGHT_URL) }.padding(horizontal = 6.dp, vertical = 2.dp).testTag("map-attribution"),
                    style = MaterialTheme.typography.labelSmall, color = AppTheme.extended.textSecondary,
                )
            }
        }
        if (thumb) {
            Surface(Modifier.align(Alignment.Center).size(32.dp).testTag("thumb-pin"), shape = CircleShape, color = MaterialTheme.colorScheme.surface,
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline)) {
                Box(contentAlignment = Alignment.Center) { Icon(AppIcons.Pin, contentDescription = null, Modifier.size(18.dp), tint = AppTheme.extended.textSecondary) }
            }
        }
    }
}

private class Resolved(val location: LastKnownLocation?)

package br.dev.evaldo.meuscooter.ui.history

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import br.dev.evaldo.meuscooter.domain.GeoPoint
import br.dev.evaldo.meuscooter.domain.RouteProjection
import br.dev.evaldo.meuscooter.ui.theme.AppTheme

/**
 * Trajeto sobre fundo neutro. Os tiles do OSM (`MapTileSource`) entram quando houver rede e rota real (Fase 5);
 * por isso não há atribuição aqui: nenhum tile é exibido.
 */
@Composable
fun RouteMap(route: List<GeoPoint>, modifier: Modifier = Modifier, thumb: Boolean = false) {
    val line = MaterialTheme.colorScheme.primary
    val start = AppTheme.extended.success
    val card = MaterialTheme.colorScheme.surface
    val radius = if (thumb) 14.dp else 20.dp
    Box(modifier.clip(RoundedCornerShape(radius)).background(AppTheme.extended.track), contentAlignment = Alignment.Center) {
        if (route.size < 2) {
            if (!thumb) Text("Sem trajeto registrado", Modifier.testTag("route-empty"), style = MaterialTheme.typography.bodyMedium, color = AppTheme.extended.textSecondary)
        } else {
            Canvas(Modifier.matchParentSize().testTag("route-map")) {
                val pad = (if (thumb) 10.dp else 24.dp).toPx()
                val pts = RouteProjection.project(route, size.width, size.height, pad)
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
        }
    }
}

package br.dev.evaldo.meuscooter.ui.history

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import br.dev.evaldo.meuscooter.ui.TripAltimetryUi
import br.dev.evaldo.meuscooter.ui.home.AppCard
import br.dev.evaldo.meuscooter.ui.theme.AppIcons
import br.dev.evaldo.meuscooter.ui.theme.AppTheme

/** Card aprovado em design/s48.py: métricas em duas colunas; fonte grande empilha sem encolher. */
@Composable
internal fun TripAltimetryCard(data: TripAltimetryUi?, big: Boolean) {
    AppCard(Modifier.fillMaxWidth().testTag("trip-altimetry")) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(AppIcons.Mountain, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
                Text("Altimetria", fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold)
            }
            if (data == null) {
                Text("Sem leitura", Modifier.testTag("trip-altimetry-empty"), fontSize = 16.sp, lineHeight = 20.sp,
                    fontWeight = FontWeight.Bold, color = AppTheme.extended.textSecondary)
            } else {
                val metrics = listOf(
                    Triple("Ganho", data.gain, "gain"), Triple("Perda", data.loss, "loss"),
                    Triple("Altitude mín.", data.min, "min"), Triple("Altitude máx.", data.max, "max"),
                )
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    metrics.chunked(if (big) 1 else 2).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            row.forEach { (label, value, tag) ->
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(label, Modifier.testTag("trip-altimetry-label-$tag"), fontSize = 11.sp, lineHeight = 14.sp, color = AppTheme.extended.textSecondary)
                                    Text(value, Modifier.testTag("trip-altimetry-$tag"), fontSize = 16.sp, lineHeight = 20.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
                AltitudeProfile(data, big)
            }
        }
    }
}

@Composable
private fun AltitudeProfile(data: TripAltimetryUi, big: Boolean) {
    val primary = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outline
    val secondary = AppTheme.extended.textSecondary
    val fontScale = LocalDensity.current.fontScale
    // Reserva de largura escalável para os rótulos; textos podem crescer em altura sem serem cortados.
    val labelWidth = ((if (big) 41 else 48) * fontScale).dp
    val chartHeight = if (big) 100.dp else 64.dp
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(0.dp)) {
        Column(Modifier.width(labelWidth).height(chartHeight), verticalArrangement = Arrangement.SpaceBetween) {
            Text(data.max, Modifier.testTag("trip-altimetry-axis-max"), fontSize = 10.sp, lineHeight = 12.sp, color = secondary)
            Text(data.min, Modifier.testTag("trip-altimetry-axis-min"), fontSize = 10.sp, lineHeight = 12.sp, color = secondary)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Canvas(Modifier.fillMaxWidth().height(chartHeight).testTag("trip-altimetry-profile").semantics {
                contentDescription = "Perfil de altitude por distância, de ${data.startDistance} a ${data.endDistance}; altitude entre ${data.min} e ${data.max}"
            }) {
                // Recuo mínimo impede cortar a espessura do traço nos extremos do Canvas.
                val inset = 1.dp.toPx()
                val width = (size.width - 2 * inset).coerceAtLeast(0f)
                val height = (size.height - 2 * inset).coerceAtLeast(0f)
                drawLine(grid, Offset(inset, inset), Offset(inset + width, inset), 1.dp.toPx())
                drawLine(grid, Offset(inset, inset + height), Offset(inset + width, inset + height), 1.dp.toPx())
                data.segments.forEach { segment ->
                    val points = segment.map { p ->
                        Offset(inset + p.distanceFraction * width, inset + (1 - p.heightFraction) * height)
                    }
                    if (points.size == 1) {
                        // Uma leitura isolada é só um ponto real, nunca uma linha interpolada.
                        drawCircle(primary, 1.dp.toPx(), points.single())
                    } else if (points.isNotEmpty()) {
                        val line = Path().apply {
                            moveTo(points.first().x, points.first().y)
                            points.drop(1).forEach { lineTo(it.x, it.y) }
                        }
                        val area = Path().apply {
                            addPath(line)
                            lineTo(points.last().x, inset + height)
                            lineTo(points.first().x, inset + height)
                            close()
                        }
                        drawPath(area, primary.copy(alpha = 0.12f))
                        drawPath(line, primary, style = Stroke(width = 2.dp.toPx()))
                    }
                }
            }
            Row(Modifier.fillMaxWidth()) {
                Text(data.startDistance, Modifier.weight(1f).testTag("trip-altimetry-axis-start"), fontSize = 10.sp, lineHeight = 12.sp, color = secondary)
                Text(data.endDistance, Modifier.weight(1f).testTag("trip-altimetry-axis-end"), fontSize = 10.sp, lineHeight = 12.sp, color = secondary, textAlign = TextAlign.End)
            }
        }
    }
}

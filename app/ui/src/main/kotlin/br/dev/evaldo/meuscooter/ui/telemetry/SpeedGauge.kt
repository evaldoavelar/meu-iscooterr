package br.dev.evaldo.meuscooter.ui.telemetry

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.ui.unit.IntOffset
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.remember
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.runtime.snapshotFlow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.stateDescription
import br.dev.evaldo.meuscooter.domain.SpeedSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import br.dev.evaldo.meuscooter.ui.theme.AppIcons
import br.dev.evaldo.meuscooter.ui.theme.AppTheme

private const val START_ANGLE = 150f   // arco de 240° aberto embaixo (design/telemetry.py: 210° → -30°)
private const val SWEEP = 240f

/**
 * Velocímetro 0–60 km/h: arco segmentado + neon externo contínuo (decisão de UX do proprietário).
 * Deslizar para a ESQUERDA mostra a velocidade do GPS; para a DIREITA volta ao patinete (S42). Ícone sensível à fonte e
 * 2 pontinhos de página abaixo dele (design/telemetry.py). O gesto só troca a fonte (exibição); [onSourceChange] persiste.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SpeedGauge(
    speedText: String, speedUnit: String, maxText: String, fraction: Float, modifier: Modifier = Modifier,
    source: SpeedSource = SpeedSource.Scooter, onSourceChange: (SpeedSource) -> Unit = {},
) {
    val pager = rememberPagerState(initialPage = source.ordinal) { SpeedSource.entries.size }
    val currentSource by rememberUpdatedState(source)
    val onChange by rememberUpdatedState(onSourceChange)
    // Gesto -> preferência (só quando a página assenta); preferência/ação de acessibilidade -> página.
    LaunchedEffect(pager) {
        snapshotFlow { pager.settledPage }.collect { page -> SpeedSource.entries[page].let { if (it != currentSource) onChange(it) } }
    }
    LaunchedEffect(source) { if (pager.currentPage != source.ordinal) pager.animateScrollToPage(source.ordinal) }
    val toGps = source == SpeedSource.Scooter
    Column(modifier.fillMaxWidth()) {
        GaugeFace(speedText, speedUnit, maxText, fraction, source, toGps, onToggle = { onChange(if (toGps) SpeedSource.Gps else SpeedSource.Scooter) }) {
            HorizontalPager(pager, Modifier.fillMaxSize().testTag("speed-pager").clearAndSetSemantics { }) { Spacer(Modifier.fillMaxSize()) }
        }
        Row(
            Modifier.align(Alignment.CenterHorizontally).offset(y = (-3).dp).testTag("speed-dots").clearAndSetSemantics { },
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            SpeedSource.entries.forEach {
                Box(Modifier.size(8.dp).testTag("speed-dot-${it.ordinal}").background(if (it == source) MaterialTheme.colorScheme.primary else AppTheme.extended.track, CircleShape))
            }
        }
    }
}

@Composable
private fun GaugeFace(
    speedText: String, speedUnit: String, maxText: String, fraction: Float, source: SpeedSource, toGps: Boolean,
    onToggle: () -> Unit, gestureLayer: @Composable () -> Unit,
) {
    val primary = MaterialTheme.colorScheme.primary
    val track = AppTheme.extended.track
    val label = if (toGps) "Mostrar velocidade do GPS" else "Mostrar velocidade do patinete"
    // Leitura única para o TalkBack (como a pílula de bateria): textos/ícones internos não viram nós separados.
    val reading = if (speedText == "--") "Velocidade desconhecida" else "Velocidade $speedText $speedUnit"
    Box(
        Modifier.fillMaxWidth().aspectRatio(1.25f).testTag("speed-gauge").clearAndSetSemantics {
            contentDescription = reading   // a fonte vai só no stateDescription (TalkBack lê "patinete" 1x)
            stateDescription = if (source == SpeedSource.Gps) "Velocidade do GPS" else "Velocidade do patinete"
            customActions = listOf(CustomAccessibilityAction(label) { onToggle(); true })
        },
        contentAlignment = Alignment.Center,
    ) {
        // Face que desliza ao trocar a fonte (a 1ª composição não anima). Só o alvo é lido: o pai limpa a semântica filha.
        val faces = remember { mutableMapOf<SpeedSource, GaugeValues>() }
        val live = GaugeValues(speedText, speedUnit, maxText, fraction)
        faces[source] = live
        AnimatedContent(
            targetState = source,
            modifier = Modifier.fillMaxSize(),
            transitionSpec = {
                // Direção segue o gesto: patinete -> GPS (deslizar à esquerda): a antiga sai pela esquerda e a nova entra pela direita;
                // GPS -> patinete (deslizar à direita): a antiga sai pela direita e a nova entra pela esquerda (300 ms).
                val move = tween<IntOffset>(300, easing = FastOutSlowInEasing)
                val fade = tween<Float>(300, easing = FastOutSlowInEasing)
                val dir = if (targetState.ordinal > initialState.ordinal) 1 else -1
                (slideInHorizontally(move) { dir * it } + fadeIn(fade)) togetherWith (slideOutHorizontally(move) { -dir * it } + fadeOut(fade))
            },
            label = "speed-source",
        ) { src ->
            // A face que sai mantém os últimos valores da própria fonte (não o texto da nova).
            val v = if (src == source) live else faces[src] ?: live
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxWidth().aspectRatio(1.25f)) {
                    val w = 14.dp.toPx()
                    val inset = 40.dp.toPx()
                    val diameter = size.height - inset
                    val topLeft = Offset((size.width - diameter) / 2f, inset / 2f)
                    val arcSize = Size(diameter, diameter)
                    val dashed = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx()))
                    drawArc(track, START_ANGLE, SWEEP, false, topLeft, arcSize, style = Stroke(w, pathEffect = dashed))
                    if (v.fraction > 0f) drawArc(primary, START_ANGLE, SWEEP * v.fraction, false, topLeft, arcSize, style = Stroke(w, pathEffect = dashed))
                    val outerPad = 17.dp.toPx() * 2
                    val outerTl = Offset(topLeft.x - outerPad / 2, topLeft.y - outerPad / 2)
                    val outerSize = Size(diameter + outerPad, diameter + outerPad)
                    val glow = maxOf(v.fraction, 0.02f)
                    drawArc(track, START_ANGLE, SWEEP, false, outerTl, outerSize, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round))
                    drawArc(primary.copy(alpha = 0.35f), START_ANGLE, SWEEP * glow, false, outerTl, outerSize, style = Stroke(11.dp.toPx(), cap = StrokeCap.Round))
                    drawArc(primary, START_ANGLE, SWEEP * glow, false, outerTl, outerSize, style = Stroke(5.dp.toPx(), cap = StrokeCap.Round))
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    ValueRow(v.speedText, v.speedUnit, tagged = true)
                    Text("Velocidade atual", Modifier.testTag("speed-label"), style = MaterialTheme.typography.bodySmall, color = AppTheme.extended.textSecondary)
                }
                if (src == SpeedSource.Gps) {
                    // Ícone 28 dp dentro de borda circular de 40 dp (traço 1,5 dp), mockup S45-C.
                    Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 6.dp).size(40.dp).border(1.5.dp, primary, CircleShape).testTag("gauge-gps-ring"), contentAlignment = Alignment.Center) {
                        Icon(AppIcons.Gps, contentDescription = null, Modifier.size(28.dp).testTag("gauge-gps"), tint = primary)
                    }
                }
                else Icon(AppIcons.Scooter, contentDescription = null, Modifier.align(Alignment.BottomCenter).padding(bottom = 6.dp).size(40.dp).testTag("gauge-scooter"), tint = primary)
                Text("0", Modifier.align(Alignment.BottomStart).padding(start = 48.dp), style = MaterialTheme.typography.bodySmall, color = AppTheme.extended.textSecondary)
                Text(v.maxText, Modifier.align(Alignment.BottomEnd).padding(end = 48.dp), style = MaterialTheme.typography.bodySmall, color = AppTheme.extended.textSecondary)
            }
        }
        gestureLayer()
    }
}

private data class GaugeValues(val speedText: String, val speedUnit: String, val maxText: String, val fraction: Float)

@Composable
private fun ValueRow(speedText: String, speedUnit: String, modifier: Modifier = Modifier, tagged: Boolean) {
    Row(modifier, verticalAlignment = Alignment.Bottom) {
        Text(speedText, if (tagged) Modifier.testTag("speed-value") else Modifier, fontSize = 56.sp, fontWeight = FontWeight.Bold)
        Text(speedUnit, Modifier.padding(start = 4.dp, bottom = 10.dp), fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = AppTheme.extended.textSecondary)
    }
}

/** Pílula de bateria sob o velocímetro (design/telemetry.py: 180×38 dp, raio 19 dp); mínimos, cresce com a fonte. */
@Composable
fun BatteryPill(batteryText: String, modifier: Modifier = Modifier) {
    val isKnown = batteryText != "--" && batteryText.isNotEmpty()
    val borderColor = if (isKnown) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
    val iconColor = if (isKnown) AppTheme.extended.success else AppTheme.extended.textSecondary
    val textColor = if (isKnown) MaterialTheme.colorScheme.onSurface else AppTheme.extended.textSecondary

    Box(
        modifier = modifier
            .widthIn(min = 180.dp)
            .heightIn(min = 38.dp)
            .border(1.dp, borderColor, RoundedCornerShape(19.dp))
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(19.dp))
            // Leitura única "Bateria 82%": os textos internos não são anunciados à parte.
            .testTag("battery-pill")
            .clearAndSetSemantics { contentDescription = "Bateria $batteryText" },
        contentAlignment = Alignment.Center,
    ) {
        // Largura mínima 180 dp (a 1,0 idêntica ao protótipo); com fonte grande a pílula cresce em vez de cortar.
        Row(
            modifier = Modifier.widthIn(min = 180.dp).padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    AppIcons.Battery,
                    contentDescription = null,
                    tint = iconColor,
                    modifier = Modifier.size(24.dp).testTag("battery-icon"),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    batteryText,
                    Modifier.testTag("battery-value"),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = textColor,
                    maxLines = 1,
                    softWrap = false,
                )
            }
            Spacer(Modifier.width(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .width(1.dp)
                        .height(18.dp)
                        .background(MaterialTheme.colorScheme.outline)
                )
                Spacer(Modifier.width(11.dp))
                Text(
                    "Bateria",
                    Modifier.testTag("battery-label"),
                    fontSize = 13.sp,
                    color = AppTheme.extended.textSecondary,
                    maxLines = 1,
                    softWrap = false,
                )
                Spacer(Modifier.width(4.dp))
            }
        }
    }
}

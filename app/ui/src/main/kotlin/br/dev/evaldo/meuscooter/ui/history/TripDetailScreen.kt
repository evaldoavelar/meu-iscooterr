package br.dev.evaldo.meuscooter.ui.history

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import br.dev.evaldo.meuscooter.ui.LinePattern
import br.dev.evaldo.meuscooter.ui.SegTone
import br.dev.evaldo.meuscooter.ui.TripLegendUi
import br.dev.evaldo.meuscooter.ui.TripViewUi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import br.dev.evaldo.meuscooter.ui.TripView
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import br.dev.evaldo.meuscooter.domain.GeoPoint
import br.dev.evaldo.meuscooter.ui.TripDetailUi
import br.dev.evaldo.meuscooter.ui.home.AppCard
import androidx.compose.ui.graphics.vector.ImageVector
import br.dev.evaldo.meuscooter.ui.theme.AppIcons
import br.dev.evaldo.meuscooter.ui.theme.AppTheme
import br.dev.evaldo.meuscooter.ui.theme.FitText
import br.dev.evaldo.meuscooter.ui.theme.IconTile

/** Detalhe da viagem (design/history.py `detalhe`). Dados locais; nada é enviado ao patinete. */
@Composable
fun TripDetailScreen(detail: TripDetailUi, route: List<GeoPoint>, onBack: () -> Unit, onNotice: ((String) -> Unit)? = null, initialView: TripView? = null) {
    val modes = detail.modes
    var view by rememberSaveable { mutableStateOf(if (!modes.hasData) TripView.None else initialView ?: TripView.Mode) }
    // Sem dado de modo/motor: aviso curto, uma vez por abertura (sobrevive a rotação).
    val context = LocalContext.current
    var noticed by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(modes.hasData) {
        if (!modes.hasData && !noticed) {
            noticed = true
            (onNotice ?: { m -> android.widget.Toast.makeText(context, m, android.widget.Toast.LENGTH_SHORT).show() })(NO_MODE_NOTICE)
        }
    }
    val active = when (view) { TripView.Mode -> modes.mode; TripView.Motor -> modes.motor; TripView.None -> null }
    val big = LocalDensity.current.fontScale >= 1.5f   // fonte grande: uma coluna, sem FitText (mockup -fonte2-v1)
    val plain = MaterialTheme.colorScheme.primary
    val overlay = active?.let { v ->
        MapOverlay(
            v.segments.map { MapSegment(it.points, toneColor(it.tone), it.pattern) },
            v.markers.mapNotNull { m -> m.at?.let { MapMarker(m.n, it, toneColor(m.tone)) } },
            v.mapDescription,
        )
    } ?: if (route.size >= 2) MapOverlay(listOf(MapSegment(route, plain, LinePattern.Solid)), emptyList(), "Trajeto sem cores por modo ou motor") else null
    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BackButton(onBack)
                Text(detail.title, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                Box(Modifier.size(48.dp))
            }
            ViewSelector(view, enabled = modes.hasData) { view = it }
            RouteMap(route, Modifier.fillMaxWidth().height(260.dp), overlay = overlay)
            active?.let { v ->
                Legend(v.legend, big)
                TimelineCard(v)
                TimesCard(v, big)
            }
            AppCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp).testTag("trip-stats"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    val stats = listOf<Triple<Pair<String, String>, ImageVector, String>>(
                        Triple("Distância" to detail.distance, AppIcons.Route, "route"), Triple("Duração" to detail.duration, AppIcons.Clock, "clock"),
                        Triple("Velocidade média" to detail.avg, AppIcons.Speed, "speed"), Triple("Velocidade máxima" to detail.max, AppIcons.Trend, "trend"),
                        Triple("Altimetria" to detail.altitude, AppIcons.Mountain, "mountain"), Triple("Consumo médio" to detail.consumption, AppIcons.Bolt, "bolt"),
                    )
                    stats.chunked(if (big) 1 else 2).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { row.forEach { (lv, icon, n) -> Item(lv.first, lv.second, icon, n, big) } }
                    }
                }
            }
            detail.battery?.let {
                AppCard(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        IconTile(AppIcons.Battery, "battery")
                        Text("Bateria na viagem", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = AppTheme.extended.textSecondary)
                        Text(it, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    }
                }
            }
            Text(
                "Trajetos e viagens ficam salvos só no seu celular.", Modifier.fillMaxWidth().padding(vertical = 8.dp),
                style = MaterialTheme.typography.bodySmall, color = AppTheme.extended.textSecondary, textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.Item(label: String, value: String, icon: ImageVector, name: String, big: Boolean = false) {
    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        IconTile(icon, name)
        Column(Modifier.weight(1f)) {
            if (big) {   // fonte grande: sem encolher (mockup -fonte2-v1); a coluna única comporta o texto
                Text(label, style = MaterialTheme.typography.bodySmall, color = AppTheme.extended.textSecondary)
                Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            } else {
                FitText(label, style = MaterialTheme.typography.bodySmall, color = AppTheme.extended.textSecondary)
                FitText(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
        }
    }
}

const val NO_MODE_NOTICE = "Sem informação de modo de condução"

@Composable
private fun toneColor(t: SegTone): Color = when (t) {
    SegTone.Success -> AppTheme.extended.success
    SegTone.Primary -> MaterialTheme.colorScheme.primary
    SegTone.Pending -> AppTheme.extended.pending
    SegTone.Error -> AppTheme.extended.error
    SegTone.Neutral -> AppTheme.extended.textSecondary
}

/** Seletor segmentado Modo | Motor | Nenhum (sem rótulo); desabilitado quando não há dado. */
@Composable
private fun ViewSelector(selected: TripView, enabled: Boolean, onSelect: (TripView) -> Unit) {
    val ext = AppTheme.extended
    Row(Modifier.fillMaxWidth().background(ext.track, RoundedCornerShape(26.dp)).padding(4.dp).selectableGroup().testTag("trip-view-selector")) {
        listOf(TripView.Mode to "Modo", TripView.Motor to "Motor", TripView.None to "Nenhum").forEach { (v, label) ->
            val on = v == selected
            Box(
                Modifier.weight(1f).heightIn(min = 48.dp)
                    .background(if (on) MaterialTheme.colorScheme.primary else Color.Transparent, RoundedCornerShape(22.dp))
                    .selectable(on, enabled = enabled, role = Role.RadioButton) { onSelect(v) }.testTag("trip-view-$label"),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label, style = MaterialTheme.typography.titleSmall, maxLines = 1, softWrap = false,
                    fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                    color = if (on) MaterialTheme.colorScheme.onPrimary else if (enabled) MaterialTheme.colorScheme.onSurface else ext.onDisabled,
                )
            }
        }
    }
}

@Composable
private fun LineSample(tone: SegTone, pattern: LinePattern, modifier: Modifier = Modifier) {
    val color = toneColor(tone)
    Canvas(modifier.size(width = 44.dp, height = 12.dp).clearAndSetSemantics {}) {
        val y = size.height / 2
        val w = (if (pattern == LinePattern.Thick) 8.dp else 5.dp).toPx()
        val effect = when (pattern) {
            LinePattern.Dotted -> PathEffect.dashPathEffect(floatArrayOf(0.1f, 10.dp.toPx()))
            LinePattern.Dashed -> PathEffect.dashPathEffect(floatArrayOf(10.dp.toPx(), 7.dp.toPx()))
            else -> null
        }
        drawLine(color, Offset(0f, y), Offset(size.width, y), w, cap = if (pattern == LinePattern.Dashed) StrokeCap.Butt else StrokeCap.Round, pathEffect = effect)
    }
}

@Composable
private fun Legend(items: List<TripLegendUi>, big: Boolean) {
    AppCard(Modifier.fillMaxWidth().testTag("trip-legend")) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items.chunked(if (big) 1 else 2).forEach { pair ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    pair.forEach { i ->
                        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            LineSample(i.tone, i.pattern)
                            Text(i.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        }
                    }
                    if (!big && pair.size == 1 && items.size > 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

private fun badgeSize(fontScale: Float) = if (fontScale >= 1.5f) 28.dp else 24.dp

@Composable
private fun NumberBadge(n: Int, tone: SegTone) {
    // Fonte grande: selo um pouco maior e número com tamanho efetivo limitado (mockup -fonte2-v1: 26 dp, número ~15), para não vazar do círculo.
    val fs = LocalDensity.current.fontScale
    Box(Modifier.size(badgeSize(fs)).background(toneColor(tone), CircleShape), contentAlignment = Alignment.Center) {
        val base = MaterialTheme.typography.labelSmall
        Text("$n", style = if (fs >= 1.5f) base.copy(fontSize = (base.fontSize.value * 1.4f / fs).sp) else base, fontWeight = FontWeight.Bold, color = AppTheme.extended.onColor, maxLines = 1, softWrap = false)
    }
}

@Composable
private fun TimelineCard(v: TripViewUi) {
    AppCard(Modifier.fillMaxWidth().testTag("trip-timeline-card")) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Linha do tempo", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            val total = v.blocks.sumOf { it.weight.toDouble() }.toFloat().coerceAtLeast(1f)
            Column(Modifier.semantics(mergeDescendants = true) { contentDescription = v.timelineDescription }.testTag("trip-timeline")) {
                Row(Modifier.fillMaxWidth().height(22.dp).clearAndSetSemantics {}, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    v.blocks.forEach { b -> Box(Modifier.weight(b.weight).fillMaxHeight().background(toneColor(b.tone), RoundedCornerShape(5.dp))) }
                }
                if (v.changes.isNotEmpty()) BoxWithConstraints(Modifier.fillMaxWidth().height(badgeSize(LocalDensity.current.fontScale) + 6.dp).padding(top = 4.dp).clearAndSetSemantics {}) {
                    var acc = 0f
                    v.blocks.forEachIndexed { i, b ->
                        if (i > 0) {
                            val c = v.changes[i - 1]
                            Box(Modifier.offset(x = maxWidth * (acc / total) - badgeSize(LocalDensity.current.fontScale) / 2)) { NumberBadge(c.n, c.tone) }
                        }
                        acc += b.weight
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = 2.dp)) {
                    Text(v.startTime, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = AppTheme.extended.textSecondary)
                    Text(v.endTime, style = MaterialTheme.typography.bodySmall, color = AppTheme.extended.textSecondary)
                }
            }
            if (v.singleText != null) Text(v.singleText, style = MaterialTheme.typography.bodyMedium, color = AppTheme.extended.textSecondary)
            v.changes.forEach { c ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    NumberBadge(c.n, c.tone)
                    Text("${c.time}  ${c.text}", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
private fun TimesCard(v: TripViewUi, big: Boolean) {
    AppCard(Modifier.fillMaxWidth().testTag("trip-times")) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(v.timesTitle, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            v.timeRows.forEach { r ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (big) {
                        // Fonte grande: nome e duração em linhas separadas, sem encolher (mockup -fonte2-v1).
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Box(Modifier.size(12.dp).background(toneColor(r.tone), RoundedCornerShape(3.dp)))
                            Text(r.label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        }
                        Text(r.value, Modifier.testTag("trip-time-value"), style = MaterialTheme.typography.bodyMedium, color = AppTheme.extended.textSecondary)
                    } else Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.size(12.dp).background(toneColor(r.tone), RoundedCornerShape(3.dp)))
                        FitText(r.label, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        FitText(r.value, Modifier.testTag("trip-time-value"), style = MaterialTheme.typography.bodyMedium, color = AppTheme.extended.textSecondary)
                    }
                    Box(Modifier.fillMaxWidth().height(6.dp).background(AppTheme.extended.track, CircleShape)) {
                        Box(Modifier.fillMaxWidth(r.fraction.coerceIn(0.02f, 1f)).fillMaxHeight().background(toneColor(r.tone), CircleShape))
                    }
                }
            }
        }
    }
}

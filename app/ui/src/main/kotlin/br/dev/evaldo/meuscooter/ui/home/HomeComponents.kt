package br.dev.evaldo.meuscooter.ui.home

import androidx.compose.material3.IconButton
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import br.dev.evaldo.meuscooter.ui.ChoiceUi
import br.dev.evaldo.meuscooter.ui.ConnectionKind
import br.dev.evaldo.meuscooter.ui.ControlStatus
import br.dev.evaldo.meuscooter.ui.ControlUi
import br.dev.evaldo.meuscooter.ui.HomeUiState
import br.dev.evaldo.meuscooter.ui.LimitUi
import br.dev.evaldo.meuscooter.ui.MainButtonKind
import br.dev.evaldo.meuscooter.ui.MainButtonUi
import br.dev.evaldo.meuscooter.ui.theme.AppIcons
import br.dev.evaldo.meuscooter.ui.theme.AppTheme

private val CardShape = RoundedCornerShape(20.dp)

@Composable
internal fun AppCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(
        modifier, shape = CardShape, color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline), content = content,
    )
}

@Composable
private fun statusColor(c: ControlUi): Color = when (c.status) {
    ControlStatus.Pending -> AppTheme.extended.pending
    ControlStatus.Failed, ControlStatus.TimedOut -> AppTheme.extended.error
    ControlStatus.Confirmed -> AppTheme.extended.success
    ControlStatus.Idle -> MaterialTheme.colorScheme.primary
}

@Composable
internal fun ConnectionChip(state: HomeUiState) {
    val dot = when (state.connection.kind) {
        ConnectionKind.Connected -> AppTheme.extended.success
        ConnectionKind.Connecting -> AppTheme.extended.pending
        else -> AppTheme.extended.error
    }
    AppCard(Modifier.fillMaxWidth().testTag("connection-chip")) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(AppIcons.Bluetooth, null, Modifier.size(24.dp), tint = if (state.connection.kind == ConnectionKind.Connected) MaterialTheme.colorScheme.primary else AppTheme.extended.textSecondary)
            Text(state.connection.label, Modifier.weight(1f).padding(horizontal = 14.dp), style = MaterialTheme.typography.bodyLarge)
            Box(Modifier.size(12.dp).clip(CircleShape).background(dot))
        }
    }
}

/** Aviso único: dados desatualizados ou motivo (sem jargão) de os controles estarem desabilitados. */
@Composable
internal fun Banner(state: HomeUiState) {
    val reason = state.notice
    val connected = state.connection.kind != ConnectionKind.Disconnected && state.connection.kind != ConnectionKind.Connecting
    if (!connected || reason == null) return
    val tag = if (state.telemetryStale) "stale-banner" else "reason-banner"
    Surface(
        Modifier.fillMaxWidth().testTag(tag), shape = RoundedCornerShape(14.dp),
        color = AppTheme.extended.pending.copy(alpha = 0.16f),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(AppIcons.Alert, null, Modifier.size(20.dp), tint = AppTheme.extended.pending)
            Text(reason, Modifier.padding(start = 10.dp), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
internal fun InfoRow(state: HomeUiState) {
    val large = LocalDensity.current.fontScale > 1.3f
    val items = listOf(
        Triple("info-battery", AppIcons.Battery to AppTheme.extended.success, state.battery to "Bateria"),
        Triple("info-speed", AppIcons.Speed to MaterialTheme.colorScheme.primary, state.speed to "Velocidade"),
        Triple("info-total", AppIcons.Pin to MaterialTheme.colorScheme.primary, state.total to "Total"),
    )
    if (large) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items.forEach { (tag, ic, vl) -> InfoCard(Modifier.fillMaxWidth(), tag, ic.first, vl.first, vl.second, ic.second) }
        }
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items.forEach { (tag, ic, vl) -> InfoCard(Modifier.weight(1f), tag, ic.first, vl.first, vl.second, ic.second) }
        }
    }
}

@Composable
private fun InfoCard(modifier: Modifier, tag: String, icon: ImageVector, value: String, label: String, tint: Color) {
    AppCard(modifier.testTag(tag)) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
            Icon(icon, null, Modifier.size(24.dp), tint = tint)
            Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(label, style = MaterialTheme.typography.bodySmall, color = AppTheme.extended.textSecondary)
        }
    }
}

@Composable
private fun ControlMessage(c: ControlUi) {
    if (c.message != null) Text(c.message, style = MaterialTheme.typography.bodySmall, color = AppTheme.extended.error)
}

@Composable
private fun toggleColors(c: ControlUi) = appSwitchColors(statusColor(c))

/** Cores do toggle como valores puros (testáveis); [appSwitchColors] só as repassa ao Material. */
internal data class SwitchPalette(
    val checkedTrack: Color, val checkedThumb: Color, val checkedBorder: Color,
    val uncheckedTrack: Color, val uncheckedThumb: Color, val uncheckedBorder: Color,
)

internal fun switchPalette(checked: Color, scheme: androidx.compose.material3.ColorScheme, ext: br.dev.evaldo.meuscooter.ui.theme.ExtendedColors) = SwitchPalette(
    checkedTrack = checked, checkedThumb = scheme.background, checkedBorder = checked,
    uncheckedTrack = ext.textSecondary.copy(alpha = 0.55f), uncheckedThumb = scheme.onSurface, uncheckedBorder = Color.Transparent,
)

/** Desligado com trilho claro e contraste visível (como o toggle do Samsung); ligado na cor de estado. */
@Composable
internal fun appSwitchColors(checked: Color = MaterialTheme.colorScheme.primary): androidx.compose.material3.SwitchColors {
    val p = switchPalette(checked, MaterialTheme.colorScheme, AppTheme.extended)
    return SwitchDefaults.colors(
        checkedTrackColor = p.checkedTrack, checkedThumbColor = p.checkedThumb, checkedBorderColor = p.checkedBorder,
        uncheckedTrackColor = p.uncheckedTrack, uncheckedThumbColor = p.uncheckedThumb, uncheckedBorderColor = p.uncheckedBorder,
    )
}

@Composable
internal fun ToggleRow(
    tag: String, icon: ImageVector, title: String, subtitle: String?, checked: Boolean, c: ControlUi, onChange: (Boolean) -> Unit,
) {
    AppCard(
        Modifier.fillMaxWidth().testTag(tag)
            .toggleable(checked, c.enabled, Role.Switch) { onChange(it) },
    ) {
        Row(Modifier.padding(horizontal = 18.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f).padding(horizontal = 18.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = AppTheme.extended.textSecondary)
                ControlMessage(c)
            }
            Switch(checked, null, enabled = c.enabled || c.status == ControlStatus.Pending, colors = toggleColors(c))
        }
    }
}

@Composable
internal fun RowScope.ToggleCard(
    tag: String, icon: ImageVector, title: String, subtitle: String, checked: Boolean, c: ControlUi, onChange: (Boolean) -> Unit,
) {
    AppCard(Modifier.weight(1f).fillMaxHeight().testTag(tag).toggleable(checked, c.enabled, Role.Switch) { onChange(it) }) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
                Text(title, Modifier.padding(start = 10.dp), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            }
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = AppTheme.extended.textSecondary)
            ControlMessage(c)
            Switch(checked, null, enabled = c.enabled || c.status == ControlStatus.Pending, colors = toggleColors(c))
        }
    }
}

/**
 * Escolha única. [stacked] = título acima e opções abaixo (modo); senão título à esquerda e opções à direita
 * (tração, como no FigCraft). Com fonte grande (> 1.3×) empilha e usa 2 colunas para não sobrepor.
 */
@Composable
internal fun ChoiceCard(
    tag: String, icon: ImageVector, title: String, subtitle: String, choice: ChoiceUi, onSelect: (Int) -> Unit, stacked: Boolean,
) {
    val large = LocalDensity.current.fontScale > 1.3f
    val header: @Composable (Modifier) -> Unit = { m ->
        Row(m, verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.padding(start = 14.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = AppTheme.extended.textSecondary)
            }
        }
    }
    AppCard(Modifier.fillMaxWidth().testTag("$tag-card")) {
        if (stacked || large) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                header(Modifier)
                Segments(tag, choice, onSelect, Modifier.fillMaxWidth(), columns = if (large) 2 else choice.options.size)
                ControlMessage(choice.control)
            }
        } else {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                header(Modifier.weight(1f))
                Segments(tag, choice, onSelect, Modifier.width(190.dp), columns = choice.options.size)
            }
        }
    }
}

@Composable
internal fun Segments(tag: String, choice: ChoiceUi, onSelect: (Int) -> Unit, modifier: Modifier, columns: Int) {
    if (choice.options.isEmpty()) return   // sem opções não há o que desenhar (e `chunked(0)` lançaria)
    Column(
        modifier.clip(RoundedCornerShape(50)).background(AppTheme.extended.track).padding(3.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        choice.options.chunked(columns.coerceAtLeast(1)).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                row.forEach { opt ->
                    val selected = opt.raw == choice.selectedRaw
                    Box(
                        Modifier.weight(1f).heightIn(min = 44.dp).clip(RoundedCornerShape(50))
                            .background(if (selected) statusColor(choice.control) else Color.Transparent)
                            .testTag("$tag-${opt.raw}")
                            .selectable(selected, choice.control.enabled, Role.RadioButton) { onSelect(opt.raw) }
                            .padding(horizontal = 2.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            opt.label, textAlign = TextAlign.Center, maxLines = 1, softWrap = false, style = MaterialTheme.typography.bodyMedium,
                            fontSize = 13.sp,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                            color = when {
                                selected -> MaterialTheme.colorScheme.onPrimary
                                choice.control.enabled -> MaterialTheme.colorScheme.onSurface
                                else -> AppTheme.extended.onDisabled
                            },
                        )
                    }
                }
            }
        }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun LimitCard(limit: LimitUi, onLimit: (Int) -> Unit) {
    var draft by remember(limit.kmh, limit.control.status, limit.control.enabled) { mutableFloatStateOf(limit.kmh.toFloat()) }
    val c = limit.control
    AppCard(Modifier.fillMaxWidth().testTag("limit")) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(AppIcons.Speed, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
                Text("Limite de velocidade", Modifier.weight(1f).padding(start = 12.dp), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(limit.valueText(draft.toInt()), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = statusColor(c))
            }
            val lo = LimitUi.RANGE.first
            val hi = LimitUi.RANGE.last
            fun step(d: Int) { val v = (draft.roundToInt() + d).coerceIn(lo, hi); if (v != draft.roundToInt()) { draft = v.toFloat(); onLimit(v) } }
            Row(verticalAlignment = Alignment.CenterVertically) {
                StepButton("limit-minus", "Diminuir limite", plus = false, enabled = c.enabled && draft.roundToInt() > lo, color = statusColor(c)) { step(-LIMIT_STEP) }
                Slider(
                    value = draft, onValueChange = { draft = it.roundToInt().toFloat() }, onValueChangeFinished = { onLimit(draft.roundToInt()) },
                    valueRange = lo.toFloat()..hi.toFloat(),
                    enabled = c.enabled,
                    colors = SliderDefaults.colors(thumbColor = statusColor(c), activeTrackColor = statusColor(c), inactiveTrackColor = AppTheme.extended.track),
                    thumb = { Box(Modifier.size(28.dp).clip(CircleShape).background(statusColor(c))) },
                    track = { st -> SliderDefaults.Track(st, drawStopIndicator = null, thumbTrackGapSize = 0.dp, colors = SliderDefaults.colors(activeTrackColor = statusColor(c), inactiveTrackColor = AppTheme.extended.track, disabledActiveTrackColor = AppTheme.extended.disabled, disabledInactiveTrackColor = AppTheme.extended.track)) },
                    modifier = Modifier.weight(1f).testTag("limit-slider"),
                )
                StepButton("limit-plus", "Aumentar limite", plus = true, enabled = c.enabled && draft.roundToInt() < hi, color = statusColor(c)) { step(LIMIT_STEP) }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(limit.minText, style = MaterialTheme.typography.bodySmall, color = AppTheme.extended.textSecondary)
                Text(limit.maxText, style = MaterialTheme.typography.bodySmall, color = AppTheme.extended.textSecondary)
            }
            ControlMessage(c)
        }
    }
}

private const val LIMIT_STEP = 5

/** Botão redondo "+"/"−" (48 dp de área de toque) para ajuste de 5 em 5 km/h. */
@Composable
private fun StepButton(tag: String, description: String, plus: Boolean, enabled: Boolean, color: Color, onClick: () -> Unit) {
    val tint = if (enabled) color else AppTheme.extended.onDisabled
    IconButton(onClick, Modifier.size(48.dp).testTag(tag).semantics { contentDescription = description }, enabled = enabled) {
        Canvas(Modifier.size(28.dp)) {
            drawCircle(tint)
            val w = 2.dp.toPx(); val r = size.minDimension * 0.22f; val c = center
            drawLine(Color.White, Offset(c.x - r, c.y), Offset(c.x + r, c.y), w)
            if (plus) drawLine(Color.White, Offset(c.x, c.y - r), Offset(c.x, c.y + r), w)
        }
    }
}

/** Botão principal: retangular largo, fixo no rodapé, respeita barras do sistema; altura ≥ 56 dp (cresce com a fonte). */
@Composable
internal fun MainButton(b: MainButtonUi, onClick: () -> Unit) {
    val ext = AppTheme.extended
    val scheme = MaterialTheme.colorScheme
    val (bg, fg) = if (!b.enabled && b.kind != MainButtonKind.Pending && b.kind != MainButtonKind.Confirmed) ext.disabled to ext.onDisabled else when (b.kind) {
        MainButtonKind.Unlock -> scheme.primary to scheme.onPrimary
        MainButtonKind.Search, MainButtonKind.Connecting, MainButtonKind.Moving -> ext.disabled to ext.onDisabled
        MainButtonKind.Pending -> ext.pending to ext.onColor
        MainButtonKind.Confirmed -> ext.success to ext.onColor
        MainButtonKind.Retry -> ext.error to ext.onColor
        MainButtonKind.Lock -> scheme.surface to scheme.primary
    }
    val icon = when (b.kind) {
        MainButtonKind.Search, MainButtonKind.Unlock -> AppIcons.Unlock
        MainButtonKind.Lock, MainButtonKind.Moving -> AppIcons.Lock
        MainButtonKind.Confirmed -> AppIcons.Check
        MainButtonKind.Retry -> AppIcons.Alert
        else -> null
    }
    Box(Modifier.fillMaxWidth().background(scheme.background).navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp)) {
    Surface(
        onClick = onClick, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("main-button"), enabled = b.enabled, shape = RoundedCornerShape(20.dp), color = bg, contentColor = fg,
        border = if (b.kind == MainButtonKind.Lock) BorderStroke(2.dp, scheme.primary) else null,
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), Arrangement.Center, Alignment.CenterVertically) {
            if (b.kind == MainButtonKind.Pending || b.kind == MainButtonKind.Connecting) {
                CircularProgressIndicator(Modifier.size(24.dp), color = fg, strokeWidth = 3.dp)
            } else if (icon != null) {
                Icon(icon, null, Modifier.size(28.dp))
            }
            Text(b.label, Modifier.padding(start = 14.dp), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        }
    }
    }
}

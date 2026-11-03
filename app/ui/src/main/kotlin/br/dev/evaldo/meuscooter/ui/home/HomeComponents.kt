package br.dev.evaldo.meuscooter.ui.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
private fun AppCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
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
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(AppIcons.Bluetooth, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
            Text(state.connection.label, Modifier.weight(1f).padding(horizontal = 14.dp), style = MaterialTheme.typography.bodyLarge)
            Box(Modifier.size(12.dp).clip(CircleShape).background(dot))
        }
    }
}

/** Aviso único: dados desatualizados ou motivo (sem jargão) de os controles estarem desabilitados. */
@Composable
internal fun Banner(state: HomeUiState) {
    val reason = state.mode.control.disabledReason
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
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        InfoCard(AppIcons.Battery, state.battery, "Bateria", AppTheme.extended.success)
        InfoCard(AppIcons.Speed, state.speed, "Velocidade", MaterialTheme.colorScheme.primary)
        InfoCard(AppIcons.Pin, state.total, "Total", MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun RowScope.InfoCard(icon: ImageVector, value: String, label: String, tint: Color) {
    AppCard(Modifier.weight(1f)) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
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
private fun toggleColors(c: ControlUi) = SwitchDefaults.colors(
    checkedTrackColor = statusColor(c), checkedThumbColor = MaterialTheme.colorScheme.background,
    checkedBorderColor = statusColor(c),
)

@Composable
internal fun ToggleRow(
    tag: String, icon: ImageVector, title: String, subtitle: String?, checked: Boolean, c: ControlUi, onChange: (Boolean) -> Unit,
) {
    AppCard(
        Modifier.fillMaxWidth().testTag(tag)
            .toggleable(checked, c.enabled, Role.Switch) { onChange(it) },
    ) {
        Row(Modifier.padding(horizontal = 18.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
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
    AppCard(Modifier.weight(1f).testTag(tag).toggleable(checked, c.enabled, Role.Switch) { onChange(it) }) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
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

/** Escolha única. Com fonte grande (> 1.3×) as opções empilham em 2 colunas para não sobrepor. */
@Composable
internal fun ChoiceCard(
    tag: String, icon: ImageVector, title: String, subtitle: String, choice: ChoiceUi, onSelect: (Int) -> Unit, stacked: Boolean,
) {
    val large = LocalDensity.current.fontScale > 1.3f
    AppCard(Modifier.fillMaxWidth().testTag("$tag-card")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
                Column(Modifier.padding(start = 14.dp)) {
                    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = AppTheme.extended.textSecondary)
                }
            }
            val rows = if (large) choice.options.chunked(2) else listOf(choice.options)
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(50)).background(AppTheme.extended.track).padding(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                rows.forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        row.forEach { opt ->
                            val selected = opt.raw == choice.selectedRaw
                            val bg = when {
                                !selected -> Color.Transparent
                                else -> statusColor(choice.control)
                            }
                            Box(
                                Modifier.weight(1f).heightIn(min = 48.dp).clip(RoundedCornerShape(50)).background(bg)
                                    .testTag("$tag-${opt.raw}")
                                    .selectable(selected, choice.control.enabled, Role.RadioButton) { onSelect(opt.raw) }
                                    .padding(horizontal = 6.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    opt.label, textAlign = TextAlign.Center, maxLines = 2,
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
            ControlMessage(choice.control)
        }
    }
}

@Composable
internal fun LimitCard(limit: LimitUi, onLimit: (Int) -> Unit) {
    var draft by remember(limit.kmh) { mutableFloatStateOf(limit.kmh.toFloat()) }
    val c = limit.control
    AppCard(Modifier.fillMaxWidth().testTag("limit")) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(AppIcons.Speed, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
                Text("Limite de velocidade", Modifier.weight(1f).padding(start = 12.dp), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text("${draft.toInt()} km/h", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = statusColor(c))
            }
            Slider(
                value = draft, onValueChange = { draft = it }, onValueChangeFinished = { onLimit(draft.toInt()) },
                valueRange = LimitUi.RANGE.first.toFloat()..LimitUi.RANGE.last.toFloat(),
                steps = LimitUi.RANGE.last - LimitUi.RANGE.first - 1, enabled = c.enabled,
                colors = SliderDefaults.colors(thumbColor = statusColor(c), activeTrackColor = statusColor(c), inactiveTrackColor = AppTheme.extended.track),
                modifier = Modifier.testTag("limit-slider"),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("${LimitUi.RANGE.first} km/h", style = MaterialTheme.typography.bodySmall, color = AppTheme.extended.textSecondary)
                Text("${LimitUi.RANGE.last} km/h", style = MaterialTheme.typography.bodySmall, color = AppTheme.extended.textSecondary)
            }
            ControlMessage(c)
        }
    }
}

/** Botão principal: retangular largo, fixo no rodapé, respeita barras do sistema; altura ≥ 56 dp (cresce com a fonte). */
@Composable
internal fun MainButton(b: MainButtonUi, onClick: () -> Unit) {
    val ext = AppTheme.extended
    val scheme = MaterialTheme.colorScheme
    val (bg, fg) = when (b.kind) {
        MainButtonKind.Search, MainButtonKind.Unlock -> scheme.primary to scheme.onPrimary
        MainButtonKind.Connecting, MainButtonKind.Moving -> ext.disabled to ext.onDisabled
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
    Box(Modifier.fillMaxWidth().background(scheme.background).navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp)) {
    Surface(
        onClick = onClick, modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).testTag("main-button"), enabled = b.enabled, shape = RoundedCornerShape(20.dp), color = bg, contentColor = fg,
        border = if (b.kind == MainButtonKind.Lock) BorderStroke(2.dp, scheme.primary) else null,
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), Arrangement.Center, Alignment.CenterVertically) {
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

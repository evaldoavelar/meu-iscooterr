package br.dev.evaldo.meuscooter.ui.telemetry

import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.width
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.vector.ImageVector
import br.dev.evaldo.meuscooter.ui.theme.FitText
import br.dev.evaldo.meuscooter.ui.theme.IconTile
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.layout.onSizeChanged
import br.dev.evaldo.meuscooter.domain.TripRecState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import br.dev.evaldo.meuscooter.ui.TelemetryUiState
import br.dev.evaldo.meuscooter.ui.home.AppCard
import br.dev.evaldo.meuscooter.ui.theme.AppIcons
import br.dev.evaldo.meuscooter.ui.theme.AppTheme

/** Telemetria (design/telemetry.py). Zerar viagem é local: não envia nada ao patinete. */
@Composable
fun TelemetryScreen(
    state: TelemetryUiState, onBack: () -> Unit, onZeroTrip: () -> Unit, onOpenHistory: () -> Unit,
    onPlayPause: () -> Unit = {}, onStop: () -> Unit = {}, onReset: () -> Unit = {},
    onSpeedSource: (br.dev.evaldo.meuscooter.domain.SpeedSource) -> Unit = {},
) {
    var menu by rememberSaveable { mutableStateOf(false) }
    var confirm by rememberSaveable { mutableStateOf(false) }
    var confirmReset by rememberSaveable { mutableStateOf(false) }
    var barHeightPx by remember { mutableIntStateOf(0) }
    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
      // Barra fixa no rodapé sobre o conteúdo (que rola por trás, com folga final igual à altura da barra).
      Box(Modifier.fillMaxSize()) {
        // Cabeçalho FIXO (Voltar e ⋮ sempre alcançáveis, inclusive com fonte 2,0); só o conteúdo rola.
        Column(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding()).padding(horizontal = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack, Modifier.size(48.dp)) {
                    Icon(AppIcons.Chevron, contentDescription = "Voltar", Modifier.graphicsLayer { scaleX = -1f })
                }
                Text("Telemetria", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                Box {
                    IconButton(onClick = { menu = true }, Modifier.size(48.dp)) { Icon(AppIcons.More, contentDescription = "Mais opções") }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem(text = { Text("Zerar viagem") }, onClick = { menu = false; confirm = true })
                        DropdownMenuItem(text = { Text("Ver histórico") }, onClick = { menu = false; onOpenHistory() })
                    }
                }
            }
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (state.staleNote != null) Text(state.staleNote, Modifier.fillMaxWidth().testTag("stale-note"), style = MaterialTheme.typography.bodyMedium, color = AppTheme.extended.textSecondary, textAlign = TextAlign.Center)
            SpeedGauge(state.speedText, state.speedUnit, state.gaugeMaxText, state.gaugeFraction, source = state.speedSource, onSourceChange = onSpeedSource)
            BatteryPill(state.batteryText, Modifier.align(Alignment.CenterHorizontally).padding(top = 4.dp))
            AppCard(Modifier.fillMaxWidth().testTag("duration-card")) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    IconTile(AppIcons.Clock, "clock")
                    Column(Modifier.weight(1f)) {
                        FitText("Duração", style = MaterialTheme.typography.bodySmall, color = AppTheme.extended.textSecondary)
                        FitText(state.durationText, Modifier.testTag("duration-value"), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    }
                    Text(
                        state.recStateText, Modifier.testTag("rec-state"), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold,
                        color = when (state.recState) { TripRecState.Running -> AppTheme.extended.success; TripRecState.Paused -> AppTheme.extended.pending; TripRecState.Stopped -> AppTheme.extended.textSecondary },
                    )
                }
            }
            AppCard(Modifier.fillMaxWidth().testTag("power-card")) {
                val noData = state.powerNowFraction == null
                val stacked = LocalDensity.current.fontScale >= 1.75f   // fonte 2,0: gráfico em largura total abaixo do texto, sem corte
                val texts = @Composable { mod: Modifier ->
                    Column(mod) {
                        FitText("Consumo em tempo real", style = MaterialTheme.typography.bodySmall, color = AppTheme.extended.textSecondary)
                        FitText(state.powerText, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = if (noData) AppTheme.extended.textSecondary else MaterialTheme.colorScheme.onSurface)
                        val sub = if (noData) NO_POWER_DATA else state.efficiencyText
                        if (sub.isNotEmpty()) FitText(sub, style = MaterialTheme.typography.bodySmall, color = AppTheme.extended.textSecondary)
                    }
                }
                if (stacked) {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        texts(Modifier.fillMaxWidth())
                        PowerBars(state.powerBars, state.powerNowFraction, Modifier.fillMaxWidth(), chartHeight = 84.dp)
                    }
                } else {
                    Row(
                        Modifier.fillMaxWidth().padding(14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        texts(Modifier.weight(1f))
                        PowerBars(state.powerBars, state.powerNowFraction, Modifier.width(152.dp), chartHeight = 44.dp)
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Stat(Modifier.weight(1f), "Corrente", state.currentText, "A", icon = AppIcons.Pulse to "pulse")
                Stat(Modifier.weight(1f), "Tensão", state.voltageText, "V", icon = AppIcons.Bolt to "bolt")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Stat(Modifier.weight(1f), "Temp. motor 1", state.motor1Text, "°C", icon = AppIcons.Thermo to "thermo")
                Stat(Modifier.weight(1f), "Temp. motor 2", state.motor2Text, "°C", state.motor2Note, "motor2-note", icon = AppIcons.Thermo to "thermo")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Stat(Modifier.weight(1f), "Odômetro atual", state.tripOdometerText, icon = AppIcons.Route to "route")
                Stat(Modifier.weight(1f), "Odômetro total", state.totalOdometerText, icon = AppIcons.Speed to "speed")
            }
            AppCard(Modifier.fillMaxWidth().testTag("trip-card")) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Viagem atual", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Stat(Modifier.weight(1f), "Velocidade média", state.avgText, icon = AppIcons.Speed to "speed")
                        Stat(Modifier.weight(1f), "Velocidade máxima", state.maxText, icon = AppIcons.Trend to "trend")
                    }
                    Column(Modifier.fillMaxWidth().testTag("altitude-chart")) {
                        FitText("Altimetria", style = MaterialTheme.typography.bodySmall, color = AppTheme.extended.textSecondary)
                        FitText(state.altitudeText, if (state.altitudeBars.isEmpty()) Modifier.testTag("altitude-empty") else Modifier, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    }
                    if (state.altitudeBars.isNotEmpty()) AltitudeBars(state)
                }
            }
            Spacer(Modifier.height(with(androidx.compose.ui.platform.LocalDensity.current) { barHeightPx.toDp() }))
            }
        }
        TripControlsBar(
            state.recState, onReset = { confirmReset = true }, onPlayPause = onPlayPause, onStop = onStop,
            modifier = Modifier.align(Alignment.BottomCenter).onSizeChanged { barHeightPx = it.height },
        )
      }
    }
    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("Descartar a viagem atual?") },
            text = { Text("A viagem em curso será descartada, sem salvar no histórico, e o contador volta a 00:00 parado. Nada é enviado ao patinete.") },
            confirmButton = { TextButton({ confirmReset = false; onReset() }, Modifier.testTag("reset-confirm")) { Text("Descartar") } },
            dismissButton = { TextButton({ confirmReset = false }, Modifier.testTag("reset-cancel")) { Text("Cancelar") } },
            modifier = Modifier.testTag("reset-dialog"),
        )
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Zerar a viagem atual?") },
            text = { Text("Ela será salva no histórico e o contador recomeça do zero. Nada é enviado ao patinete.") },
            confirmButton = { TextButton({ confirm = false; onZeroTrip() }) { Text("Zerar") } },
            dismissButton = { TextButton({ confirm = false }) { Text("Cancelar") } },
        )
    }
}

/** Perfil de altitude (barras), só com amostras: sem dados nenhuma leitura é inventada (o cartão mostra "Sem leitura"). */
@Composable
private fun AltitudeBars(state: TelemetryUiState) {
    val primary = MaterialTheme.colorScheme.primary
    Canvas(Modifier.fillMaxWidth().height(48.dp).testTag("altitude-bars")) {
        val n = state.altitudeBars.size
        val gap = 3.dp.toPx()
        val barW = ((size.width - gap * (n - 1)) / n).coerceAtLeast(1f)
        val floor = 3.dp.toPx()
        for (i in 0 until n) {
            val h = floor + state.altitudeBars[i] * (size.height - floor)
            drawRoundRect(primary, Offset(i * (barW + gap), size.height - h), Size(barW, h), CornerRadius(barW / 2))
        }
    }
}

/** Texto do subtítulo do cartão de consumo sem leitura de potência (design/s43.py). */
internal const val NO_POWER_DATA = "Sem dado do patinete"

/**
 * Gráfico de consumo dos últimos 30 s em ESCALA ABSOLUTA FIXA 0–750 W (ADR 0013, adendo S43; design/s43.py):
 * altura da barra = W/750 (já vem em 0..1), linha tracejada = valor atual, fio claro no teto com rótulo "750 W".
 * [bars] vazio = sem dado: sem barras, só a linha de base tênue.
 */
@Composable
private fun PowerBars(bars: List<Float>, nowFraction: Float?, modifier: Modifier = Modifier, chartHeight: androidx.compose.ui.unit.Dp = 44.dp) {
    val primary = MaterialTheme.colorScheme.primary
    val track = AppTheme.extended.track
    val secondary = AppTheme.extended.textSecondary
    val hasData = nowFraction != null && bars.isNotEmpty()   // sem dado: sem barras nem linha de referência
    val current = nowFraction?.coerceIn(0f, 1f) ?: 0f   // potência ATUAL (W/750), não a média da última fatia

    // A11y: uma leitura única do gráfico, qualitativa (o valor em W já é lido no cartão): posição da barra atual na escala fixa.
    val summary = when {
        !hasData -> "Consumo dos últimos 30 s: sem dados"
        current >= 0.85f -> "Consumo dos últimos 30 s, escala fixa de 0 a 750 W: agora próximo do teto"
        current <= 0.4f -> "Consumo dos últimos 30 s, escala fixa de 0 a 750 W: agora bem abaixo do teto"
        else -> "Consumo dos últimos 30 s, escala fixa de 0 a 750 W: agora abaixo do teto"
    }
    Column(
        modifier.testTag("power-bars-chart").clearAndSetSemantics { contentDescription = summary },
        horizontalAlignment = Alignment.Start,
    ) {
        Text(
            "750 W", fontSize = 10.sp, color = secondary, textAlign = TextAlign.End,
            modifier = Modifier.fillMaxWidth().testTag("power-bars-ceiling"),
        )
        Canvas(Modifier.fillMaxWidth().height(chartHeight).testTag("power-bars")) {
            val n = 24
            val barW = size.width * 4f / 152f
            val step = (size.width - barW) / (n - 1)
            val corner = barW / 2
            val minH = 2.dp.toPx()
            val maxH = size.height
            val w = size.width
            drawLine(track, Offset(0f, 0f), Offset(w, 0f), strokeWidth = 1.dp.toPx())   // fio do teto (750 W)
            if (hasData) {
                for (i in 0 until n) {
                    val frac = bars.getOrElse(i) { 0f }.coerceIn(0f, 1f)
                    val h = maxOf(minH, frac * maxH)
                    drawRoundRect(if (frac > 0f) primary else track, Offset(i * step, size.height - h), Size(barW, h), CornerRadius(corner, corner))
                }
            }
            if (hasData) {
                val y = size.height - current * maxH
                val dash = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx()))
                drawLine(secondary, Offset(0f, y), Offset(w, y), strokeWidth = 1.dp.toPx(), pathEffect = dash)
            } else {
                drawLine(track, Offset(0f, size.height), Offset(w, size.height), strokeWidth = 1.dp.toPx())   // base tênue
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "últimos 30 s",
            fontSize = 10.sp,
            color = secondary,
            modifier = Modifier.testTag("power-bars-legend"),
        )
    }
}

@Composable
private fun Stat(modifier: Modifier, label: String, value: String, unit: String? = null, note: String? = null, noteTag: String = "", icon: Pair<ImageVector, String>? = null, valueTag: String = "") {
    AppCard(modifier) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            icon?.let { IconTile(it.first, it.second) }
            Column(Modifier.weight(1f)) {
                FitText(label, style = MaterialTheme.typography.bodySmall, color = AppTheme.extended.textSecondary)
                FitText(value + (unit?.let { " $it" } ?: ""), if (valueTag.isEmpty()) Modifier else Modifier.testTag(valueTag), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                if (note != null) Text(note, if (noteTag.isEmpty()) Modifier else Modifier.testTag(noteTag), style = MaterialTheme.typography.bodySmall, color = AppTheme.extended.textSecondary)
            }
        }
    }
}

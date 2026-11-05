package br.dev.evaldo.meuscooter.ui.telemetry

import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.Canvas
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import br.dev.evaldo.meuscooter.ui.TelemetryUiState
import br.dev.evaldo.meuscooter.ui.home.AppCard
import br.dev.evaldo.meuscooter.ui.theme.AppIcons
import br.dev.evaldo.meuscooter.ui.theme.AppTheme

/** Telemetria (design/telemetry.py). Zerar viagem é local: não envia nada ao patinete. */
@Composable
fun TelemetryScreen(state: TelemetryUiState, onBack: () -> Unit, onZeroTrip: () -> Unit, onOpenHistory: () -> Unit) {
    var menu by rememberSaveable { mutableStateOf(false) }
    var confirm by rememberSaveable { mutableStateOf(false) }
    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        // Cabeçalho FIXO (Voltar e ⋮ sempre alcançáveis, inclusive com fonte 2,0); só o conteúdo rola.
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
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
            SpeedGauge(state.speedText, state.speedUnit, state.gaugeMaxText, state.gaugeFraction)
            Row(Modifier.height(IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Stat(Modifier.weight(1f).fillMaxHeight(), "Bateria", state.batteryText, icon = AppIcons.Battery to "battery")
                AppCard(Modifier.weight(1f).fillMaxHeight().testTag("power-card")) {
                    Column(Modifier.padding(12.dp)) {
                        FitText("Consumo em tempo real", style = MaterialTheme.typography.bodySmall, color = AppTheme.extended.textSecondary)
                        FitText(state.powerText, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        if (state.efficiencyText.isNotEmpty()) Text(state.efficiencyText, style = MaterialTheme.typography.bodySmall, color = AppTheme.extended.textSecondary)
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
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Stat(Modifier.weight(1f), "Duração", state.durationText, icon = AppIcons.Clock to "clock")
                        Stat(Modifier.weight(1f).testTag("altitude-chart"), "Altimetria", state.altitudeText, icon = AppIcons.Mountain to "mountain", valueTag = if (state.altitudeBars.isEmpty()) "altitude-empty" else "")
                    }
                    if (state.altitudeBars.isNotEmpty()) AltitudeBars(state)
                }
            }
            }
        }
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

@Composable
private fun Stat(modifier: Modifier, label: String, value: String, unit: String? = null, note: String? = null, noteTag: String = "", icon: Pair<ImageVector, String>? = null, valueTag: String = "") {
    AppCard(modifier) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            icon?.let { IconTile(it.first, it.second) }
            Column(Modifier.weight(1f)) {
                FitText(label, style = MaterialTheme.typography.bodySmall, color = AppTheme.extended.textSecondary)
                FitText(value + (unit?.let { " $it" } ?: ""), Modifier.testTag(valueTag), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                if (note != null) Text(note, Modifier.testTag(noteTag), style = MaterialTheme.typography.bodySmall, color = AppTheme.extended.textSecondary)
            }
        }
    }
}

package br.dev.evaldo.meuscooter.ui.telemetry

import androidx.compose.foundation.layout.Arrangement
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
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
            SpeedGauge(state.speedText, state.speedUnit, state.gaugeMaxText, state.gaugeFraction)
            Stat(Modifier.fillMaxWidth(), "Bateria", state.batteryText)
            AppCard(Modifier.fillMaxWidth().testTag("power-card")) {
                Column(Modifier.padding(14.dp)) {
                    Text("Consumo em tempo real", style = MaterialTheme.typography.bodySmall, color = AppTheme.extended.textSecondary)
                    Text(state.powerText, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    Text(state.efficiencyText, style = MaterialTheme.typography.bodySmall, color = AppTheme.extended.textSecondary)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Stat(Modifier.weight(1f), "Corrente", state.currentText, "A")
                Stat(Modifier.weight(1f), "Tensão", state.voltageText, "V")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Stat(Modifier.weight(1f), "Temp. motor 1", state.motor1Text, "°C")
                Stat(Modifier.weight(1f), "Temp. motor 2", state.motor2Text, "°C", state.motor2Note, "motor2-note")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Stat(Modifier.weight(1f), "Odômetro atual", state.tripOdometerText)
                Stat(Modifier.weight(1f), "Odômetro total", state.totalOdometerText)
            }
            AppCard(Modifier.fillMaxWidth().testTag("trip-card")) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Viagem atual", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Stat(Modifier.weight(1f), "Velocidade média", state.avgText)
                        Stat(Modifier.weight(1f), "Velocidade máxima", state.maxText)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Stat(Modifier.weight(1f), "Duração", state.durationText)
                        Stat(Modifier.weight(1f), "Altimetria", state.altitudeText)
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

@Composable
private fun Stat(modifier: Modifier, label: String, value: String, unit: String? = null, note: String? = null, noteTag: String = "") {
    AppCard(modifier) {
        Column(Modifier.padding(12.dp)) {
            Text(label, style = MaterialTheme.typography.bodySmall, color = AppTheme.extended.textSecondary)
            Text(value + (unit?.let { " $it" } ?: ""), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            if (note != null) Text(note, Modifier.testTag(noteTag), style = MaterialTheme.typography.bodySmall, color = AppTheme.extended.textSecondary)
        }
    }
}

package br.dev.evaldo.meuscooter.ui.home

import br.dev.evaldo.meuscooter.domain.AppDestination
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import br.dev.evaldo.meuscooter.ui.HomeUiState
import br.dev.evaldo.meuscooter.ui.R
import br.dev.evaldo.meuscooter.ui.theme.AppIcons

/**
 * Home conforme o FigCraft aprovado. O botão principal fica em `bottomBar` (fixo, áreas seguras);
 * só o conteúdo rola, então a rolagem nunca o oculta.
 */
@Composable
fun HomeScreen(state: HomeUiState, actions: HomeActions, modifier: Modifier = Modifier) {
    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = { MainButton(state.mainButton, actions.onMainButton) },
    ) { padding ->
        Column(
            Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            TopBar(actions.onOpen)
            ConnectionChip(state)
            Banner(state)
            InfoRow(state)
            ToggleRow("headlight", AppIcons.Light, "Farol", null, state.headlight.checked, state.headlight.control, actions.onHeadlight)
            ChoiceCard("motors", AppIcons.Motor, "Tração", "Acionamento do motor", state.motors, actions.onMotors, stacked = false)
            ChoiceCard("mode", AppIcons.Speed, "Modo de velocidade", "Ajusta a resposta e a velocidade máxima", state.mode, actions.onMode, stacked = true)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ToggleCard("cruise", AppIcons.Cruise, "Cruise", "Mantém a velocidade constante", state.cruise.checked, state.cruise.control, actions.onCruise)
                ToggleCard("zero-start", AppIcons.Rocket, "Partida do zero", "Aceleração sem empurrar", state.zeroStart.checked, state.zeroStart.control, actions.onZeroStart)
            }
            LimitCard(state.limit, actions.onLimit)
            Spacer(Modifier.size(4.dp))
        }
    }
}

@Composable
private fun TopBar(onOpen: (AppDestination) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(top = 0.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            stringResource(R.string.app_name), Modifier.weight(1f),
            style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
        )
        Box {
            IconButton(onClick = { open = true }, Modifier.size(48.dp)) {
                Icon(AppIcons.More, contentDescription = "Mais opções")
            }
            DropdownMenu(open, { open = false }) {
                listOf(
                    AppDestination.Telemetry to "Telemetria",
                    AppDestination.Settings to "Preferências",
                    AppDestination.History to "Histórico de viagens",
                ).forEach { (dest, label) ->
                    DropdownMenuItem(text = { Text(label) }, onClick = { open = false; onOpen(dest) })
                }
            }
        }
    }
}

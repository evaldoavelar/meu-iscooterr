package br.dev.evaldo.meuscooter.ui.home

import br.dev.evaldo.meuscooter.domain.AppDestination
import br.dev.evaldo.meuscooter.ui.label
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.height
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
fun HomeScreen(state: HomeUiState, actions: HomeActions, modifier: Modifier = Modifier, pinVault: PinVaultUi = PinVaultUi()) {
    // Não usa rememberSaveable: nada do PIN sobrevive a recriação/processo.
    var askPin by remember { mutableStateOf(false) }
    // Com PIN salvo, "Desbloquear" pede a biometria primeiro; o teclado só abre se ela for cancelada/falhar (keypadRequest sobe).
    var seenKeypadRequest by remember { mutableIntStateOf(pinVault.keypadRequest) }
    LaunchedEffect(pinVault.keypadRequest) {
        if (pinVault.keypadRequest != seenKeypadRequest) { seenKeypadRequest = pinVault.keypadRequest; askPin = true }
    }
    if (askPin && state.mainButton.asksPin && state.mainButton.enabled) {
        PinDialog(
            vault = pinVault,
            onConfirm = { pin, save -> askPin = false; if (save) actions.onUnlockPinAndSave(pin) else actions.onUnlockPin(pin) },
            onUseSaved = { askPin = false; actions.onUseSavedPin() },
            onForget = actions.onForgetPin,
            onDismiss = { askPin = false },
        )
    } else if (askPin) {
        askPin = false
    }
    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            MainButton(state.mainButton) {
                if (!state.mainButton.asksPin) actions.onMainButton()
                else if (pinVault.available && pinVault.saved) actions.onUseSavedPin()
                else askPin = true
            }
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            TopBar(actions.onOpen)
            ConnectionChip(state, actions.onConnectionChip, actions.onConnectionMenu)
            Banner(state)
            pinVault.notice?.let { PinNotice(it) }
            InfoRow(state)
            ToggleRow("headlight", AppIcons.Light, "Farol", null, state.headlight.checked, state.headlight.control, actions.onHeadlight)
            ChoiceCard("motors", AppIcons.Motor, "Tração", "Acionamento do motor", state.motors, actions.onMotors, stacked = false)
            // Sem opções (BLE real: nomes dos modos ainda [HIPÓTESE]) não há controle a mostrar.
            ChoiceCard("mode", AppIcons.Speed, "Modo de velocidade", "Ajusta a resposta e a velocidade máxima", state.mode, actions.onMode, stacked = true)
            Row(Modifier.height(IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(12.dp)) {   // cards da mesma altura
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
                AppDestination.menu.forEach { dest ->
                    DropdownMenuItem(text = { Text(dest.label) }, onClick = { open = false; onOpen(dest) })
                }
            }
        }
    }
}

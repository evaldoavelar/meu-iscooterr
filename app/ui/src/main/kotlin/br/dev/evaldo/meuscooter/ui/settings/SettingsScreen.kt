package br.dev.evaldo.meuscooter.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import br.dev.evaldo.meuscooter.domain.Preferences
import br.dev.evaldo.meuscooter.domain.ThemeMode
import br.dev.evaldo.meuscooter.domain.Units
import br.dev.evaldo.meuscooter.ui.home.AppCard
import br.dev.evaldo.meuscooter.ui.theme.AppIcons
import br.dev.evaldo.meuscooter.ui.theme.AppTheme

data class SettingsActions(
    val onBack: () -> Unit = {},
    val onTheme: (ThemeMode) -> Unit = {},
    val onUnits: (Units) -> Unit = {},
    val onAutoReconnect: (Boolean) -> Unit = {},
    val onSaveRoute: (Boolean) -> Unit = {},
    val onLockWithPin: (Boolean) -> Unit = {},
    val onForget: () -> Unit = {},
    val onClearHistory: () -> Unit = {},
)

/** Air Lock (CMD 0x41, ADR 0011): o desbloqueio ao reconectar é do firmware `[HIPÓTESE]`, por isso o selo "Em teste". */
internal const val AIR_LOCK_SUBTITLE =
    "Marcado: o patinete deve bloquear sozinho quando o app desconectar e desbloquear ao reconectar. Desmarcado: não deve bloquear ao desconectar."

private enum class Confirm { None, Forget, Clear }

/** Preferências (design/screens.py `configuracoes`). Tudo local; "Esquecer" e "Apagar" não enviam nada ao patinete. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(prefs: Preferences, deviceName: String, actions: SettingsActions, airLockNotice: String? = null) {
    var confirm by rememberSaveable { mutableStateOf(Confirm.None) }
    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = actions.onBack, Modifier.size(48.dp)) {
                    Icon(AppIcons.Chevron, contentDescription = "Voltar", Modifier.graphicsLayer { scaleX = -1f })
                }
                Text("Preferências", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                Box(Modifier.size(48.dp))
            }
            AppCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Label("Tema")
                    Choice(listOf("Sistema", "Claro", "Escuro"), ThemeMode.entries.indexOf(prefs.theme)) { actions.onTheme(ThemeMode.entries[it]) }
                }
            }
            AppCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Label("Unidade de distância e velocidade")
                    Choice(listOf("km e km/h", "mi e mph"), Units.entries.indexOf(prefs.units)) { actions.onUnits(Units.entries[it]) }
                }
            }
            AppCard(Modifier.fillMaxWidth()) {
                Column {
                    SwitchRow("switch-reconnect", "Reconectar automaticamente", "Quando o patinete for ligado", prefs.autoReconnect, actions.onAutoReconnect)
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                    SwitchRow("switch-route", "Guardar trajeto das viagens", "Só neste celular, usa o GPS", prefs.saveRoute, actions.onSaveRoute)
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                    SwitchRow("switch-lock-pin", "Bloquear ao desconectar", AIR_LOCK_SUBTITLE, prefs.lockWithPin, actions.onLockWithPin, badge = "Em teste")
                    if (airLockNotice != null) Text(airLockNotice, Modifier.padding(horizontal = 16.dp).padding(bottom = 8.dp).testTag("air-lock-notice"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
            AppCard(Modifier.fillMaxWidth()) {
                Column {
                    TextRow(deviceName)
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                    TextRow("Esquecer este patinete", color = MaterialTheme.colorScheme.error) { confirm = Confirm.Forget }
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                    TextRow("Apagar histórico de viagens", color = MaterialTheme.colorScheme.error) { confirm = Confirm.Clear }
                }
            }
            AppCard(Modifier.fillMaxWidth()) {
                Column {
                    TextRow("Versão 0.1 (protótipo)")
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                    TextRow("Código aberto · licença MIT")
                }
            }
        }
    }
    when (confirm) {
        Confirm.Forget -> ConfirmDialog(
            "Esquecer este patinete?", "O app desconecta deste patinete e volta ao primeiro uso. Nada é enviado a ele.", "Esquecer",
            onConfirm = { confirm = Confirm.None; actions.onForget() }, onDismiss = { confirm = Confirm.None },
        )
        Confirm.Clear -> ConfirmDialog(
            "Apagar o histórico?", "As viagens salvas neste celular serão apagadas. Não afeta o patinete.", "Apagar",
            onConfirm = { confirm = Confirm.None; actions.onClearHistory() }, onDismiss = { confirm = Confirm.None },
        )
        Confirm.None -> Unit
    }
}

@Composable
private fun Label(text: String) = Text(text, style = MaterialTheme.typography.bodySmall, color = AppTheme.extended.textSecondary)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Choice(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed { i, label ->
            SegmentedButton(
                selected = i == selected, onClick = { onSelect(i) },
                shape = SegmentedButtonDefaults.itemShape(i, options.size), Modifier.heightIn(min = 48.dp),
            ) { Text(label) }
        }
    }
}

@Composable
private fun SwitchRow(tag: String, title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit, badge: String? = null) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag(tag).toggleable(checked, role = Role.Switch, onValueChange = onChange).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                if (badge != null) Text(badge, style = MaterialTheme.typography.labelSmall, color = AppTheme.extended.textSecondary)
            }
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = AppTheme.extended.textSecondary)
        }
        Switch(checked, onCheckedChange = null, colors = br.dev.evaldo.meuscooter.ui.home.appSwitchColors())
    }
}

@Composable
private fun TextRow(text: String, subtitle: String? = null, color: Color = Color.Unspecified, onClick: (() -> Unit)? = null) {
    Column(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).let { if (onClick != null) it.clickable(onClick = onClick) else it }.padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(text, style = MaterialTheme.typography.bodyLarge, color = color)
        subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = AppTheme.extended.textSecondary) }
    }
}

@Composable
private fun ConfirmDialog(title: String, text: String, confirmLabel: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text(title) }, text = { Text(text) },
        confirmButton = { TextButton(onConfirm) { Text(confirmLabel) } },
        dismissButton = { TextButton(onDismiss) { Text("Cancelar") } },
    )
}

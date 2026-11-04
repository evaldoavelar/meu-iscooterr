package br.dev.evaldo.meuscooter.ui.recovery

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import br.dev.evaldo.meuscooter.ui.StatusIconKind
import br.dev.evaldo.meuscooter.ui.StatusTone
import br.dev.evaldo.meuscooter.ui.StatusUi
import br.dev.evaldo.meuscooter.ui.theme.AppButton
import br.dev.evaldo.meuscooter.ui.theme.AppIcons
import br.dev.evaldo.meuscooter.ui.theme.AppTheme

/** Ação de rodapé da [StatusScreen]; a primeira da lista é a principal. */
data class StatusAction(val label: String, val tonal: Boolean = false, val onClick: () -> Unit)

/** Tela de estado do protótipo (design/screens.py `status`): selo com ícone, título, texto e botões. Conteúdo rola; botões ficam fixos. */
@Composable
fun StatusScreen(icon: ImageVector, tone: StatusTone, title: String, text: String, actions: List<StatusAction>) {
    val ext = AppTheme.extended
    val color = when (tone) {
        StatusTone.Pending -> ext.pending
        StatusTone.Error -> ext.error
        StatusTone.Primary -> MaterialTheme.colorScheme.primary
    }
    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            ) {
                Surface(Modifier.size(140.dp), shape = CircleShape, color = MaterialTheme.colorScheme.surface, border = BorderStroke(3.dp, color)) {
                    Icon(icon, contentDescription = null, Modifier.padding(38.dp), tint = color)
                }
                Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                Text(text, style = MaterialTheme.typography.bodyLarge, color = ext.textSecondary, textAlign = TextAlign.Center)
            }
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                actions.forEachIndexed { i, a -> AppButton(a.label, if (i == 0) "status-primary" else "status-secondary", a.tonal, onClick = a.onClick) }
            }
        }
    }
}

/** Recuperação: [StatusScreen] preenchida por [StatusUi]. Os cliques viram os callbacks (ações SIMULADAS nesta fase). */
@Composable
fun RecoveryScreen(ui: StatusUi, onPrimary: () -> Unit, onSecondary: () -> Unit = {}) {
    val icon = when (ui.icon) {
        StatusIconKind.Scooter -> AppIcons.Scooter
        StatusIconKind.Bluetooth -> AppIcons.Bluetooth
        StatusIconKind.Alert -> AppIcons.Alert
        StatusIconKind.Spin -> AppIcons.Spin
    }
    val actions = buildList {
        add(StatusAction(ui.primaryLabel, ui.primaryTonal, onPrimary))
        ui.secondaryLabel?.let { add(StatusAction(it, tonal = true, onClick = onSecondary)) }
    }
    StatusScreen(icon, ui.tone, ui.title, ui.text, actions)
}

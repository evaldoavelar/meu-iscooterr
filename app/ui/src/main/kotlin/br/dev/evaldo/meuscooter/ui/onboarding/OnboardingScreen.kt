package br.dev.evaldo.meuscooter.ui.onboarding

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import br.dev.evaldo.meuscooter.domain.OnboardingFlow
import br.dev.evaldo.meuscooter.domain.OnboardingStep
import br.dev.evaldo.meuscooter.ui.home.AppCard
import br.dev.evaldo.meuscooter.ui.theme.AppButton
import br.dev.evaldo.meuscooter.ui.theme.AppIcons
import br.dev.evaldo.meuscooter.ui.theme.AppTheme

data class OnboardingActions(val onConfirm: () -> Unit = {}, val onFinish: () -> Unit = {}, val onCancel: () -> Unit = {})

/**
 * Primeiro uso em 3 passos (design/screens.py `primeiro_uso`; "iX7 Pro" vira [deviceName]). Sem animação de ondas
 * (decorativo, e animações infinitas travam os testes de UI). Nada é enviado ao patinete; o app só conecta após "Conectar a este patinete".
 */
@Composable
fun OnboardingScreen(flow: OnboardingFlow, deviceName: String, actions: OnboardingActions) {
    val ext = AppTheme.extended
    val scheme = MaterialTheme.colorScheme
    val done = flow.step == OnboardingStep.Connected || flow.step == OnboardingStep.Ready
    Scaffold(containerColor = scheme.background) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("Vamos conectar seu patinete", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text(if (done) "Tudo certo!" else "Isso é feito só uma vez.", style = MaterialTheme.typography.bodyLarge, color = ext.textSecondary)
                when {
                    done -> Connected(deviceName)
                    flow.step == OnboardingStep.Found -> Found(deviceName, flow.failed)
                    else -> Searching()
                }
            }
            Box(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                when {
                    done -> AppButton("Começar", "onboarding-button", onClick = actions.onFinish)
                    flow.step == OnboardingStep.Found && flow.confirmed -> AppButton("Conectando…", "onboarding-button", enabled = false, onClick = {})
                    flow.step == OnboardingStep.Found -> AppButton(if (flow.failed) "Tentar novamente" else "Conectar a este patinete", "onboarding-button", onClick = actions.onConfirm)
                    else -> AppButton("Cancelar", "onboarding-button", tonal = true, onClick = actions.onCancel)
                }
            }
        }
    }
}

@Composable
private fun Searching() {
    val ext = AppTheme.extended
    val primary = MaterialTheme.colorScheme.primary
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(Modifier.size(180.dp), contentAlignment = Alignment.Center) {
            listOf(180.dp to 0.15f, 140.dp to 0.3f, 100.dp to 0.6f).forEach { (size, a) ->
                Surface(Modifier.size(size).alpha(a), shape = CircleShape, color = Color.Transparent, border = BorderStroke(2.dp, primary)) {}
            }
            Surface(Modifier.size(60.dp), shape = CircleShape, color = primary) {
                Icon(AppIcons.Bluetooth, contentDescription = null, Modifier.padding(14.dp), tint = MaterialTheme.colorScheme.onPrimary)
            }
        }
        Text("Procurando seu patinete…", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("1. Ligue o patinete", "2. Fique perto dele (1 a 2 metros)", "3. Deixe o Bluetooth do celular ligado").forEach {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = ext.textSecondary)
            }
        }
    }
}

@Composable
private fun Found(deviceName: String, failed: Boolean) {
    val ext = AppTheme.extended
    Text("Encontramos este patinete", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
    AppCard(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Surface(Modifier.size(40.dp), shape = MaterialTheme.shapes.medium, color = ext.track) {
                Icon(AppIcons.Scooter, contentDescription = null, Modifier.padding(7.dp), tint = MaterialTheme.colorScheme.primary)
            }
            Column(Modifier.weight(1f)) {
                Text(deviceName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("Encontrado agora", style = MaterialTheme.typography.bodySmall, color = ext.textSecondary)
            }
            Box(Modifier.size(10.dp).background(ext.success, CircleShape))
        }
    }
    if (failed) Text("Não foi possível conectar. Aproxime-se do patinete e tente de novo.", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.error)
    Text("É o seu? Confirme antes de conectar. O app só controla patinetes que você escolher.", style = MaterialTheme.typography.bodyMedium, color = ext.textSecondary)
}

@Composable
private fun Connected(deviceName: String) {
    val ext = AppTheme.extended
    Column(Modifier.fillMaxWidth().padding(top = 24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Surface(Modifier.size(140.dp), shape = CircleShape, color = MaterialTheme.colorScheme.surface, border = BorderStroke(3.dp, ext.success)) {
            Icon(AppIcons.Check, contentDescription = null, Modifier.padding(38.dp), tint = ext.success)
        }
        Text("$deviceName conectado", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Text("Agora é só usar o app.", style = MaterialTheme.typography.bodyLarge, color = ext.textSecondary, textAlign = TextAlign.Center)
    }
}

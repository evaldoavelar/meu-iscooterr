package br.dev.evaldo.meuscooter.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import br.dev.evaldo.meuscooter.ui.home.HomeActions
import br.dev.evaldo.meuscooter.domain.AppDestination
import br.dev.evaldo.meuscooter.domain.Navigator
import br.dev.evaldo.meuscooter.ui.home.HomeScreen
import br.dev.evaldo.meuscooter.ui.theme.AppIcons
import androidx.compose.ui.graphics.graphicsLayer

/** Raiz da navegação da Sprint 4: Home + telas stub (Sprint 5 as implementa). */
@Composable
fun AppRoot(viewModel: HomeViewModel) {
    val navigator = remember { Navigator() }
    var current by remember { mutableStateOf(navigator.current) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    if (current == AppDestination.Home) {
        HomeScreen(
            state,
            HomeActions(
                onMainButton = viewModel::onMainButton, onHeadlight = viewModel::onHeadlight, onMotors = viewModel::onMotors,
                onMode = viewModel::onMode, onCruise = viewModel::onCruise, onZeroStart = viewModel::onZeroStart,
                onLimit = viewModel::onLimit, onOpen = { navigator.open(it); current = navigator.current },
            ),
        )
    } else {
        BackHandler { navigator.back(); current = navigator.current }
        StubScreen(
            when (current) {
                AppDestination.Home, AppDestination.Telemetry -> "Telemetria"
                AppDestination.Settings -> "Preferências"
                AppDestination.History -> "Histórico de viagens"
            },
            onBack = { navigator.back(); current = navigator.current },
        )
    }
}

@Composable
private fun StubScreen(title: String, onBack: () -> Unit) {
    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack, Modifier.size(48.dp)) {
                    Icon(AppIcons.Chevron, contentDescription = "Voltar", Modifier.graphicsLayer { scaleX = -1f })
                }
                Text(title, Modifier.testTag("stub-title"), style = MaterialTheme.typography.headlineSmall)
            }
            Text("Esta tela chega na Sprint 5.", Modifier.padding(top = 24.dp), style = MaterialTheme.typography.bodyLarge)
        }
    }
}

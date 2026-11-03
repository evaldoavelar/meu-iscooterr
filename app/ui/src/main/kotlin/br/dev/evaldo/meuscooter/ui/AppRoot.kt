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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.listSaver
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
import br.dev.evaldo.meuscooter.ui.history.HistoryScreen
import br.dev.evaldo.meuscooter.ui.history.TripDetailScreen
import br.dev.evaldo.meuscooter.ui.telemetry.TelemetryScreen
import br.dev.evaldo.meuscooter.ui.theme.AppIcons
import androidx.compose.ui.graphics.graphicsLayer

/** Raiz da navegação: Home + telas (stubs até a fatia correspondente da Sprint 5). */
@Composable
fun AppRoot(viewModel: HomeViewModel) {
    var navigator by rememberSaveable(stateSaver = NavigatorSaver) { mutableStateOf(Navigator()) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    if (navigator.current == AppDestination.Home) {
        HomeScreen(
            state,
            HomeActions(
                onMainButton = viewModel::onMainButton, onHeadlight = viewModel::onHeadlight, onMotors = viewModel::onMotors,
                onMode = viewModel::onMode, onCruise = viewModel::onCruise, onZeroStart = viewModel::onZeroStart,
                onLimit = viewModel::onLimit, onOpen = { navigator = navigator.open(it) },
            ),
        )
    } else {
        BackHandler { navigator = navigator.back() }
        val telemetry by viewModel.telemetryState.collectAsStateWithLifecycle()
        if (navigator.current == AppDestination.Telemetry) {
            TelemetryScreen(
                telemetry, onBack = { navigator = navigator.back() }, onZeroTrip = viewModel::onZeroTrip,
                onOpenHistory = { navigator = navigator.open(AppDestination.History) },
            )
        } else if (navigator.current == AppDestination.History) {
            val history by viewModel.historyState.collectAsStateWithLifecycle()
            HistoryScreen(
                history, onBack = { navigator = navigator.back() }, onOpenTrip = { navigator = navigator.openTrip(it) },
                routeOf = { viewModel.trip(it)?.route.orEmpty() },
            )
        } else if (navigator.current == AppDestination.TripDetail) {
            val id = navigator.tripId
            val detail = id?.let(viewModel::tripDetail)
            if (detail != null) TripDetailScreen(detail, viewModel.trip(id).let { it?.route.orEmpty() }, onBack = { navigator = navigator.back() })
            else StubScreen("Viagem não encontrada", onBack = { navigator = navigator.back() })
        } else {
            StubScreen(navigator.current.label, onBack = { navigator = navigator.back() })
        }
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

private val NavigatorSaver = listSaver<Navigator, String>(save = { it.names }, restore = { Navigator.fromNames(it) })

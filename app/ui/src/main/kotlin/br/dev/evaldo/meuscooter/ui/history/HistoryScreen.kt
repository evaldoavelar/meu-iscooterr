package br.dev.evaldo.meuscooter.ui.history

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import br.dev.evaldo.meuscooter.domain.GeoPoint
import br.dev.evaldo.meuscooter.ui.HistoryRowUi
import br.dev.evaldo.meuscooter.ui.HistoryUiState
import br.dev.evaldo.meuscooter.ui.home.AppCard
import br.dev.evaldo.meuscooter.ui.theme.AppIcons
import br.dev.evaldo.meuscooter.ui.theme.AppTheme

/** Histórico (design/history.py). [routeOf] fornece o trajeto para a miniatura (vazio = sem trajeto). */
@Composable
fun HistoryScreen(state: HistoryUiState, onBack: () -> Unit, onOpenTrip: (Long) -> Unit, routeOf: (Long) -> List<GeoPoint> = { emptyList() }) {
    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BackButton(onBack)
                Text("Histórico", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                Box(Modifier.size(48.dp))
            }
            if (state.empty) EmptyState() else LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item { SummaryCard(state) }
                items(state.rows, key = { it.id }) { row -> TripRow(row, routeOf(row.id), onOpenTrip) }
            }
        }
    }
}

@Composable
internal fun BackButton(onBack: () -> Unit) {
    IconButton(onClick = onBack, Modifier.size(48.dp)) {
        Icon(AppIcons.Chevron, contentDescription = "Voltar", Modifier.graphicsLayer { scaleX = -1f })
    }
}

@Composable
private fun EmptyState() {
    Column(Modifier.fillMaxSize().padding(horizontal = 40.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(AppIcons.Pin, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
        Text("Nenhuma viagem ainda", Modifier.padding(top = 16.dp), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(
            "Suas viagens aparecem aqui, com o trajeto no mapa. Tudo fica só no seu celular.",
            Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodyMedium, color = AppTheme.extended.textSecondary, textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun SummaryCard(state: HistoryUiState) {
    AppCard(Modifier.fillMaxWidth().testTag("history-summary")) {
        Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SummaryItem(Modifier.weight(1f), state.summary.count, "Viagens · 30 dias")
            SummaryItem(Modifier.weight(1f), state.summary.distance, "Distância")
            SummaryItem(Modifier.weight(1f), state.summary.time, "Tempo")
        }
    }
}

@Composable
private fun SummaryItem(modifier: Modifier, value: String, label: String) {
    Column(modifier) {
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.bodySmall, color = AppTheme.extended.textSecondary)
    }
}

@Composable
private fun TripRow(row: HistoryRowUi, route: List<GeoPoint>, onOpen: (Long) -> Unit) {
    Column {
        row.section?.let {
            Text(it, Modifier.padding(start = 4.dp, top = 6.dp, bottom = 4.dp), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = AppTheme.extended.textSecondary)
        }
        AppCard(Modifier.fillMaxWidth().clickable { onOpen(row.id) }.testTag("trip-${row.id}")) {
            Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                RouteMap(route, Modifier.size(72.dp), thumb = true)
                Column(Modifier.weight(1f)) {
                    Text(row.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(row.headline, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    Text(row.subtitle, style = MaterialTheme.typography.bodySmall, color = AppTheme.extended.textSecondary)
                }
                Icon(AppIcons.Chevron, null, tint = AppTheme.extended.textSecondary)
            }
        }
    }
}

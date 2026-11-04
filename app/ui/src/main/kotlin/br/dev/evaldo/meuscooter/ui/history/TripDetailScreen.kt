package br.dev.evaldo.meuscooter.ui.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import br.dev.evaldo.meuscooter.domain.GeoPoint
import br.dev.evaldo.meuscooter.ui.TripDetailUi
import br.dev.evaldo.meuscooter.ui.home.AppCard
import androidx.compose.ui.graphics.vector.ImageVector
import br.dev.evaldo.meuscooter.ui.theme.AppIcons
import br.dev.evaldo.meuscooter.ui.theme.AppTheme
import br.dev.evaldo.meuscooter.ui.theme.FitText
import br.dev.evaldo.meuscooter.ui.theme.IconTile

/** Detalhe da viagem (design/history.py `detalhe`). Dados locais; nada é enviado ao patinete. */
@Composable
fun TripDetailScreen(detail: TripDetailUi, route: List<GeoPoint>, onBack: () -> Unit) {
    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BackButton(onBack)
                Text(detail.title, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                Box(Modifier.size(48.dp))
            }
            RouteMap(route, Modifier.fillMaxWidth().height(260.dp))
            AppCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { Item("Distância", detail.distance, AppIcons.Route, "route"); Item("Duração", detail.duration, AppIcons.Clock, "clock") }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { Item("Velocidade média", detail.avg, AppIcons.Speed, "speed"); Item("Velocidade máxima", detail.max, AppIcons.Trend, "trend") }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { Item("Altimetria", detail.altitude, AppIcons.Mountain, "mountain"); Item("Consumo médio", detail.consumption, AppIcons.Bolt, "bolt") }
                }
            }
            detail.battery?.let {
                AppCard(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        IconTile(AppIcons.Battery, "battery")
                        Text("Bateria na viagem", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = AppTheme.extended.textSecondary)
                        Text(it, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    }
                }
            }
            Text(
                "Trajetos e viagens ficam salvos só no seu celular.", Modifier.fillMaxWidth().padding(vertical = 8.dp),
                style = MaterialTheme.typography.bodySmall, color = AppTheme.extended.textSecondary, textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.Item(label: String, value: String, icon: ImageVector, name: String) {
    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        IconTile(icon, name)
        Column(Modifier.weight(1f)) {
            FitText(label, style = MaterialTheme.typography.bodySmall, color = AppTheme.extended.textSecondary)
            FitText(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
    }
}

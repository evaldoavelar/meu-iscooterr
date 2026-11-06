package br.dev.evaldo.meuscooter.ui.eventlog

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.border
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import br.dev.evaldo.meuscooter.domain.AppEvent
import br.dev.evaldo.meuscooter.domain.EventFilter
import br.dev.evaldo.meuscooter.domain.EventLog
import br.dev.evaldo.meuscooter.domain.EventSeverity
import br.dev.evaldo.meuscooter.ui.EventLogUiMapper
import br.dev.evaldo.meuscooter.ui.EventRowUi
import br.dev.evaldo.meuscooter.ui.history.BackButton
import br.dev.evaldo.meuscooter.ui.home.AppCard
import br.dev.evaldo.meuscooter.ui.theme.AppIcons
import br.dev.evaldo.meuscooter.ui.theme.AppTheme
import java.time.ZoneId

/** Registro de eventos (design/proposals/log-eventos-proposta.png). [nowMs] = relógio de parede, para o "Hoje". */
@Composable
fun EventLogScreen(events: List<AppEvent>, zone: ZoneId, nowMs: Long, onBack: () -> Unit, onClear: () -> Unit) {
    var filterName by rememberSaveable { mutableStateOf(EventFilter.All.name) }
    val filter = EventFilter.valueOf(filterName)
    val state = remember(events, filter, nowMs, zone) { EventLogUiMapper(zone).map(events, filter, nowMs) }
    var menuOpen by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BackButton(onBack)
                Text("Registro de eventos", Modifier.weight(1f).testTag("eventlog-title"), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Box {
                    IconButton(onClick = { menuOpen = true }, Modifier.size(48.dp).testTag("eventlog-menu")) {
                        Icon(AppIcons.More, contentDescription = "Mais opções do registro")
                    }
                    DropdownMenu(menuOpen, { menuOpen = false }) {
                        DropdownMenuItem(text = { Text("Limpar registro") }, onClick = { menuOpen = false; confirmClear = true }, modifier = Modifier.testTag("eventlog-clear"))
                    }
                }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                EventFilter.entries.forEach { f ->
                    FilterChip(
                        selected = f == filter, onClick = { filterName = f.name }, label = { Text(f.label) },
                        modifier = Modifier.testTag("eventlog-filter-${f.name}"),
                    )
                }
            }
            if (state.empty) {
                Column(Modifier.fillMaxSize().padding(horizontal = 40.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Text("Nenhum evento ainda", Modifier.testTag("eventlog-empty"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        "Bloqueios, desbloqueios e conexões aparecem aqui. Tudo fica só no seu celular.",
                        Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodyMedium, color = AppTheme.extended.textSecondary, textAlign = TextAlign.Center,
                    )
                }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    item {
                        AppCard(Modifier.fillMaxWidth().testTag("eventlog-summary").semantics(mergeDescendants = true) {
                            contentDescription = "Hoje: ${state.summary}" + (state.last?.let { ". Último às $it" } ?: "")
                        }) {
                            Column(Modifier.padding(14.dp)) {
                                Row {
                                    Text("Hoje", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = AppTheme.extended.textSecondary)
                                    state.last?.let { Text("Último: $it", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary) }
                                }
                                Text(state.summary, Modifier.padding(top = 2.dp), style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                    }
                    if (state.filteredEmpty) item {
                        Text(
                            "Nenhum evento neste filtro", Modifier.fillMaxWidth().padding(vertical = 24.dp).testTag("eventlog-filter-empty"),
                            style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                        )
                    }
                    state.days.forEach { day ->
                        item(key = "h-${day.header}") {
                            Text(day.header, Modifier.padding(start = 4.dp, top = 4.dp).testTag("eventlog-day"), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = AppTheme.extended.textSecondary)
                        }
                        items(day.rows, key = { it.id }) { EventRow(it) }
                    }
                    item {
                        Text(
                            "Mantém os últimos ${EventLog.LIMIT} eventos · nunca guarda o PIN",
                            Modifier.fillMaxWidth().padding(vertical = 12.dp), style = MaterialTheme.typography.bodySmall, color = AppTheme.extended.textSecondary, textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        }
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            text = { Text("Limpar o registro de eventos? Isso não afeta o patinete.") },
            confirmButton = { TextButton({ confirmClear = false; onClear() }, Modifier.testTag("eventlog-clear-yes")) { Text("Sim") } },
            dismissButton = { TextButton({ confirmClear = false }, Modifier.testTag("eventlog-clear-no")) { Text("Não") } },
            modifier = Modifier.testTag("eventlog-clear-dialog"),
        )
    }
}

@Composable
private fun severityColor(s: EventSeverity): Color = when (s) {
    EventSeverity.Success -> AppTheme.extended.success
    EventSeverity.Info -> MaterialTheme.colorScheme.primary
    EventSeverity.Neutral -> AppTheme.extended.textSecondary
    EventSeverity.Warning -> AppTheme.extended.pending
    EventSeverity.Error -> AppTheme.extended.error
}

@Composable
private fun EventRow(row: EventRowUi) {
    val color = severityColor(row.severity)
    AppCard(Modifier.fillMaxWidth().testTag("event-${row.id}").semantics(mergeDescendants = true) {
        contentDescription = "${row.title}, ${row.detail}, às ${row.time}"
    }) {
        Row(Modifier.height(androidx.compose.foundation.layout.IntrinsicSize.Min)) {
            Box(Modifier.width(4.dp).fillMaxHeight().background(color))
            Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(40.dp).clip(CircleShape).border(2.dp, color, CircleShape), contentAlignment = Alignment.Center) {
                    Text(row.glyph, color = color, fontWeight = FontWeight.Bold)
                }
                Column(Modifier.weight(1f)) {
                    Row {
                        Text(row.title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        Text(row.time, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = color)
                    }
                    Text(row.detail, style = MaterialTheme.typography.bodySmall, color = AppTheme.extended.textSecondary)
                }
            }
        }
    }
}

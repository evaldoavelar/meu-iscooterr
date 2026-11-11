package br.dev.evaldo.meuscooter.ui.history

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import br.dev.evaldo.meuscooter.domain.GeoPoint
import br.dev.evaldo.meuscooter.ui.HistoryRowUi
import br.dev.evaldo.meuscooter.ui.HistoryUiState
import br.dev.evaldo.meuscooter.ui.home.AppCard
import br.dev.evaldo.meuscooter.ui.theme.AppIcons
import br.dev.evaldo.meuscooter.ui.theme.AppTheme
import br.dev.evaldo.meuscooter.ui.theme.FitText

/** Histórico (design/history.py). [routeOf] fornece o trajeto para a miniatura (vazio = sem trajeto). */
@Composable
fun HistoryScreen(
    state: HistoryUiState,
    onBack: () -> Unit,
    onOpenTrip: (Long) -> Unit,
    routeOf: (Long) -> List<GeoPoint> = { emptyList() },
    onDeleteTrip: (Long) -> Unit = {},
) {
    var pendingDeleteRow by remember { mutableStateOf<HistoryRowUi?>(null) }
    var openRowId by remember { mutableStateOf<Long?>(null) }

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BackButton(onBack)
                Text("Histórico", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                Box(Modifier.size(48.dp))
            }
            if (state.empty) EmptyState() else LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item { SummaryCard(state) }
                items(state.rows, key = { it.id }) { row ->
                    TripRow(
                        row = row,
                        route = routeOf(row.id),
                        onOpen = onOpenTrip,
                        onRequestDelete = { pendingDeleteRow = it },
                        isOpen = openRowId == row.id,
                        onOpenChange = { open -> openRowId = if (open) row.id else if (openRowId == row.id) null else openRowId },
                    )
                }
            }
        }
    }

    pendingDeleteRow?.let { row ->
        DeleteTripDialog(
            row = row,
            onConfirm = {
                val id = row.id
                pendingDeleteRow = null
                openRowId = null
                onDeleteTrip(id)
            },
            onDismiss = {
                openRowId = null
                pendingDeleteRow = null
            },
        )
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
    val items = listOf(state.summary.count to "Viagens", state.summary.distance to "Distância", state.summary.time to "Tempo")
    val stacked = LocalDensity.current.fontScale >= 1.75f   // fonte 2,0: itens empilhados, sem corte (mockup resumo-historico-A-fonte2)
    val divider = MaterialTheme.colorScheme.outline
    AppCard(Modifier.fillMaxWidth().testTag("history-summary")) {
        if (stacked) {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
                items.forEachIndexed { i, (v, l) ->
                    if (i > 0) Box(Modifier.padding(vertical = 12.dp).fillMaxWidth().height(1.dp).background(divider).testTag("summary-divider-$i"))
                    SummaryItem(Modifier.fillMaxWidth().testTag("summary-item-$i"), v, l)
                }
            }
        } else {
            Row(Modifier.height(IntrinsicSize.Min).padding(horizontal = 20.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                items.forEachIndexed { i, (v, l) ->
                    if (i > 0) Box(Modifier.padding(horizontal = 8.dp).width(1.dp).fillMaxHeight().background(divider).testTag("summary-divider-$i"))
                    SummaryItem(Modifier.weight(1f).testTag("summary-item-$i"), v, l)
                }
            }
        }
    }
}

@Composable
private fun SummaryItem(modifier: Modifier, value: String, label: String) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        FitText(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        FitText(label, style = MaterialTheme.typography.bodySmall, color = AppTheme.extended.textSecondary)
    }
}

/**
 * Linha com swipe-to-reveal (protótipo Historico-Deslizando): arrastar à esquerda REVELA o bloco vermelho "Excluir" e o
 * card assenta aberto; só o toque no bloco abre o diálogo. Fechar (arrastar de volta, tocar no card aberto, cancelar ou
 * confirmar) volta ao repouso. Com o card aberto, tocar nele apenas fecha (não abre o detalhe).
 */
@Composable
private fun TripRow(
    row: HistoryRowUi,
    route: List<GeoPoint>,
    onOpen: (Long) -> Unit,
    onRequestDelete: (HistoryRowUi) -> Unit,
    isOpen: Boolean,
    onOpenChange: (Boolean) -> Unit,
) {
    val revealPx = with(LocalDensity.current) { 88.dp.toPx() }
    val offset = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(isOpen) { offset.animateTo(if (isOpen) -revealPx else 0f) }

    Column {
        row.section?.let {
            Text(
                it,
                Modifier.padding(start = 4.dp, top = 6.dp, bottom = 4.dp),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = AppTheme.extended.textSecondary,
            )
        }
        Box(Modifier.testTag("swipe-row-${row.id}")) {
            if (offset.value < 0f) {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .clip(RoundedCornerShape(20.dp))
                        .background(AppTheme.extended.error),
                    contentAlignment = Alignment.CenterEnd,
                ) {
                    Column(
                        modifier = Modifier
                            .width(88.dp)
                            .fillMaxHeight()
                            .clickable { onRequestDelete(row) }
                            // Sem 2º "Excluir" no TalkBack: a ação acessível é a customAction do card.
                            .clearAndSetSemantics { testTag = "action-delete-${row.id}" },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Icon(AppIcons.Trash, contentDescription = null, tint = MaterialTheme.colorScheme.onError, modifier = Modifier.size(24.dp))
                        Text(
                            "Excluir",
                            color = MaterialTheme.colorScheme.onError,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
            AppCard(
                Modifier
                    .offset { IntOffset(offset.value.roundToInt(), 0) }
                    .draggable(
                        orientation = Orientation.Horizontal,
                        state = rememberDraggableState { d -> scope.launch { offset.snapTo((offset.value + d).coerceIn(-revealPx, 0f)) } },
                        onDragStopped = {
                            val open = offset.value < -revealPx / 2
                            onOpenChange(open)
                            scope.launch { offset.animateTo(if (open) -revealPx else 0f) }
                        },
                    )
                    .fillMaxWidth()
                    .clickable { if (isOpen) onOpenChange(false) else onOpen(row.id) }
                    .semantics {
                        customActions = listOf(
                            CustomAccessibilityAction("Excluir viagem") {
                                onRequestDelete(row)
                                true
                            },
                        )
                    }
                    .testTag("trip-${row.id}"),
            ) {
                Row(
                    Modifier.padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
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
}

@Composable
private fun DeleteTripDialog(
    row: HistoryRowUi,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                "Excluir esta viagem?",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Text(
                "${row.title} · ${row.headline}. O trajeto no mapa também será apagado. Isso não pode ser desfeito.",
                style = MaterialTheme.typography.bodyMedium,
                color = AppTheme.extended.textSecondary,
            )
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(
                    containerColor = AppTheme.extended.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.testTag("delete-confirm"),
            ) {
                Text("Excluir", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(
                    containerColor = AppTheme.extended.track,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                ),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.testTag("delete-cancel"),
            ) {
                Text("Cancelar", fontWeight = FontWeight.SemiBold)
            }
        },
        modifier = Modifier.testTag("delete-dialog"),
    )
}

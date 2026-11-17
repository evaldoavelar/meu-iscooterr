package br.dev.evaldo.meuscooter.ui.backup

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import br.dev.evaldo.meuscooter.ui.BackupViewModel

/**
 * Liga [BackupScreen] ao ViewModel e ao seletor do Android ("Criar documento", que já permite escolher o Google Drive).
 * O pedido do seletor é contado em memória (não salvo): ao girar a tela com o seletor aberto ele não abre duas vezes,
 * e o resultado ainda chega ao mesmo ViewModel.
 */
@Composable
fun BackupRoute(vm: BackupViewModel, onBack: () -> Unit) {
    val state by vm.backup.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.onBackupOpened() }
    val handled = remember { mutableIntStateOf(state.destinationRequest) }
    val create = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri -> vm.onDestinationChosen(uri?.toString()) }
    LaunchedEffect(state.destinationRequest) {
        if (state.destinationRequest > handled.intValue) { handled.intValue = state.destinationRequest; create.launch(state.suggestedName) }
    }
    BackupScreen(
        state,
        BackupActions(
            onBack = { vm.onBackupExit(); onBack() },
            onSave = { vm.onSaveClicked(it) },
            onSaveWithoutPin = { vm.onSaveClicked(it, includePin = false) },
            onDone = { vm.onBackupExit(); onBack() },
        ),
    )
}

/** Liga [RestoreScreen] ao ViewModel e ao seletor "Abrir documento". [onOpenApp] reinicia o app (transporte real, histórico novo). */
@Composable
fun RestoreRoute(vm: BackupViewModel, onBack: () -> Unit, onOpenApp: () -> Unit) {
    val state by vm.restore.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.onRestoreOpened() }
    val handled = remember { mutableIntStateOf(state.pickRequest) }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> vm.onDocumentPicked(uri?.toString()) }
    LaunchedEffect(state.pickRequest) {
        if (state.pickRequest > handled.intValue) { handled.intValue = state.pickRequest; open.launch(arrayOf("application/octet-stream", "*/*")) }
    }
    RestoreScreen(
        state,
        RestoreActions(
            onBack = { vm.onRestoreExit(); onBack() },
            onPickAgain = vm::onPickAgain,
            onPassword = vm::onPasswordSubmitted,
            onRestore = vm::onRestoreConfirmed,
            onOpenApp = { vm.onRestoreExit(); onOpenApp() },
        ),
    )
}

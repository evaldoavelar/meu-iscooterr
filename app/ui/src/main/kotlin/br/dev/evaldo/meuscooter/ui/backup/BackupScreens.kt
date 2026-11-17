package br.dev.evaldo.meuscooter.ui.backup

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import br.dev.evaldo.meuscooter.ui.BACKUP_BLOCKED_BY_TRIP
import br.dev.evaldo.meuscooter.ui.BackupFormat
import br.dev.evaldo.meuscooter.ui.BackupStep
import br.dev.evaldo.meuscooter.ui.BackupUiState
import br.dev.evaldo.meuscooter.ui.PasswordCheck
import br.dev.evaldo.meuscooter.ui.PinOutcome
import br.dev.evaldo.meuscooter.ui.RESTORE_BLOCKED_BY_TRIP
import br.dev.evaldo.meuscooter.ui.RestoreStep
import br.dev.evaldo.meuscooter.ui.RestoreUiState
import br.dev.evaldo.meuscooter.ui.WRONG_PASSWORD_MESSAGE
import br.dev.evaldo.meuscooter.ui.home.AppCard
import br.dev.evaldo.meuscooter.ui.theme.AppButton
import br.dev.evaldo.meuscooter.ui.theme.AppIcons
import br.dev.evaldo.meuscooter.ui.theme.AppTheme

data class BackupActions(
    val onBack: () -> Unit = {},
    /** Entrega a senha; o ViewModel a zera depois do uso. */
    val onSave: (CharArray) -> Unit = {},
    val onSaveWithoutPin: (CharArray) -> Unit = {},
    val onDone: () -> Unit = {},
)

data class RestoreActions(
    val onBack: () -> Unit = {},
    val onPickAgain: () -> Unit = {},
    val onPassword: (CharArray) -> Unit = {},
    val onRestore: () -> Unit = {},
    val onOpenApp: () -> Unit = {},
)

// ------------------------------------------------------------------ Fazer backup

/** design/mockups/backup-criar-v1 e backup-concluido-v1. Sem rolagem horizontal, fonte 2,0 sem corte (rola na vertical). */
@Composable
fun BackupScreen(state: BackupUiState, actions: BackupActions) {
    BackHandler(enabled = state.busy) { /* gravando: não sai no meio */ }
    if (state.step == BackupStep.Saved && state.saved != null) {
        ResultScreen(
            "Backup salvo", "${state.saved.fileName} · ${state.saved.sizeLabel}. Guarde a senha: sem ela não dá para restaurar.",
            note = null, buttonLabel = "Concluir", tag = "backup-done", onClick = actions.onDone,
        )
        return
    }
    var password by remember { mutableStateOf("") }
    var repeat by remember { mutableStateOf("") }
    var show by remember { mutableStateOf(false) }
    val check = BackupFormat.check(password, repeat)
    val ready = check == PasswordCheck.Ok && !state.busy && !state.recording
    val ext = AppTheme.extended
    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            TopBar("Fazer backup", state.busy, actions.onBack)
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Salva um arquivo para recuperar tudo ao reinstalar o app ou trocar de celular.", Modifier.padding(horizontal = 8.dp), style = MaterialTheme.typography.bodyLarge, color = ext.textSecondary)
                AppCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("O que vai no backup", style = MaterialTheme.typography.bodyMedium, color = ext.textSecondary)
                        ContentRow(AppIcons.Route, "history", "Histórico de viagens", "${BackupFormat.trips(state.tripCount)} e seus trajetos", true)
                        ContentRow(AppIcons.Scooter, "scooter", "Patinete", if (state.hasScooter) "iX7 Pro (endereço Bluetooth)" else "Nenhum patinete cadastrado", state.hasScooter)
                        ContentRow(AppIcons.Lock, "pin", "PIN de desbloqueio", if (state.pinSaved) "Guardado cifrado com a senha abaixo" else "PIN não guardado", state.pinSaved)
                        ContentRow(AppIcons.Gear, "prefs", "Preferências", "Tema, unidades e opções", true)
                    }
                }
                AppCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Senha do backup", style = MaterialTheme.typography.bodyMedium, color = ext.textSecondary)
                        PasswordField("Senha", password, "backup-password", show, { show = !show }, { password = it }, enabled = !state.busy)
                        PasswordField("Repetir senha", repeat, "backup-repeat", show, { show = !show }, { repeat = it }, enabled = !state.busy)
                        if (repeat.isNotEmpty() && check == PasswordCheck.Mismatch) {
                            Text("As senhas não conferem.", Modifier.testTag("backup-mismatch").semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
                Text("Mínimo de ${BackupFormat.MIN_PASSWORD} caracteres. O PIN vai cifrado com esta senha. Sem ela o backup não abre e não há como recuperá-la.", Modifier.padding(horizontal = 8.dp), style = MaterialTheme.typography.bodyMedium, color = ext.textSecondary)
                if (state.recording) Message(BACKUP_BLOCKED_BY_TRIP, "backup-blocked")
                state.failure?.let { Message(it.message, "backup-error") }
                if (state.busy) Message(if (state.step == BackupStep.ReadingPin) "Confirme com a digital para ler o PIN…" else "Preparando o backup…", "backup-working", error = false)
            }
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.canSkipPin) AppButton("Salvar sem o PIN", "backup-skip-pin", tonal = true, enabled = ready) { val c = password.toCharArray(); actions.onSaveWithoutPin(c) }
                AppButton("Salvar backup…", "backup-save", enabled = ready) { val c = password.toCharArray(); actions.onSave(c) }
                Text("O Android abre o seletor de arquivos para você escolher onde salvar (celular, Drive...). Ao tocar, o app pede sua digital para ler o PIN do cofre.", style = MaterialTheme.typography.bodySmall, color = ext.textSecondary)
            }
        }
    }
}

// ------------------------------------------------------------------ Restaurar

/** design/mockups/restaurar-v1 e restaurar-concluido-v1. */
@Composable
fun RestoreScreen(state: RestoreUiState, actions: RestoreActions) {
    BackHandler(enabled = state.busy) { /* trabalhando: não sai no meio */ }
    if (state.step == RestoreStep.Done && state.done != null) {
        val d = state.done
        ResultScreen(BackupFormat.restoredTitle(d), BackupFormat.restoredText(d), note = d.pin.note, buttonLabel = "Abrir o app", tag = "restore-open-app", onClick = actions.onOpenApp)
        return
    }
    var password by remember { mutableStateOf("") }
    var show by remember { mutableStateOf(false) }
    val ext = AppTheme.extended
    val review = state.step == RestoreStep.Review || state.step == RestoreStep.Applying
    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            TopBar("Restaurar backup", state.busy, actions.onBack)
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                state.file?.let { f ->
                    AppCard(Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            Surface(Modifier.size(40.dp), shape = MaterialTheme.shapes.medium, color = ext.track) { Icon(AppIcons.Check, contentDescription = null, Modifier.padding(8.dp), tint = ext.success) }
                            Column(Modifier.weight(1f).testTag("restore-file")) {
                                Text(f.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                Text("${f.sizeLabel} · criado em ${f.createdLabel}", style = MaterialTheme.typography.bodyMedium, color = ext.textSecondary)
                            }
                        }
                    }
                }
                when (state.step) {
                    RestoreStep.Idle, RestoreStep.Picking, RestoreStep.NoFile, RestoreStep.Reading -> {
                        Text(if (state.step == RestoreStep.Reading) "Lendo o arquivo…" else "Escolha o arquivo .ixbak do backup (celular, Drive...).", Modifier.padding(8.dp).testTag("restore-hint"), style = MaterialTheme.typography.bodyLarge, color = ext.textSecondary)
                    }
                    RestoreStep.NeedsPassword, RestoreStep.Decrypting -> {
                        Text("Senha do backup", Modifier.padding(horizontal = 8.dp), style = MaterialTheme.typography.bodyMedium, color = ext.textSecondary)
                        PasswordField("Senha do backup", password, "restore-password", show, { show = !show }, { password = it }, enabled = state.step == RestoreStep.NeedsPassword)
                        if (state.wrongPassword) Message(WRONG_PASSWORD_MESSAGE, "restore-wrong-password")
                        if (state.step == RestoreStep.Decrypting) Message("Abrindo o backup…", "restore-working", error = false)
                    }
                    RestoreStep.Review, RestoreStep.Applying -> state.summary?.let { s ->
                        AppCard(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text("Dentro do backup", style = MaterialTheme.typography.bodyMedium, color = ext.textSecondary)
                                ContentRow(AppIcons.Route, "history", "Histórico de viagens", "${BackupFormat.trips(s.tripCount)} e seus trajetos", true)
                                ContentRow(AppIcons.Scooter, "scooter", "Patinete", if (s.hasScooter) "iX7 Pro" else "Nenhum patinete", s.hasScooter)
                                ContentRow(AppIcons.Lock, "pin", "PIN de desbloqueio", if (s.hasPin) "Será guardado de novo no cofre" else "Não está no backup", s.hasPin)
                                ContentRow(AppIcons.Gear, "prefs", "Preferências", "Tema, unidades e opções", true)
                            }
                        }
                        Surface(Modifier.fillMaxWidth().testTag("restore-warning"), shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface, border = BorderStroke(1.5.dp, ext.pending)) {
                            Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                                Surface(Modifier.size(40.dp), shape = MaterialTheme.shapes.medium, color = ext.track) { Icon(AppIcons.Alert, contentDescription = null, Modifier.padding(8.dp), tint = ext.pending) }
                                Column(Modifier.weight(1f)) {
                                    Text("Substitui o que há neste celular", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                    Text("Histórico, patinete e PIN atuais serão trocados pelos do backup.", style = MaterialTheme.typography.bodyMedium, color = ext.textSecondary)
                                }
                            }
                        }
                        if (state.step == RestoreStep.Applying) Message("Restaurando… não feche o app.", "restore-working", error = false)
                    }
                    RestoreStep.Failed -> {}
                    RestoreStep.Done -> {}
                }
                if (state.recording && review) Message(RESTORE_BLOCKED_BY_TRIP, "restore-blocked")
                if (state.step == RestoreStep.Failed) state.failure?.let { Message(it.message, "restore-error") }
            }
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when (state.step) {
                    RestoreStep.NeedsPassword, RestoreStep.Decrypting ->
                        AppButton("Abrir backup", "restore-unlock", enabled = state.step == RestoreStep.NeedsPassword && password.length >= 1) { val c = password.toCharArray(); actions.onPassword(c) }
                    RestoreStep.Review, RestoreStep.Applying -> {
                        AppButton("Restaurar", "restore-confirm", enabled = state.step == RestoreStep.Review && !state.recording, onClick = actions.onRestore)
                        Text("Depois de restaurar, o app pede sua digital para guardar o PIN no cofre deste celular.", style = MaterialTheme.typography.bodySmall, color = ext.textSecondary)
                    }
                    RestoreStep.Failed -> AppButton("Escolher outro arquivo", "restore-pick", onClick = actions.onPickAgain)
                    RestoreStep.NoFile, RestoreStep.Idle, RestoreStep.Picking -> AppButton("Escolher arquivo", "restore-pick", enabled = state.step == RestoreStep.NoFile, onClick = actions.onPickAgain)
                    else -> {}
                }
            }
        }
    }
}

// ------------------------------------------------------------------ Primeira abertura

/** design/mockups/primeira-abertura-backup-v1. */
@Composable
fun RestorePromptScreen(onRestore: () -> Unit, onStartFresh: () -> Unit) {
    val ext = AppTheme.extended
    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically)) {
                Surface(Modifier.size(140.dp), shape = CircleShape, color = MaterialTheme.colorScheme.surface, border = BorderStroke(3.dp, MaterialTheme.colorScheme.primary)) {
                    Icon(AppIcons.Scooter, contentDescription = null, Modifier.padding(38.dp), tint = MaterialTheme.colorScheme.primary)
                }
                Text("Já usou o app antes?", Modifier.testTag("prompt-title"), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                Text("Se você tem um backup, recupere viagens, patinete e PIN. Senão, comece do zero.", style = MaterialTheme.typography.bodyLarge, color = ext.textSecondary, textAlign = TextAlign.Center)
            }
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                AppButton("Começar do zero", "prompt-fresh", tonal = true, onClick = onStartFresh)
                AppButton("Restaurar backup", "prompt-restore", onClick = onRestore)
            }
        }
    }
}

// ------------------------------------------------------------------ Peças

@Composable
private fun TopBar(title: String, busy: Boolean, onBack: () -> Unit) {
    Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack, Modifier.size(48.dp).testTag("backup-back"), enabled = !busy) {
            Icon(AppIcons.Chevron, contentDescription = "Voltar", Modifier.graphicsLayer { scaleX = -1f })
        }
        Text(title, Modifier.weight(1f).semantics { contentDescription = title }, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Box(Modifier.size(48.dp))
    }
}

@Composable
private fun ContentRow(icon: ImageVector, name: String, title: String, subtitle: String, included: Boolean) {
    val ext = AppTheme.extended
    Row(Modifier.fillMaxWidth().testTag("row-$name"), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        br.dev.evaldo.meuscooter.ui.theme.IconTile(icon, name)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = ext.textSecondary)
        }
        if (included) Icon(AppIcons.Check, contentDescription = "Incluído", Modifier.size(24.dp), tint = ext.success)
    }
}

@Composable
private fun Message(text: String, tag: String, error: Boolean = true) {
    Text(
        text, Modifier.fillMaxWidth().padding(horizontal = 8.dp).testTag(tag).semantics { liveRegion = LiveRegionMode.Polite },
        style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold,
        color = if (error) MaterialTheme.colorScheme.error else AppTheme.extended.textSecondary,
    )
}

/**
 * Campo de senha: sem autofill (nenhum `contentType`), sem sugestões/autocorreção, texto mascarado salvo em "mostrar". O texto
 * fica só em memória do Compose (nunca em `rememberSaveable`/Bundle) e sai daqui como `CharArray` no botão.
 */
@Composable
private fun PasswordField(label: String, value: String, tag: String, show: Boolean, onToggle: () -> Unit, onChange: (String) -> Unit, enabled: Boolean) {
    val scheme = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = AppTheme.extended.textSecondary)
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp).border(1.dp, scheme.outline, RoundedCornerShape(18.dp)).padding(start = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicTextField(
                value, onChange, Modifier.weight(1f).testTag(tag).semantics { contentDescription = label },
                enabled = enabled, singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = scheme.onSurface),
                cursorBrush = SolidColor(scheme.primary),
                visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
            )
            Text(
                if (show) "ocultar" else "mostrar",
                Modifier.heightIn(min = 48.dp).clickable(role = Role.Button, onClick = onToggle).padding(horizontal = 14.dp).wrapCenter().testTag("$tag-toggle")
                    .semantics { contentDescription = if (show) "Ocultar senha" else "Mostrar senha" },
                style = MaterialTheme.typography.bodyMedium, color = scheme.primary,
            )
        }
    }
}

private fun Modifier.wrapCenter() = this.then(Modifier.heightIn(min = 48.dp)).padding(vertical = 12.dp)

@Composable
private fun ResultScreen(title: String, text: String, note: String?, buttonLabel: String, tag: String, onClick: () -> Unit) {
    val ext = AppTheme.extended
    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically),
            ) {
                Surface(Modifier.size(140.dp), shape = CircleShape, color = MaterialTheme.colorScheme.surface, border = BorderStroke(3.dp, ext.success)) {
                    Icon(AppIcons.Check, contentDescription = null, Modifier.padding(38.dp), tint = ext.success)
                }
                Column(Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text(title, Modifier.testTag("result-title"), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                    Text(text, Modifier.testTag("result-text"), style = MaterialTheme.typography.bodyLarge, color = ext.textSecondary, textAlign = TextAlign.Center)
                    if (note != null) Text(note, Modifier.testTag("result-note"), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = ext.pending, textAlign = TextAlign.Center)
                }
            }
            Box(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) { AppButton(buttonLabel, tag, onClick = onClick) }
        }
    }
}

package br.dev.evaldo.meuscooter.ui.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import br.dev.evaldo.meuscooter.ui.theme.AppIcons
import br.dev.evaldo.meuscooter.ui.theme.AppTheme
import br.dev.evaldo.meuscooter.protocol.Pin

/**
 * Entrada do PIN de 4 dígitos para desbloquear (etapa 4.4): teclado numérico próprio e 4 pontos mascarados — sem campo
 * de texto, então o PIN não passa pelo teclado do sistema (sem aprendizado/autopreenchimento) e nunca aparece em claro.
 * Os dígitos vivem só neste diálogo (`remember`, não `rememberSaveable`: não persistem) e são esvaziados ao enviar (4º dígito)
 * ou cancelar. Nada aqui registra o PIN.
 */
@Composable
internal fun PinDialog(
    vault: PinVaultUi,
    onConfirm: (Pin, Boolean) -> Unit,
    onUseSaved: () -> Unit,
    onForget: () -> Unit,
    onDismiss: () -> Unit,
) {
    var entry by remember { mutableStateOf(PinEntry()) }
    val digits = entry.digits
    var save by remember { mutableStateOf(false) }   // opt-in ("Acessar com Biometria"), sempre começa desligado
    val dismiss = { entry = PinEntry(); onDismiss() }
    AlertDialog(
        onDismissRequest = dismiss,
        modifier = Modifier.testTag("pin-dialog"),
        title = {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Desbloquear", fontWeight = FontWeight.Bold)
                Text(
                    "PIN do patinete · envia ao completar 4 dígitos",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        },
        text = {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                vault.notice?.let {
                    Text(it, Modifier.testTag("pin-dialog-notice"), style = MaterialTheme.typography.bodySmall, color = AppTheme.extended.pending, textAlign = TextAlign.Center)
                }
                PinDots(digits.length)
                PinKeypad(
                    showBiometric = vault.saved,
                    onDigit = { d ->
                        // Lê o estado atual a cada toque: dois toques antes da recomposição não enviam duas vezes.
                        val (next, complete) = entry.press(d)
                        entry = next
                        // 4º dígito digitado = UMA tentativa, na hora (decisão do proprietário, S30). O PIN some do diálogo ao enviar.
                        complete?.let { c -> Pin.parse(c)?.let { pin -> onConfirm(pin, save && vault.available && !vault.saved) } }
                    },
                    onBackspace = { entry = entry.backspace() },
                    onBiometric = { entry = PinEntry(); onUseSaved() },
                )
                if (vault.available && !vault.saved) {
                    Row(Modifier.testTag("pin-save-row"), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            save, { save = it },
                            Modifier.testTag("pin-save-checkbox")
                                .semantics { contentDescription = "Acessar com biometria: salvar o PIN neste aparelho após desbloquear" },
                        )
                        Text("Acessar com Biometria")
                    }
                }
                if (vault.saved) {
                    Text(
                        "Esquecer PIN",
                        Modifier.clickable(role = Role.Button) { onForget() }.heightIn(min = 48.dp).wrapContentHeight(Alignment.CenterVertically).padding(horizontal = 8.dp).testTag("pin-forget"),
                        style = MaterialTheme.typography.bodyMedium.copy(textDecoration = TextDecoration.Underline),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {},   // sem botão: o 4º dígito envia (PinEntry)
        dismissButton = {
            OutlinedButton(
                dismiss,
                Modifier.testTag("pin-cancel"),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant),
            ) { Text("Cancelar") }
        },
    )
}

/**
 * Digitação do PIN, pura (testável na JVM). [press] devolve o novo estado e, só ao digitar o 4º dígito, os 4 dígitos a enviar.
 * Depois de enviado ([sent]) o diálogo ignora novos toques (duplo disparo); um diálogo novo começa com [PinEntry] vazio.
 */
internal data class PinEntry(val digits: String = "", val sent: Boolean = false) {
    fun press(d: Char): Pair<PinEntry, String?> {
        if (sent || digits.length >= Pin.LENGTH || d !in '0'..'9') return this to null
        val next = digits + d
        return if (next.length == Pin.LENGTH) PinEntry(sent = true) to next else copy(digits = next) to null
    }
    fun backspace(): PinEntry = if (sent) this else copy(digits = digits.dropLast(1))
}

/** 4 pontos: preenchidos = dígitos já digitados. Só a QUANTIDADE é exposta (acessibilidade inclusive). */
@Composable
private fun PinDots(filled: Int) {
    Row(
        Modifier.testTag("pin-dots").semantics { contentDescription = "$filled de ${Pin.LENGTH} dígitos" },
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        repeat(Pin.LENGTH) { i ->
            val color = MaterialTheme.colorScheme.primary
            Box(
                Modifier.size(16.dp).clip(CircleShape)
                    .then(if (i < filled) Modifier.background(color) else Modifier.border(2.dp, color, CircleShape)),
            )
        }
    }
}

@Composable
private fun PinKeypad(showBiometric: Boolean, onDigit: (Char) -> Unit, onBackspace: () -> Unit, onBiometric: () -> Unit) {
    val rows = listOf("123", "456", "789")
    Column(verticalArrangement = Arrangement.spacedBy(KEY_GAP)) {
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(KEY_GAP)) { row.forEach { d -> Key(d.toString(), "pin-key-$d") { onDigit(d) } } }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(KEY_GAP), verticalAlignment = Alignment.CenterVertically) {
            if (showBiometric) {
                IconKey(AppIcons.Fingerprint, "pin-use-saved", "Acessar com biometria", ring = true, onClick = onBiometric)
            } else {
                Box(Modifier.size(KEY_SIZE))
            }
            Key("0", "pin-key-0") { onDigit('0') }
            IconKey(AppIcons.Backspace, "pin-backspace", "Apagar", ring = false, onClick = onBackspace)
        }
    }
}

private val KEY_SIZE = 64.dp
private val KEY_GAP = 12.dp

@Composable
private fun Key(label: String, tag: String, onClick: () -> Unit) {
    Box(
        Modifier.size(KEY_SIZE).clip(CircleShape)
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
            .clickable(role = Role.Button, onClick = onClick)
            .testTag(tag).semantics { contentDescription = "Dígito $label" },
        contentAlignment = Alignment.Center,
    ) { Text(label, style = MaterialTheme.typography.headlineSmall) }
}

/** Tecla de ícone (digital / apagar), em ciano; [ring] desenha o contorno circular (digital). Área de toque = tecla. */
@Composable
private fun IconKey(icon: ImageVector, tag: String, description: String, ring: Boolean, onClick: () -> Unit) {
    val primary = MaterialTheme.colorScheme.primary
    Box(
        Modifier.size(KEY_SIZE).clip(CircleShape)
            .then(if (ring) Modifier.border(2.dp, primary, CircleShape) else Modifier)
            .clickable(role = Role.Button, onClick = onClick).testTag(tag)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) { Icon(icon, contentDescription = null, Modifier.size(if (ring) 40.dp else 28.dp), tint = primary) }
}

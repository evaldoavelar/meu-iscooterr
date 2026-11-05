package br.dev.evaldo.meuscooter.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import br.dev.evaldo.meuscooter.protocol.Pin

/**
 * Entrada do PIN de 4 dígitos para desbloquear (etapa 4.4): teclado numérico próprio e 4 pontos mascarados — sem campo
 * de texto, então o PIN não passa pelo teclado do sistema (sem aprendizado/autopreenchimento) e nunca aparece em claro.
 * Os dígitos vivem só neste diálogo (`remember`, não `rememberSaveable`: não persistem) e são esvaziados ao confirmar
 * ou cancelar. Nada aqui registra o PIN.
 */
@Composable
internal fun PinDialog(onConfirm: (Pin) -> Unit, onDismiss: () -> Unit) {
    var digits by remember { mutableStateOf("") }
    val dismiss = { digits = ""; onDismiss() }
    AlertDialog(
        onDismissRequest = dismiss,
        modifier = Modifier.testTag("pin-dialog"),
        title = { Text("Desbloquear com PIN") },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Digite o PIN de 4 dígitos do patinete. Uma tentativa por toque.")
                PinDots(digits.length)
                PinKeypad(
                    onDigit = { d -> if (digits.length < Pin.LENGTH) digits += d },
                    onBackspace = { digits = digits.dropLast(1) },
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { Pin.parse(digits)?.let { pin -> digits = ""; onConfirm(pin) } },
                enabled = digits.length == Pin.LENGTH,
                modifier = Modifier.testTag("pin-confirm"),
            ) { Text("Desbloquear") }
        },
        dismissButton = { TextButton(dismiss) { Text("Cancelar") } },
    )
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
private fun PinKeypad(onDigit: (Char) -> Unit, onBackspace: () -> Unit) {
    val rows = listOf("123", "456", "789")
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { row.forEach { d -> Key(d.toString(), "pin-key-$d") { onDigit(d) } } }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Box(Modifier.size(72.dp, 48.dp))
            Key("0", "pin-key-0") { onDigit('0') }
            Key("⌫", "pin-backspace", description = "Apagar", onClick = onBackspace)
        }
    }
}

@Composable
private fun Key(label: String, tag: String, description: String? = null, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        modifier = Modifier.size(72.dp, 48.dp).testTag(tag)
            .then(if (description != null) Modifier.semantics { contentDescription = description } else Modifier),
    ) { Text(label, style = MaterialTheme.typography.titleLarge) }
}

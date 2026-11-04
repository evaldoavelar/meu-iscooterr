package br.dev.evaldo.meuscooter.ui.theme

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/** Botão largo do protótipo (`botao`): altura mínima 56 dp (≥ 48 dp), cresce com a fonte; `Button` já expõe Role.Button. */
@Composable
internal fun AppButton(label: String, tag: String, tonal: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    val ext = AppTheme.extended
    val scheme = MaterialTheme.colorScheme
    Button(
        onClick = onClick, enabled = enabled, shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag(tag),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (tonal) ext.track else scheme.primary, contentColor = if (tonal) scheme.onSurface else scheme.onPrimary,
            disabledContainerColor = ext.disabled, disabledContentColor = ext.onDisabled,
        ),
    ) { Text(label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center) }
}

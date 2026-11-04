package br.dev.evaldo.meuscooter.ui.theme

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp

/**
 * Texto de uma linha só: se não couber, a fonte encolhe (até [minScale] do tamanho original) em vez de quebrar linha.
 * Abaixo do mínimo corta (`Clip`), nunca quebra.
 */
@Composable
fun FitText(
    text: String, modifier: Modifier = Modifier, style: TextStyle = MaterialTheme.typography.bodyMedium,
    color: Color = Color.Unspecified, fontWeight: FontWeight? = null, minScale: Float = 0.5f,
) {
    val base = if (style.fontSize.isSpecified) style.fontSize.value else 14f
    var size by remember(base) { mutableStateOf(base) }   // não reinicia a cada texto novo (valores ao vivo não piscam em tamanho cheio)
    Text(
        text, modifier, color = color, fontSize = size.sp, fontWeight = fontWeight, style = style, maxLines = 1, softWrap = false, overflow = TextOverflow.Clip,
        onTextLayout = { if (it.hasVisualOverflow && size > base * minScale) size *= 0.92f },
    )
}

/** Quadradinho com ícone (design: `ic-tile` 36 dp, canto 10, fundo `track`). [name] vira a tag `tile-<name>` dos testes. */
@Composable
fun IconTile(icon: ImageVector, name: String, modifier: Modifier = Modifier) {
    Surface(modifier.size(36.dp).testTag("tile-$name"), shape = RoundedCornerShape(10.dp), color = AppTheme.extended.track) {
        Icon(icon, contentDescription = null, Modifier.padding(6.dp), tint = MaterialTheme.colorScheme.primary)
    }
}

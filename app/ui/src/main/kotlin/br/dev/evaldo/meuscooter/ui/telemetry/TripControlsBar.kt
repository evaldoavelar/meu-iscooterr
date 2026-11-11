package br.dev.evaldo.meuscooter.ui.telemetry

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import br.dev.evaldo.meuscooter.domain.TripRecState
import br.dev.evaldo.meuscooter.ui.theme.AppIcons

private val ARC = 12.dp

/**
 * Glifo do Reset: AppIcons.Reset e de traco (viewBox 24, tinta ~20u: arco 3..21 + traco); Parar e preenchido
 * (12u + 1,5u = 13,5u, 15,75 dp a 28 dp). 20u x 19/24 = 15,83 dp, ou seja, mesma tinta do Parar. O slot continua 28 dp.
 */
private val RESET_GLYPH = 19.dp

/** Barra fixa Reset | Play/Pause | Parar (arco sutil no topo). Tudo local: não envia nada ao patinete. */
@Composable
internal fun TripControlsBar(recState: TripRecState, onReset: () -> Unit, onPlayPause: () -> Unit, onStop: () -> Unit, modifier: Modifier = Modifier) {
    val arcPx = with(androidx.compose.ui.platform.LocalDensity.current) { ARC.toPx() }
    val shape = GenericShape { size, _ ->
        moveTo(0f, arcPx); quadraticTo(size.width / 2, -arcPx, size.width, arcPx)
        lineTo(size.width, size.height); lineTo(0f, size.height); close()
    }
    val line = MaterialTheme.colorScheme.outline
    Box(
        modifier.fillMaxWidth().testTag("trip-bar").clip(shape).background(MaterialTheme.colorScheme.background).border(1.dp, line, shape),
    ) {
        Row(Modifier.navigationBarsPadding().padding(top = ARC).heightIn(min = 72.dp), verticalAlignment = Alignment.CenterVertically) {
            Cell("trip-reset", "Reset", "Reset", AppIcons.Reset, MaterialTheme.colorScheme.onBackground, 28.dp, onReset, glyphSize = RESET_GLYPH, enabled = recState != TripRecState.Stopped)
            Divider()
            val running = recState == TripRecState.Running
            Cell(
                "trip-play-pause", if (running) "Pausar viagem" else "Iniciar viagem", null,
                if (running) AppIcons.Pause else AppIcons.Play, MaterialTheme.colorScheme.primary, 40.dp, onPlayPause,
            )
            Divider()
            Cell("trip-stop", "Parar viagem", "Parar", AppIcons.Stop, MaterialTheme.colorScheme.primary, 28.dp, onStop, enabled = recState != TripRecState.Stopped)
        }
    }
}

@Composable
private fun Divider() = Box(Modifier.width1().background(MaterialTheme.colorScheme.outline))

private fun Modifier.width1() = this.size(1.dp, 48.dp)

@Composable
private fun RowScope.Cell(tag: String, description: String, label: String?, icon: ImageVector, tint: androidx.compose.ui.graphics.Color, iconSize: androidx.compose.ui.unit.Dp, onClick: () -> Unit, glyphSize: androidx.compose.ui.unit.Dp = iconSize, enabled: Boolean = true) {
    Column(
        Modifier.weight(1f).heightIn(min = 64.dp).testTag(tag).semantics(mergeDescendants = true) { contentDescription = description }
            .alpha(if (enabled) 1f else 0.38f).clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        Box(Modifier.size(iconSize).testTag("$tag-slot"), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, Modifier.size(glyphSize).testTag("$tag-glyph"), tint = tint)
        }
        if (label != null) Text(label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
    }
}

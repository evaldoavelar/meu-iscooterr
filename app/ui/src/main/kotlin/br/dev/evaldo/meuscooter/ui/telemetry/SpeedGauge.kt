package br.dev.evaldo.meuscooter.ui.telemetry

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import br.dev.evaldo.meuscooter.ui.theme.AppIcons
import br.dev.evaldo.meuscooter.ui.theme.AppTheme

private const val START_ANGLE = 150f   // arco de 240° aberto embaixo (design/telemetry.py: 210° → -30°)
private const val SWEEP = 240f

/** Velocímetro 0–60 km/h: arco segmentado + neon externo contínuo (decisão de UX do proprietário). */
@Composable
fun SpeedGauge(speedText: String, speedUnit: String, maxText: String, fraction: Float, modifier: Modifier = Modifier) {
    val primary = MaterialTheme.colorScheme.primary
    val track = AppTheme.extended.track
    Box(modifier.fillMaxWidth().aspectRatio(1.25f).testTag("speed-gauge"), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxWidth().aspectRatio(1.25f)) {
            val w = 14.dp.toPx()
            val inset = 40.dp.toPx()
            val diameter = size.height - inset
            val topLeft = Offset((size.width - diameter) / 2f, inset / 2f)
            val arcSize = Size(diameter, diameter)
            val dashed = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx()))
            drawArc(track, START_ANGLE, SWEEP, false, topLeft, arcSize, style = Stroke(w, pathEffect = dashed))
            if (fraction > 0f) drawArc(primary, START_ANGLE, SWEEP * fraction, false, topLeft, arcSize, style = Stroke(w, pathEffect = dashed))
            val outerPad = 17.dp.toPx() * 2
            val outerTl = Offset(topLeft.x - outerPad / 2, topLeft.y - outerPad / 2)
            val outerSize = Size(diameter + outerPad, diameter + outerPad)
            val glow = maxOf(fraction, 0.02f)
            drawArc(track, START_ANGLE, SWEEP, false, outerTl, outerSize, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round))
            drawArc(primary.copy(alpha = 0.35f), START_ANGLE, SWEEP * glow, false, outerTl, outerSize, style = Stroke(11.dp.toPx(), cap = StrokeCap.Round))
            drawArc(primary, START_ANGLE, SWEEP * glow, false, outerTl, outerSize, style = Stroke(5.dp.toPx(), cap = StrokeCap.Round))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(speedText, Modifier.testTag("speed-value"), fontSize = 56.sp, fontWeight = FontWeight.Bold)
                Text(speedUnit, Modifier.padding(start = 4.dp, bottom = 10.dp), fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = AppTheme.extended.textSecondary)
            }
            Text("Velocidade atual", style = MaterialTheme.typography.bodySmall, color = AppTheme.extended.textSecondary)
        }
        Icon(AppIcons.Scooter, contentDescription = null, Modifier.align(Alignment.BottomCenter).padding(bottom = 6.dp).size(40.dp).testTag("gauge-scooter"), tint = primary)
        Text("0", Modifier.align(Alignment.BottomStart).padding(start = 48.dp), style = MaterialTheme.typography.bodySmall, color = AppTheme.extended.textSecondary)
        Text(maxText, Modifier.align(Alignment.BottomEnd).padding(end = 48.dp), style = MaterialTheme.typography.bodySmall, color = AppTheme.extended.textSecondary)
    }
}

/** Pílula de bateria sob o velocímetro (design/telemetry.py: 180×38 dp, raio 19 dp). */
@Composable
fun BatteryPill(batteryText: String, modifier: Modifier = Modifier) {
    val isKnown = batteryText != "--" && batteryText.isNotEmpty()
    val borderColor = if (isKnown) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
    val iconColor = if (isKnown) AppTheme.extended.success else AppTheme.extended.textSecondary
    val textColor = if (isKnown) MaterialTheme.colorScheme.onSurface else AppTheme.extended.textSecondary

    Box(
        modifier = modifier
            .size(width = 180.dp, height = 38.dp)
            .border(1.dp, borderColor, RoundedCornerShape(19.dp))
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(19.dp))
            .semantics { contentDescription = "Bateria $batteryText" }
            .testTag("battery-pill"),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                AppIcons.Battery,
                contentDescription = null,
                tint = iconColor,
                modifier = Modifier.size(24.dp).testTag("battery-icon"),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                batteryText,
                Modifier.testTag("battery-value"),
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = textColor,
            )
            Spacer(Modifier.weight(1f))
            Box(
                Modifier
                    .width(1.dp)
                    .height(18.dp)
                    .background(MaterialTheme.colorScheme.outline)
            )
            Spacer(Modifier.width(11.dp))
            Text(
                "Bateria",
                fontSize = 13.sp,
                color = AppTheme.extended.textSecondary,
            )
            Spacer(Modifier.width(4.dp))
        }
    }
}

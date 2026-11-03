package br.dev.evaldo.meuscooter.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/** Ícones de traço (24x24) com os mesmos caminhos de `design/lib.py` ICONS. */
object AppIcons {
    private fun icon(name: String, d: String, width: Float = 2f): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).addPath(
            pathData = PathParser().parsePathString(d).toNodes(),
            stroke = SolidColor(Color.Black), strokeLineWidth = width,
            strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
        ).build()

    val More = icon("more", "M12 5v.01 M12 12v.01 M12 19v.01", width = 3.5f)
    val Bluetooth = icon("bluetooth", "M7 7l10 10-5 5V2l5 5L7 17")
    val Battery = icon("battery", "M9 3h6v2h2v16H7V5h2z M10 12h4 M10 16h4")
    val Speed = icon("speed", "M4 18a9 9 0 1 1 16 0 M12 15l4-6")
    val Pin = icon("pin", "M12 21s6-5.5 6-11a6 6 0 1 0-12 0c0 5.5 6 11 6 11z M12 8a2 2 0 1 0 .001 0")
    val Light = icon("light", "M10 6h3a6 6 0 0 1 0 12h-3z M3 8h4 M3 12h4 M3 16h4")
    val Motor = icon("motor", "M6 7h12v10H6z M3 10v4 M21 10v4 M9 4h6 M10 12h4")
    val Cruise = icon("cruise", "M12 3a9 9 0 1 0 .001 0z M12 12l4-4 M12 6v1.5")
    val Rocket = icon("rocket", "M12 3c3 2 5 5 5 9l-2 3H9l-2-3c0-4 2-7 5-9z M9 15l-3 3 M15 15l3 3 M12 9a1 1 0 1 0 .001 0")
    val Lock = icon("lock", "M6 11h12v9H6z M8 11V8a4 4 0 0 1 8 0v3")
    val Unlock = icon("unlock", "M6 11h12v9H6z M8 11V8a4 4 0 0 1 7.6-1.8")
    val Chevron = icon("chev", "M9 6l6 6-6 6")
    val Check = icon("check", "M5 12l5 5 9-10")
    val Alert = icon("alert", "M12 4l9 16H3z M12 10v4 M12 17v.1")
    val Spin = icon("spin", "M12 3a9 9 0 1 1-9 9")
}

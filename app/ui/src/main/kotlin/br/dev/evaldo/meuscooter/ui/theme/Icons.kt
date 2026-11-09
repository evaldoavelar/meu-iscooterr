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

    /** Pedestre preenchido (Material `directions_walk`, Apache 2.0), 24x24. */
    val Walk: ImageVector = ImageVector.Builder("walk", 24.dp, 24.dp, 24f, 24f).addPath(
        pathData = PathParser().parsePathString(
            "M13.5 5.5c1.1 0 2-.9 2-2s-.9-2-2-2-2 .9-2 2 .9 2 2 2zM9.8 8.9L7 23h2.1l1.8-8 2.1 2v6h2v-7.5l-2.1-2 .6-3C14.8 12 16.8 13 19 13v-2c-1.9 0-3.5-1-4.3-2.4l-1-1.6c-.4-.6-1-1-1.7-1-.3 0-.5.1-.8.1L6 8.3V13h2V9.6l1.8-.7z",
        ).toNodes(),
        fill = SolidColor(Color.Black),
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
    val Fingerprint = icon(
        "fingerprint",
        "M12 3a9 9 0 0 1 9 9 M3 12a9 9 0 0 1 4-7.5 M7 12a5 5 0 0 1 10 0v2 M12 12v3a6 6 0 0 1-1.5 4 " +
            "M9 12a3 3 0 0 1 6 0v3a9 9 0 0 1-2 5.5 M7 14v1a8 8 0 0 1-1.5 4.5 M17 17a12 12 0 0 1-1 2.5",
        width = 1.5f,
    )
    val Backspace = icon("backspace", "M9 5h11v14H9l-6-7z M12 9l5 6 M17 9l-5 6")
    val Chevron = icon("chev", "M9 6l6 6-6 6")
    val Check = icon("check", "M5 12l5 5 9-10")
    val Alert = icon("alert", "M12 4l9 16H3z M12 10v4 M12 17v.1")
    val Spin = icon("spin", "M12 3a9 9 0 1 1-9 9")
    val Thermo = icon("thermo", "M10 14V5a2 2 0 0 1 4 0v9a4 4 0 1 1-4 0z M12 9v8")
    val Bolt = icon("bolt", "M13 3L6 13h5l-1 8 7-10h-5z")
    val Pulse = icon("pulse", "M3 12h4l2-6 4 12 2-6h6")
    val Route = icon("route", "M5 19a2 2 0 1 0 .001 0 M19 5a2 2 0 1 0 .001 0 M7 19h6a3 3 0 0 0 0-6h-2a3 3 0 0 1 0-6h4")
    val Clock = icon("clock", "M12 4a8 8 0 1 0 .001 0 M12 8v4l3 2")
    val Mountain = icon("mountain", "M3 19l6-10 4 6 3-4 5 8z")
    val Trend = icon("trend", "M4 16l5-5 4 4 7-8 M15 7h5v5")
    val Scooter = icon("scooter", "M1.5 17a2.5 2.5 0 1 0 5 0 2.5 2.5 0 1 0-5 0 M17.5 17a2.5 2.5 0 1 0 5 0 2.5 2.5 0 1 0-5 0 M6.5 17H13.5L18 6.5 M16 6.5h4 M17 9.5l2.8 5.2")
}

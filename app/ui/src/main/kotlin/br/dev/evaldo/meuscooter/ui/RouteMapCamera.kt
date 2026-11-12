package br.dev.evaldo.meuscooter.ui

/** Câmera relativa ao trajeto inteiro; offsets em pixels. Não depende de Compose ou de localização. */
data class RouteMapCamera(val scale: Float = 1f, val offsetX: Float = 0f, val offsetY: Float = 0f) {
    val canRecenter: Boolean get() = scale != 1f || offsetX != 0f || offsetY != 0f

    fun recenter() = RouteMapCamera()

    /** Mantém o ponto sob o centro da pinça/duplo toque; arrasto limitado a meia área ampliada por eixo. */
    fun transform(
        factor: Float, panX: Float, panY: Float, width: Float, height: Float,
        focusX: Float = width / 2f, focusY: Float = height / 2f, maxScale: Float = 16f,
    ): RouteMapCamera {
        if (!factor.isFinite() || factor <= 0f || !panX.isFinite() || !panY.isFinite() ||
            !focusX.isFinite() || !focusY.isFinite() || width <= 0f || height <= 0f) return this
        val next = (scale * factor).coerceIn(1f, maxScale.coerceIn(1f, 16f))
        val ratio = next / scale
        val x = (offsetX - (focusX - width / 2f)) * ratio + (focusX - width / 2f) + panX
        val y = (offsetY - (focusY - height / 2f)) * ratio + (focusY - height / 2f) + panY
        return RouteMapCamera(next, x.coerceIn(-width * next / 2f, width * next / 2f),
            y.coerceIn(-height * next / 2f, height * next / 2f))
    }
}

/** Maior distância legível (1/2/5 × potência de dez) que cabe no alvo da barra. */
fun mapScaleDistance(targetMeters: Double): Double {
    if (!targetMeters.isFinite() || targetMeters <= 0.0) return 1.0
    val power = Math.pow(10.0, kotlin.math.floor(kotlin.math.log10(targetMeters)))
    val mantissa = targetMeters / power
    return power * when { mantissa >= 5.0 -> 5.0; mantissa >= 2.0 -> 2.0; else -> 1.0 }
}

fun formatMapScale(meters: Double): String {
    val value = if (meters >= 1000.0) meters / 1000.0 else meters
    val number = java.text.DecimalFormat("0.##", java.text.DecimalFormatSymbols(java.util.Locale.forLanguageTag("pt-BR"))).format(value)
    return "$number ${if (meters >= 1000.0) "km" else "m"}"
}

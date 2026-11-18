package br.dev.evaldo.meuscooter.ui

import br.dev.evaldo.meuscooter.ui.history.THUMB_BLUR_RADIUS_DP
import br.dev.evaldo.meuscooter.ui.history.THUMB_SOFTEN_DIVISOR
import br.dev.evaldo.meuscooter.ui.history.thumbMapColorMatrix
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThumbMapStyleTest {
    private fun apply(m: FloatArray, r: Float, g: Float, b: Float) = FloatArray(3) { i ->
        m[i * 5] * r + m[i * 5 + 1] * g + m[i * 5 + 2] * b + m[i * 5 + 4]
    }

    @Test fun `desfoque leve para as ruas aparecerem`() {
        assertTrue(THUMB_BLUR_RADIUS_DP in 0.5f..2f)
        assertTrue(THUMB_SOFTEN_DIVISOR in 2..8)
    }

    @Test fun `contraste puro fixa o cinza medio e afasta claros e escuros`() {
        val m = thumbMapColorMatrix(contrast = 1.5f, saturation = 1f)
        assertEquals(listOf(127.5f, 127.5f, 127.5f), apply(m, 127.5f, 127.5f, 127.5f).toList())
        assertEquals(161.25f, apply(m, 150f, 150f, 150f)[0], 0.01f)
        assertEquals(86.25f, apply(m, 100f, 100f, 100f)[0], 0.01f)
    }

    @Test fun `saturacao 0 gera cinza e alfa fica intacto`() {
        val m = thumbMapColorMatrix(contrast = 1f, saturation = 0f)
        val out = apply(m, 200f, 100f, 0f)
        assertEquals(out[0], out[1], 0.01f); assertEquals(out[1], out[2], 0.01f)
        assertEquals(0.213f * 200 + 0.715f * 100, out[0], 0.01f)
        assertEquals(listOf(0f, 0f, 0f, 1f, 0f), m.slice(15..19))
    }
}

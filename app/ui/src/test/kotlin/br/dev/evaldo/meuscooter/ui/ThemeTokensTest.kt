package br.dev.evaldo.meuscooter.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import br.dev.evaldo.meuscooter.ui.home.switchPalette
import org.junit.Assert.assertTrue
import br.dev.evaldo.meuscooter.ui.theme.AppColors
import br.dev.evaldo.meuscooter.ui.theme.resolveDark
import org.junit.Assert.assertEquals
import org.junit.Test

/** Valores esperados copiados de `design/lib.py` COLORS (claro, escuro) — fonte independente. */
class ThemeTokensTest {
    @Test fun `tokens claros seguem o FigCraft`() {
        val c = AppColors.light
        assertEquals(Color(0xFFF4F7FB), c.scheme.background); assertEquals(Color(0xFFFFFFFF), c.scheme.surface)
        assertEquals(Color(0xFF00A9C0), c.scheme.primary); assertEquals(Color(0xFF00262B), c.scheme.onPrimary)
        assertEquals(Color(0xFF0E1726), c.scheme.onBackground); assertEquals(Color(0xFFD3DCE8), c.scheme.outline)
        assertEquals(Color(0xFF0E9F63), c.extended.success); assertEquals(Color(0xFFE67E00), c.extended.pending)
        assertEquals(Color(0xFFD32F2F), c.extended.error); assertEquals(Color(0xFFC9D3E0), c.extended.disabled)
        assertEquals(Color(0xFF6B7B92), c.extended.onDisabled); assertEquals(Color(0xFF101820), c.extended.onColor)
        assertEquals(Color(0xFF52627A), c.extended.textSecondary); assertEquals(Color(0xFFE3EAF3), c.extended.track)
    }

    @Test fun `tokens escuros seguem o FigCraft`() {
        val c = AppColors.dark
        assertEquals(Color(0xFF0B1220), c.scheme.background); assertEquals(Color(0xFF131C2B), c.scheme.surface)
        assertEquals(Color(0xFF00E5FF), c.scheme.primary); assertEquals(Color(0xFFF2F6FA), c.scheme.onBackground)
        assertEquals(Color(0xFF22304A), c.scheme.outline)
        assertEquals(Color(0xFF19D68A), c.extended.success); assertEquals(Color(0xFFFF9800), c.extended.pending)
        assertEquals(Color(0xFFFF5252), c.extended.error); assertEquals(Color(0xFF2A3547), c.extended.disabled)
        assertEquals(Color(0xFF7C8BA3), c.extended.onDisabled); assertEquals(Color(0xFF9AA8BC), c.extended.textSecondary)
    }

    @Test fun `modo de tema resolve escuro conforme o sistema`() {
        assertEquals(true, br.dev.evaldo.meuscooter.domain.ThemeMode.Dark.resolveDark(systemDark = false))
        assertEquals(false, br.dev.evaldo.meuscooter.domain.ThemeMode.Light.resolveDark(systemDark = true))
        assertEquals(true, br.dev.evaldo.meuscooter.domain.ThemeMode.System.resolveDark(systemDark = true))
        assertEquals(false, br.dev.evaldo.meuscooter.domain.ThemeMode.System.resolveDark(systemDark = false))
    }

    @Test fun `toggle ligado usa a cor de estado e desligado tem trilho visivel no tema claro`() {
        val ok = AppColors.light.extended.success
        val p = switchPalette(ok, AppColors.light.scheme, AppColors.light.extended)
        assertEquals(ok, p.checkedTrack); assertEquals(ok, p.checkedBorder)
        assertEquals(AppColors.light.scheme.background, p.checkedThumb)
        assertTrackIsTranslucent(p.uncheckedTrack, Color(0xFF52627A))
        assertEquals(AppColors.light.scheme.onSurface, p.uncheckedThumb)
        assertEquals(Color.Transparent, p.uncheckedBorder)
        // trilho desligado precisa se destacar do fundo/cartao claros: contraste >= 1.5 apos composicao
        val track = p.uncheckedTrack.compositeOver(AppColors.light.scheme.surface)
        assertTrue(contrast(track, AppColors.light.scheme.surface) >= 1.5)
        assertTrue(contrast(p.uncheckedThumb, track) >= 3.0)
    }

    @Test fun `toggle no tema escuro mantem trilho desligado distinto do cartao e polegar legivel`() {
        val d = AppColors.dark
        val p = switchPalette(d.scheme.primary, d.scheme, d.extended)
        assertEquals(d.scheme.primary, p.checkedTrack)
        assertTrackIsTranslucent(p.uncheckedTrack, Color(0xFF9AA8BC))
        val track = p.uncheckedTrack.compositeOver(d.scheme.surface)
        assertTrue(contrast(track, d.scheme.surface) >= 1.5)
        assertTrue(contrast(p.uncheckedThumb, track) >= 3.0)
    }

    /** Referencia independente: mesma cor RGB do texto secundario (literal do FigCraft), translucida (0.4..0.7), nunca opaca. */
    private fun assertTrackIsTranslucent(track: Color, rgb: Color) {
        assertEquals(rgb.red, track.red, 1e-6f); assertEquals(rgb.green, track.green, 1e-6f); assertEquals(rgb.blue, track.blue, 1e-6f)
        assertTrue("alfa ${track.alpha}", track.alpha in 0.4f..0.7f)
    }

    private fun contrast(a: Color, b: Color): Double {
        val la = a.luminance() + 0.05; val lb = b.luminance() + 0.05
        return maxOf(la, lb) / minOf(la, lb)
    }
}

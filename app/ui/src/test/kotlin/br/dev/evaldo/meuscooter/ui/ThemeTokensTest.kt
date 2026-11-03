package br.dev.evaldo.meuscooter.ui

import androidx.compose.ui.graphics.Color
import br.dev.evaldo.meuscooter.ui.theme.AppColors
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
}

package br.dev.evaldo.meuscooter.ui

import androidx.compose.material3.SwitchColors
import androidx.compose.material3.SwitchDefaults
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import br.dev.evaldo.meuscooter.ui.home.appSwitchColors
import br.dev.evaldo.meuscooter.ui.theme.MeuPatineteTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Exercita [appSwitchColors] de fato: o `SwitchColors` devolvido deve ser igual (equals por campo) ao
 * montado aqui com cores LITERAIS de `design/lib.py` — verifica o encaminhamento checked/unchecked de
 * trilho, polegar e borda, nos temas claro e escuro.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class SwitchColorsTest {
    @get:Rule val rule = createComposeRule()

    private fun render(dark: Boolean, checked: Color, expected: @androidx.compose.runtime.Composable (Color) -> SwitchColors): Pair<SwitchColors, SwitchColors> {
        lateinit var actual: SwitchColors
        lateinit var want: SwitchColors
        rule.setContent {
            MeuPatineteTheme(darkTheme = dark) { actual = appSwitchColors(checked); want = expected(checked) }
        }
        rule.waitForIdle()
        return actual to want
    }

    @Test fun `tema claro encaminha trilho polegar e borda`() {
        val ok = Color(0xFF0E9F63)
        val (actual, want) = render(false, ok) {
            SwitchDefaults.colors(
                checkedTrackColor = it, checkedThumbColor = Color(0xFFF4F7FB), checkedBorderColor = it,
                uncheckedTrackColor = Color(0xFF52627A).copy(alpha = 0.55f), uncheckedThumbColor = Color(0xFF0E1726),
                uncheckedBorderColor = Color.Transparent,
            )
        }
        assertEquals(want, actual)
    }

    @Test fun `tema escuro encaminha trilho polegar e borda`() {
        val prim = Color(0xFF00E5FF)
        val (actual, want) = render(true, prim) {
            SwitchDefaults.colors(
                checkedTrackColor = it, checkedThumbColor = Color(0xFF0B1220), checkedBorderColor = it,
                uncheckedTrackColor = Color(0xFF9AA8BC).copy(alpha = 0.55f), uncheckedThumbColor = Color(0xFFF2F6FA),
                uncheckedBorderColor = Color.Transparent,
            )
        }
        assertEquals(want, actual)
    }
}

package br.dev.evaldo.meuscooter.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.compose.ui.unit.Density
import br.dev.evaldo.meuscooter.protocol.Telemetry
import br.dev.evaldo.meuscooter.ui.telemetry.TelemetryScreen
import br.dev.evaldo.meuscooter.ui.theme.MeuPatineteTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** S52: cartão de aviso de Economia de energia (design/mockups/telemetria-aviso-economia-v1-*.png). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class EnergySavingWarningUiTest {
    @get:Rule val rule = createComposeRule()

    private val reading = Telemetry(receivedAtMs = 1, speedKmh = 18f, scooterSpeedKmh = 18f, batteryPercent = 82, voltageV = 54.9f, currentA = 8.4f)
    private fun state(warn: Boolean) = TelemetryUiMapper().map(reading, null, recState = br.dev.evaldo.meuscooter.domain.TripRecState.Running, energySavingImpairsGps = warn)
    private var opened = 0

    private fun show(warn: Boolean = true, dark: Boolean = false, fontScale: Float = 1f) = rule.setContent {
        MeuPatineteTheme(darkTheme = dark) {
            val d = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(d.density, fontScale)) {
                TelemetryScreen(state(warn), onBack = {}, onOpenHistory = {}, onOpenBatterySaverSettings = { opened++ })
            }
        }
    }

    private fun bounds(tag: String): Rect = rule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot

    @Test fun `mostra os dois textos do mockup`() {
        show()
        rule.onNodeWithText("Economia de energia ligada").assertIsDisplayed()
        rule.onNodeWithText("O GPS não funciona com a tela apagada").assertIsDisplayed()
    }

    @Test fun `sem o aviso o cartao nao existe`() {
        show(warn = false)
        rule.onNodeWithTag("energy-saving-warning").assertDoesNotExist()
    }

    @Test fun `tem 328 por 52 dp e fica entre a pilula de bateria e o cartao Duracao`() {
        show()
        val b = bounds("energy-saving-warning")
        val density = rule.density.density
        assertEquals(52f, b.height / density, 0.5f)
        assertTrue("largura ${b.width / density}", b.width / density in 320f..380f)
        assertTrue(bounds("battery-pill").bottom <= b.top)
        assertTrue(b.bottom <= bounds("duration-card").top)
    }

    @Test fun `toque abre os ajustes de bateria`() {
        opened = 0
        show()
        rule.onNodeWithTag("energy-saving-warning").performClick()
        assertEquals(1, opened)
    }

    @Test fun `toque longo tambem abre os ajustes de bateria`() {
        opened = 0
        show()
        rule.onNodeWithTag("energy-saving-warning").performTouchInput { longClick() }
        assertEquals(1, opened)
    }

    @Test fun `acessibilidade tem papel de botao, descricao completa e rotulo de toque longo`() {
        show()
        val cfg = rule.onNodeWithTag("energy-saving-warning").fetchSemanticsNode().config
        assertEquals(Role.Button, cfg[SemanticsProperties.Role])
        assertEquals(
            listOf("Economia de energia ligada. O GPS não funciona com a tela apagada. Toque para abrir os ajustes de bateria"),
            cfg[SemanticsProperties.ContentDescription],
        )
        assertEquals(
            "Economia de energia ligada. O GPS não funciona com a tela apagada. Toque para abrir os ajustes de bateria",
            cfg[SemanticsActions.OnLongClick].label,
        )
    }

    @Test fun `fonte 2,0 nao corta nem sobrepoe o cartao seguinte`() {
        show(fontScale = 2f)
        rule.onNodeWithTag("energy-saving-warning").performScrollTo()
        val w = bounds("energy-saving-warning")
        rule.onNodeWithText("Economia de energia ligada").assertIsDisplayed()
        rule.onNodeWithText("O GPS não funciona com a tela apagada").assertIsDisplayed()
        val title = rule.onNodeWithText("Economia de energia ligada", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val sub = rule.onNodeWithText("O GPS não funciona com a tela apagada", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue("textos dentro do cartão", title.left >= w.left && title.right <= w.right && sub.left >= w.left && sub.right <= w.right)
        assertTrue("título acima do subtítulo: $title $sub $w", title.bottom <= sub.top + 1f)
        assertTrue("textos dentro na vertical", title.top >= w.top && sub.bottom <= w.bottom)
        assertTrue(w.bottom <= bounds("duration-card").top)
    }

    @Test fun `tema escuro tambem renderiza o cartao`() {
        show(dark = true)
        rule.onNodeWithTag("energy-saving-warning").assertIsDisplayed()
    }
}

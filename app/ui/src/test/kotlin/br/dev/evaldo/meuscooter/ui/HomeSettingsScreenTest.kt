package br.dev.evaldo.meuscooter.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertRangeInfoEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import br.dev.evaldo.meuscooter.domain.HomeState
import br.dev.evaldo.meuscooter.protocol.ConnectionStatus
import br.dev.evaldo.meuscooter.protocol.Telemetry
import br.dev.evaldo.meuscooter.ui.home.HomeActions
import br.dev.evaldo.meuscooter.ui.home.HomeScreen
import br.dev.evaldo.meuscooter.ui.theme.MeuPatineteTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** S31 (ADR 0009): cards de unidade, modo, limite (teto 60) e tracao (confirmacao Sim/Nao) com o transporte real. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class HomeSettingsScreenTest {
    @get:Rule val rule = createComposeRule()

    private val mapper = HomeUiMapper(emptyList(), "iX7 Pro") { "14:32" }
    private val t = Telemetry(
        receivedAtMs = 1_000, speedKmh = 0f, locked = false, dualMotor = false, speedUnitMph = false,
        availableModes = 0b0111, speedModeRaw = 1, speedLimitKmh = 25, batteryPercent = 80, totalKm = 100f,
    )

    private fun ui(tel: Telemetry = t) = mapper.map(
        HomeState(
            ConnectionStatus.Connected, tel, 1_000, meter1AtMs = 1_000, meter2AtMs = 1_000, commandsAvailable = false,
            headlightAvailable = true, unitAvailable = true, driveModeAvailable = true, speedLimitAvailable = true, driveAvailable = true,
        ),
    )

    private fun show(state: HomeUiState, actions: HomeActions = HomeActions()) =
        rule.setContent { MeuPatineteTheme(darkTheme = false) { HomeScreen(state, actions) } }

    @Test fun `unidade envia o raw escolhido`() {
        var got = -1
        show(ui(), HomeActions(onUnit = { got = it }))
        rule.onNodeWithTag("unit-card").performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag("unit-1").performScrollTo().performClick()
        assertEquals(1, got)
    }

    @Test fun `modo so mostra os da mascara`() {
        show(ui())
        rule.onNodeWithTag("mode-2").performScrollTo().assertIsDisplayed()
        assertTrue(rule.onAllNodesWithTagCount("mode-3") == 0)
    }

    @Test fun `tracao so envia depois do Sim e Nao cancela`() {
        var got = -1
        show(ui(), HomeActions(onMotors = { got = it }))
        rule.onNodeWithTag("motors-1").performScrollTo().performClick()
        assertEquals(-1, got)
        rule.onNodeWithTag("motors-dialog").assertIsDisplayed()
        rule.onNodeWithTag("motors-dialog-no").performClick()
        assertEquals(-1, got)
        rule.onNodeWithTag("motors-1").performClick()
        rule.onNodeWithTag("motors-dialog-yes").performClick()
        assertEquals(1, got)
    }

    @Test fun `limite com 1 motor tem barra ate 60 e acima de 50 desabilitado com dica`() {
        var got = mutableListOf<Int>()
        show(ui(t.copy(speedLimitKmh = 50)), HomeActions(onLimit = { got += it }))
        rule.onNodeWithTag("limit-slider").performScrollTo().assertRangeInfoEquals(ProgressBarRangeInfo(50f, 20f..60f, 0))
        rule.onNodeWithTag("limit-plus").assertIsNotEnabled()
        rule.onNodeWithTag("limit-hint").assertIsDisplayed()
        assertTrue(got.isEmpty())
    }

    @Test fun `limite com 2 motores chega a 60 e o minimo e 15`() {
        val got = mutableListOf<Int>()
        show(ui(t.copy(dualMotor = true, speedLimitKmh = 55)), HomeActions(onLimit = { got += it }))
        rule.onNodeWithTag("limit-plus").performScrollTo().assertIsEnabled().performClick()
        assertEquals(listOf(60), got)
        rule.onNodeWithTag("limit-slider").assertRangeInfoEquals(ProgressBarRangeInfo(60f, 15f..60f, 0))
    }

    @Test fun `limite em mph fica desabilitado`() {
        show(ui(t.copy(speedUnitMph = true, speedLimitKmh = 31)))
        rule.onNodeWithTag("limit-slider").performScrollTo().assertIsNotEnabled()
        rule.onNodeWithTag("limit-plus").assertIsNotEnabled()
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTagCount(tag: String) =
        onAllNodes(androidx.compose.ui.test.hasTestTag(tag)).fetchSemanticsNodes().size
}

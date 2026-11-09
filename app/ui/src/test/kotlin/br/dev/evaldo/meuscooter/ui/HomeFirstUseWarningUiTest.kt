package br.dev.evaldo.meuscooter.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import br.dev.evaldo.meuscooter.domain.HomeState
import br.dev.evaldo.meuscooter.protocol.ConnectionStatus
import br.dev.evaldo.meuscooter.protocol.Telemetry
import br.dev.evaldo.meuscooter.ui.home.FirstUseWarning
import br.dev.evaldo.meuscooter.ui.home.HomeActions
import br.dev.evaldo.meuscooter.ui.home.HomeScreen
import br.dev.evaldo.meuscooter.ui.theme.MeuPatineteTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Aviso de 1º uso do Cruise e da Partida do zero (S34, ADR 0010): confirma/cancela; cancelar não envia nada; confirmado não repete. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class HomeFirstUseWarningUiTest {
    @get:Rule val rule = createComposeRule()
    private val mapper = HomeUiMapper(emptyList(), "iX7 Pro") { "14:32" }
    private val log = mutableListOf<String>()
    private val actions = HomeActions(
        onCruise = { log += "cruise=$it" }, onZeroStart = { log += "zero=$it" }, onAckWarning = { log += "ack=${it.name}" },
    )

    private fun state(zeroStartOn: Boolean = false, cruiseOn: Boolean = false, speed: Float = 0f): HomeUiState {
        val t = Telemetry(receivedAtMs = 1_000, locked = false, speedKmh = speed, batteryPercent = 82, headlightOn = true, dualMotor = false,
            speedModeRaw = 1, cruiseOn = cruiseOn, zeroStartOn = zeroStartOn, speedLimitKmh = 25, totalKm = 10f)
        return mapper.map(HomeState(ConnectionStatus.Connected, t, 1_000, emptyMap(), emptyMap(), meter1AtMs = 1_000, meter2AtMs = 1_000))
    }

    private fun show(acked: Set<FirstUseWarning>, zeroStartOn: Boolean = false, cruiseOn: Boolean = false, speed: Float = 0f) {
        val state = state(zeroStartOn, cruiseOn, speed)
        rule.setContent { MeuPatineteTheme { HomeScreen(state, actions, warningsAcked = acked) } }
    }

    private fun tap(tag: String) = rule.onNodeWithTag(tag).performScrollTo().performClick()

    @Test fun `primeira vez no cruise mostra o aviso e nao envia antes de confirmar`() {
        show(emptySet()); tap("cruise")
        rule.onNodeWithTag("warning-dialog").assertIsDisplayed()
        assertEquals(emptyList<String>(), log)
    }

    @Test fun `confirmar guarda o aviso e envia o cruise`() {
        show(emptySet()); tap("cruise"); rule.onNodeWithTag("warning-confirm").performClick()
        assertEquals(listOf("ack=Cruise", "cruise=true"), log)
        rule.onNodeWithTag("warning-dialog").assertDoesNotExist()
    }

    @Test fun `cancelar nao envia nem guarda`() {
        show(emptySet()); tap("cruise"); rule.onNodeWithTag("warning-cancel").performClick()
        assertEquals(emptyList<String>(), log)
        rule.onNodeWithTag("warning-dialog").assertDoesNotExist()
    }

    @Test fun `partida do zero tem o proprio aviso`() {
        show(emptySet(), zeroStartOn = false); tap("zero-start"); rule.onNodeWithTag("warning-confirm").performClick()
        assertEquals(listOf("ack=ZeroStart", "zero=true"), log)
    }

    @Test fun `ja confirmado envia direto sem dialogo`() {
        show(setOf(FirstUseWarning.Cruise)); tap("cruise")
        rule.onNodeWithTag("warning-dialog").assertDoesNotExist()
        assertEquals(listOf("cruise=true"), log)
    }

    @Test fun `desligar a partida do zero sem ack abre o aviso e so envia ao confirmar`() {
        show(emptySet(), zeroStartOn = true); tap("zero-start")
        rule.onNodeWithTag("warning-dialog").assertIsDisplayed()
        assertEquals(emptyList<String>(), log)
        rule.onNodeWithTag("warning-confirm").performClick()
        assertEquals(listOf("ack=ZeroStart", "zero=false"), log)
    }

    @Test fun `desligar o cruise nao pede aviso`() {
        show(emptySet(), cruiseOn = true); tap("cruise")
        rule.onNodeWithTag("warning-dialog").assertDoesNotExist()
        assertEquals(listOf("cruise=false"), log)
    }

    @Test fun `ack da partida do zero nao dispensa o aviso do cruise`() {
        show(setOf(FirstUseWarning.ZeroStart)); tap("cruise")
        rule.onNodeWithTag("warning-dialog").assertIsDisplayed()
        assertEquals(emptyList<String>(), log)
    }

    @Test fun `controle desabilitado nao abre o aviso`() {
        show(emptySet(), speed = 15f); tap("cruise"); tap("zero-start")
        rule.onNodeWithTag("warning-dialog").assertDoesNotExist()
        assertEquals(emptyList<String>(), log)
    }

    @Test fun `aviso fecha e nao grava se o controle ficar desabilitado`() {
        var speed by mutableStateOf(0f)
        rule.setContent { MeuPatineteTheme { HomeScreen(state(speed = speed), actions, warningsAcked = emptySet()) } }
        tap("cruise")
        rule.onNodeWithTag("warning-dialog").assertIsDisplayed()
        speed = 15f; rule.waitForIdle()
        rule.onNodeWithTag("warning-dialog").assertDoesNotExist()
        assertEquals(emptyList<String>(), log)
    }
}

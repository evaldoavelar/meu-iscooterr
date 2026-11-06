package br.dev.evaldo.meuscooter.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import br.dev.evaldo.meuscooter.domain.HomeState
import br.dev.evaldo.meuscooter.protocol.ConnectionStatus
import br.dev.evaldo.meuscooter.ui.home.HomeActions
import br.dev.evaldo.meuscooter.ui.home.HomeScreen
import br.dev.evaldo.meuscooter.ui.theme.MeuPatineteTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Pressionar e segurar o chip de conexão abre um diálogo Sim/Não conforme o estado; toque curto segue como antes (S22). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class HomeConnectionMenuUiTest {
    @get:Rule val rule = createComposeRule()
    private val mapper = HomeUiMapper(emptyList(), "iX7 Pro") { "14:32" }
    private val picked = mutableListOf<ConnectionMenuAction>()
    private var taps = 0

    private fun show(status: ConnectionStatus) {
        val state = mapper.map(HomeState(connection = status, nowMs = 1_000))
        rule.setContent {
            MeuPatineteTheme { HomeScreen(state, HomeActions(onConnectionChip = { taps++ }, onConnectionMenu = { picked += it })) }
        }
    }

    private fun longPress() = rule.onNodeWithTag("connection-chip").performTouchInput { longClick() }

    private fun yes() = rule.onNodeWithTag("connection-dialog-yes").performClick()
    private fun no() = rule.onNodeWithTag("connection-dialog-no").performClick()

    @Test fun connectedAsksToDisconnectAndYesRuns() {
        show(ConnectionStatus.Connected); longPress()
        rule.onNodeWithText("Deseja desconectar o patinete?").assertIsDisplayed()
        rule.onNodeWithText("Sim").assertIsDisplayed(); rule.onNodeWithText("Não").assertIsDisplayed()
        assertEquals(emptyList<ConnectionMenuAction>(), picked)
        yes()
        assertEquals(listOf(ConnectionMenuAction.Disconnect), picked)
        rule.onNodeWithTag("connection-dialog").assertDoesNotExist()
        assertEquals(0, taps)
    }

    @Test fun disconnectedAsksToConnect() {
        show(ConnectionStatus.Disconnected); longPress()
        rule.onNodeWithText("Deseja conectar o patinete?").assertIsDisplayed()
        yes()
        assertEquals(listOf(ConnectionMenuAction.Connect), picked)
    }

    @Test fun connectingAsksToCancel() {
        show(ConnectionStatus.Connecting); longPress()
        rule.onNodeWithText("Deseja cancelar a conexão?").assertIsDisplayed()
        yes()
        assertEquals(listOf(ConnectionMenuAction.Cancel), picked)
    }

    @Test fun noClosesWithoutEffect() {
        show(ConnectionStatus.Connected); longPress(); no()
        rule.onNodeWithTag("connection-dialog").assertDoesNotExist()
        assertEquals(emptyList<ConnectionMenuAction>(), picked)
        assertEquals(0, taps)
    }

    @Test fun stateChangeWhileOpenClosesDialogAndNeverRunsStaleAction() {
        var status by mutableStateOf(ConnectionStatus.Connected)
        rule.setContent {
            val state = mapper.map(HomeState(connection = status, nowMs = 1_000))
            MeuPatineteTheme { HomeScreen(state, HomeActions(onConnectionChip = { taps++ }, onConnectionMenu = { picked += it })) }
        }
        longPress()
        rule.onNodeWithText("Deseja desconectar o patinete?").assertIsDisplayed()
        status = ConnectionStatus.Disconnected
        rule.waitForIdle()
        rule.onNodeWithTag("connection-dialog").assertDoesNotExist()
        assertEquals(emptyList<ConnectionMenuAction>(), picked)
    }

    @Test fun shortTapStillCallsTheChipAction() {
        show(ConnectionStatus.Disconnected)
        rule.onNodeWithTag("connection-chip").performClick()
        assertEquals(1, taps)
        rule.onNodeWithTag("connection-dialog").assertDoesNotExist()
    }
}

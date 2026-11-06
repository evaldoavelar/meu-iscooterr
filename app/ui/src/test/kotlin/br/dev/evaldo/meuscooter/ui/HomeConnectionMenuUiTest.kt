package br.dev.evaldo.meuscooter.ui

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

/** Pressionar e segurar o chip de conexão abre um menu com UMA opção conforme o estado; toque curto segue como antes (S22). */
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

    @Test fun connectedOffersDisconnectOnly() {
        show(ConnectionStatus.Connected); longPress()
        rule.onNodeWithText("Desconectar patinete").assertIsDisplayed()
        rule.onNodeWithText("Conectar patinete").assertDoesNotExist()
        rule.onNodeWithText("Desconectar patinete").performClick()
        assertEquals(listOf(ConnectionMenuAction.Disconnect), picked)
        assertEquals(0, taps)
    }

    @Test fun disconnectedOffersConnectOnly() {
        show(ConnectionStatus.Disconnected); longPress()
        rule.onNodeWithText("Conectar patinete").performClick()
        assertEquals(listOf(ConnectionMenuAction.Connect), picked)
    }

    @Test fun connectingOffersCancel() {
        show(ConnectionStatus.Connecting); longPress()
        rule.onNodeWithText("Cancelar conexão").performClick()
        assertEquals(listOf(ConnectionMenuAction.Cancel), picked)
    }

    @Test fun shortTapStillCallsTheChipAction() {
        show(ConnectionStatus.Disconnected)
        rule.onNodeWithTag("connection-chip").performClick()
        assertEquals(1, taps)
        rule.onNodeWithText("Conectar patinete").assertDoesNotExist()
    }
}

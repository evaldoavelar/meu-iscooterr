package br.dev.evaldo.meuscooter.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import br.dev.evaldo.meuscooter.data.FakeScooterTransport
import br.dev.evaldo.meuscooter.protocol.Clock
import br.dev.evaldo.meuscooter.ui.theme.MeuPatineteTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Navegação da Home para as telas stub (Telemetria · Preferências · Histórico) e volta. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class AppRootTest {
    @get:Rule val rule = createComposeRule()

    private fun start() {
        val vm = HomeViewModel({ scope, clock -> FakeScooterTransport(scope, clock) }, Clock { System.nanoTime() / 1_000_000 }, null) { "00:00" }
        rule.setContent { MeuPatineteTheme(darkTheme = false) { AppRoot(vm) } }
    }

    @Test fun `inicia na Home desconectada`() {
        start()
        rule.onNodeWithText("Procurar patinete").assertIsDisplayed()
    }

    @Test fun `menu abre cada tela ainda stub e voltar retorna a Home`() {
        start()
        listOf("Preferências", "Histórico de viagens").forEach { title ->
            rule.onNodeWithContentDescription("Mais opções").performClick()
            rule.onNodeWithText(title).performClick()
            rule.onNodeWithTag("stub-title").assertIsDisplayed()
            rule.onNodeWithText("Esta tela chega na Sprint 5.").assertIsDisplayed()
            rule.onNodeWithContentDescription("Voltar").performClick()
            rule.onNodeWithTag("main-button").assertIsDisplayed()
        }
    }

    @Test fun `tela aberta sobrevive a recriacao da Activity`() {
        val vm = HomeViewModel({ scope, clock -> FakeScooterTransport(scope, clock) }, Clock { System.nanoTime() / 1_000_000 }, null) { "00:00" }
        val restoration = StateRestorationTester(rule)
        restoration.setContent { MeuPatineteTheme(darkTheme = false) { AppRoot(vm) } }
        rule.onNodeWithContentDescription("Mais opções").performClick()
        rule.onNodeWithText("Histórico de viagens").performClick()
        restoration.emulateSavedInstanceStateRestore()
        rule.onNodeWithTag("stub-title").assertIsDisplayed()
        rule.onNodeWithText("Histórico de viagens").assertIsDisplayed()
    }

    @Test fun `menu abre a Telemetria real e voltar retorna a Home`() {
        start()
        rule.onNodeWithContentDescription("Mais opções").performClick()
        rule.onNodeWithText("Telemetria").performClick()
        rule.onNodeWithTag("speed-value").assertIsDisplayed()
        rule.onNodeWithContentDescription("Voltar").performClick()
        rule.onNodeWithTag("main-button").assertIsDisplayed()
    }

    @Test fun `Ver historico na Telemetria empilha e voltar retorna a Telemetria`() {
        start()
        rule.onNodeWithContentDescription("Mais opções").performClick()
        rule.onNodeWithText("Telemetria").performClick()
        rule.onNodeWithContentDescription("Mais opções").performClick()
        rule.onNodeWithText("Ver histórico").performClick()
        rule.onNodeWithTag("stub-title").assertIsDisplayed()
        rule.onNodeWithContentDescription("Voltar").performClick()
        rule.onNodeWithTag("speed-value").assertIsDisplayed()
    }
}

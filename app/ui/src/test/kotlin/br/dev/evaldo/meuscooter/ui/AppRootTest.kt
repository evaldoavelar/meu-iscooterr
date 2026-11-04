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

    @Test fun `menu abre Preferencias reais e voltar retorna a Home`() {
        start()
        rule.onNodeWithContentDescription("Mais opções").performClick()
        rule.onNodeWithText("Preferências").performClick()
        rule.onNodeWithText("Tema").assertIsDisplayed()
        rule.onNodeWithContentDescription("Voltar").performClick()
        rule.onNodeWithTag("main-button").assertIsDisplayed()
    }

    @Test fun `trocar unidade nas Preferencias muda a Telemetria`() {
        start()
        rule.onNodeWithContentDescription("Mais opções").performClick()
        rule.onNodeWithText("Preferências").performClick()
        rule.onNodeWithText("mi e mph").performClick()
        rule.onNodeWithContentDescription("Voltar").performClick()
        rule.onNodeWithContentDescription("Mais opções").performClick()
        rule.onNodeWithText("Telemetria").performClick()
        rule.onNodeWithText("mph").assertIsDisplayed()
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
        rule.onNodeWithText("Nenhuma viagem ainda").assertIsDisplayed()
        rule.onNodeWithContentDescription("Voltar").performClick()
        rule.onNodeWithTag("speed-value").assertIsDisplayed()
    }

    @Test fun `historico abre o detalhe da viagem e voltar retorna ao historico`() {
        val trip = br.dev.evaldo.meuscooter.domain.Trip(0, 1_000_000_000_000L, 4.8f, 21 * 60_000L, 14f, 27f, batteryStartPercent = 82, batteryEndPercent = 76)
        val repo = br.dev.evaldo.meuscooter.domain.InMemoryTripRepository().also { kotlinx.coroutines.runBlocking { it.save(trip) } }
        val vm = HomeViewModel({ scope, clock -> FakeScooterTransport(scope, clock) }, Clock { System.nanoTime() / 1_000_000 }, null, repo, { 1_000_000_000_000L + 3_600_000L }) { "00:00" }
        rule.setContent { MeuPatineteTheme(darkTheme = false) { AppRoot(vm) } }
        rule.onNodeWithContentDescription("Mais opções").performClick()
        rule.onNodeWithText("Histórico de viagens").performClick()
        rule.onNodeWithTag("trip-1").performClick()
        rule.onNodeWithText("Bateria na viagem").assertIsDisplayed()
        rule.onNodeWithContentDescription("Voltar").performClick()
        rule.onNodeWithTag("trip-1").assertIsDisplayed()
    }
}

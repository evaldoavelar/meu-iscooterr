package br.dev.evaldo.meuscooter.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performScrollTo
import br.dev.evaldo.meuscooter.data.FakeScooterTransport
import br.dev.evaldo.meuscooter.data.SimulatedBluetoothEnvironment
import br.dev.evaldo.meuscooter.domain.InMemoryPreferencesStore
import br.dev.evaldo.meuscooter.domain.Preferences
import br.dev.evaldo.meuscooter.protocol.ScooterTransport
import kotlinx.coroutines.delay
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

    /** [neverConnects]: o transporte não conecta (patinete longe), para testar Cancelar na reconexão. */
    private fun start(
        onboardingDone: Boolean = true, env: SimulatedBluetoothEnvironment = SimulatedBluetoothEnvironment(), neverConnects: Boolean = false,
    ): HomeViewModel {
        val vm = HomeViewModel(
            { scope, clock ->
                val fake = FakeScooterTransport(scope, clock)
                if (neverConnects) object : ScooterTransport by fake { override suspend fun connect() { delay(60_000) } } else fake
            },
            Clock { System.nanoTime() / 1_000_000 }, null,
            preferencesStore = InMemoryPreferencesStore(Preferences(onboardingDone = onboardingDone)), bluetoothEnvironment = env,
        ) { "00:00" }
        rule.setContent { MeuPatineteTheme(darkTheme = false) { AppRoot(vm) } }
        return vm
    }

    private fun waitForText(text: String) = rule.waitUntil(timeoutMillis = 10_000) {
        rule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    }

    @Test fun `primeira abertura mostra o primeiro uso e Cancelar leva a Home desconectada`() {
        start(onboardingDone = false)
        rule.onNodeWithText("Vamos conectar seu patinete").assertIsDisplayed()
        rule.onNodeWithText("Procurando seu patinete…").assertIsDisplayed()
        rule.onNodeWithText("Cancelar").performClick()
        rule.onNodeWithText("Procurar patinete").assertIsDisplayed()
    }

    @Test fun `primeiro uso completo - encontra, confirma, conecta e Comecar abre a Home conectada`() {
        val vm = start(onboardingDone = false)
        waitForText("Encontramos este patinete")
        rule.onNodeWithText("Meu iScooter").assertIsDisplayed()
        rule.onNodeWithText("Conectar a este patinete").performClick()
        waitForText("Começar")
        rule.onNodeWithText("Meu iScooter conectado").assertIsDisplayed()
        rule.onNodeWithText("Começar").performClick()
        rule.waitUntil(timeoutMillis = 5_000) { vm.preferences.value.onboardingDone }
        rule.onNodeWithTag("main-button").assertIsDisplayed()
        rule.onNodeWithText("Desbloquear").assertIsDisplayed()
    }

    @Test fun `com o primeiro uso concluido a Home abre direto`() {
        start(onboardingDone = true)
        rule.onNodeWithText("Vamos conectar seu patinete").assertDoesNotExist()
    }

    @Test fun `Esquecer este patinete volta ao primeiro uso`() {
        start(onboardingDone = true)
        rule.onNodeWithContentDescription("Mais opções").performClick()
        rule.onNodeWithText("Preferências").performClick()
        rule.onNodeWithText("Esquecer este patinete").performScrollTo().performClick()
        rule.onNodeWithText("Esquecer").performClick()
        waitForText("Vamos conectar seu patinete")
        rule.onNodeWithText("Procurando seu patinete…").assertIsDisplayed()
    }

    @Test fun `Esquecer nas Preferencias e concluir o primeiro uso cai na Home`() {
        start(onboardingDone = true)
        rule.onNodeWithContentDescription("Mais opções").performClick()
        rule.onNodeWithText("Preferências").performClick()
        rule.onNodeWithText("Esquecer este patinete").performScrollTo().performClick()
        rule.onNodeWithText("Esquecer").performClick()
        waitForText("Encontramos este patinete")
        rule.onNodeWithText("Conectar a este patinete").performClick()
        waitForText("Começar")
        rule.onNodeWithText("Começar").performClick()
        rule.onNodeWithTag("main-button").assertIsDisplayed()
        rule.onNodeWithText("Tema").assertDoesNotExist()
    }

    @Test fun `sem Bluetooth no primeiro uso mostra o motivo e nao conecta`() {
        start(onboardingDone = false, env = SimulatedBluetoothEnvironment(bluetoothEnabled = false))
        rule.onNodeWithText("Bluetooth desligado").assertIsDisplayed()
        rule.onNodeWithText("Ativar Bluetooth").performClick()
        waitForText("Procurando seu patinete…")
        rule.onNodeWithText("Cancelar").assertIsDisplayed()
    }

    @Test fun `Procurar patinete abre a Recuperacao, reconecta e volta a Home conectada`() {
        start()
        rule.onNodeWithText("Procurar patinete").performClick()
        rule.onNodeWithText("Reconectando…").assertIsDisplayed()
        rule.onNodeWithText("Tentativa 1 de 5. Mantenha o patinete ligado e por perto.").assertIsDisplayed()
        waitForText("Desbloquear")
        rule.onNodeWithTag("main-button").assertIsDisplayed()
    }

    @Test fun `queda depois de conectado - Procurar patinete reconecta e volta a Home conectada`() {
        var fake: FakeScooterTransport? = null
        val vm = HomeViewModel(
            { scope, clock -> FakeScooterTransport(scope, clock).also { fake = it } },
            Clock { System.nanoTime() / 1_000_000 }, null,
            preferencesStore = InMemoryPreferencesStore(Preferences(onboardingDone = true)),
            bluetoothEnvironment = SimulatedBluetoothEnvironment(),
        ) { "00:00" }
        rule.setContent { MeuPatineteTheme(darkTheme = false) { AppRoot(vm) } }
        rule.onNodeWithText("Procurar patinete").performClick()
        waitForText("Desbloquear")
        kotlinx.coroutines.runBlocking { fake!!.disconnect() }   // queda do enlace
        waitForText("Procurar patinete")
        rule.onNodeWithText("Desconectado").assertIsDisplayed()
        rule.onNodeWithText("Procurar patinete").performClick()
        waitForText("Desbloquear")
        rule.onNodeWithText("Conectado", substring = true).assertIsDisplayed()
    }

    @Test fun `tocar no chip de conexao reconecta sem sair da Home`() {
        start()
        rule.onNodeWithTag("connection-chip").performClick()
        waitForText("Desbloquear")
        rule.onNodeWithText("Reconectando…").assertDoesNotExist()
    }

    @Test fun `Cancelar na reconexao volta a Home desconectada`() {
        start(neverConnects = true)
        rule.onNodeWithText("Procurar patinete").performClick()
        rule.onNodeWithText("Reconectando…").assertIsDisplayed()
        rule.onNodeWithText("Cancelar").performClick()
        rule.onNodeWithText("Procurar patinete").assertIsDisplayed()
    }

    @Test fun `Bluetooth desligado na Recuperacao - Ativar segue para Reconectando e conecta`() {
        start(env = SimulatedBluetoothEnvironment(bluetoothEnabled = false))
        rule.onNodeWithText("Procurar patinete").performClick()
        rule.onNodeWithText("Bluetooth desligado").assertIsDisplayed()
        rule.onNodeWithText("Ativar Bluetooth").performClick()
        waitForText("Desbloquear")
    }

    @Test fun `permissao negada na Recuperacao - Permitir segue adiante e Abrir configuracoes e simulado`() {
        val env = SimulatedBluetoothEnvironment(permissionGranted = false)
        start(env = env)
        rule.onNodeWithText("Procurar patinete").performClick()
        rule.onNodeWithText("Falta uma permissão").assertIsDisplayed()
        rule.onNodeWithText("Abrir configurações do Android").performClick()
        org.junit.Assert.assertEquals(1, env.settingsOpenedCount)
        rule.onNodeWithText("Permitir").performClick()
        waitForText("Desbloquear")
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

    @Test fun `menu abre o Registro de eventos vazio e voltar retorna a Home`() {
        start()
        rule.onNodeWithContentDescription("Mais opções").performClick()
        rule.onNodeWithText("Registro de eventos").performClick()
        rule.onNodeWithText("Nenhum evento ainda").assertIsDisplayed()
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
        val vm = HomeViewModel({ scope, clock -> FakeScooterTransport(scope, clock) }, Clock { System.nanoTime() / 1_000_000 }, null, repo, { 1_000_000_000_000L + 3_600_000L },
            preferencesStore = InMemoryPreferencesStore(Preferences(onboardingDone = true))) { "00:00" }
        rule.setContent { MeuPatineteTheme(darkTheme = false) { AppRoot(vm) } }
        rule.onNodeWithContentDescription("Mais opções").performClick()
        rule.onNodeWithText("Histórico de viagens").performClick()
        rule.onNodeWithTag("trip-1").performClick()
        rule.onNodeWithText("Bateria na viagem").assertIsDisplayed()
        rule.onNodeWithContentDescription("Voltar").performClick()
        rule.onNodeWithTag("trip-1").assertIsDisplayed()
    }
}

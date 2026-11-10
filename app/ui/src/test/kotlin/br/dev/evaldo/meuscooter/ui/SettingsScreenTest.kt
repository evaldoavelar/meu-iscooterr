package br.dev.evaldo.meuscooter.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import br.dev.evaldo.meuscooter.domain.Preferences
import br.dev.evaldo.meuscooter.domain.ThemeMode
import br.dev.evaldo.meuscooter.domain.Units
import br.dev.evaldo.meuscooter.ui.settings.SettingsActions
import br.dev.evaldo.meuscooter.ui.settings.SettingsScreen
import br.dev.evaldo.meuscooter.ui.theme.MeuPatineteTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** S4: textos de design/screens.py (`configuracoes`). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class SettingsScreenTest {
    @get:Rule val rule = createComposeRule()

    private val calls = mutableListOf<String>()
    private val actions = SettingsActions(
        onBack = { calls += "back" }, onTheme = { calls += "theme:$it" }, onUnits = { calls += "units:$it" },
        onAutoReconnect = { calls += "reconnect:$it" }, onSaveRoute = { calls += "route:$it" }, onStartTripOnOpen = { calls += "trip:$it" }, onLockWithPin = { calls += "pin:$it" },
        onForget = { calls += "forget" }, onClearHistory = { calls += "clear" },
    )

    private fun show(p: Preferences = Preferences(), fontScale: Float = 1f, notice: String? = null) = rule.setContent {
        MeuPatineteTheme(darkTheme = false) {
            val d = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(d.density, fontScale)) { SettingsScreen(p, "Meu iScooter", actions, notice) }
        }
    }

    @Test fun `mostra os grupos do prototipo`() {
        show()
        rule.onNodeWithText("Preferências").assertIsDisplayed()
        rule.onNodeWithText("Tema").assertIsDisplayed()
        rule.onNodeWithText("Sistema").assertIsDisplayed(); rule.onNodeWithText("Claro").assertIsDisplayed(); rule.onNodeWithText("Escuro").assertIsDisplayed()
        rule.onNodeWithText("km e km/h").assertIsDisplayed(); rule.onNodeWithText("mi e mph").assertIsDisplayed()
        rule.onNodeWithText("Reconectar automaticamente").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Guardar trajeto das viagens").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Versão 0.1 (protótipo)").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Código aberto · licença MIT").performScrollTo().assertIsDisplayed()
    }

    @Test fun `escolher tema e unidade chama os callbacks`() {
        show()
        rule.onNodeWithText("Escuro").performClick()
        rule.onNodeWithText("mi e mph").performClick()
        assertEquals(listOf("theme:${ThemeMode.Dark}", "units:${Units.Imperial}"), calls)
    }

    @Test fun `interruptores refletem o estado e alternam`() {
        show(Preferences(autoReconnect = true, saveRoute = false))
        rule.onNodeWithTag("switch-reconnect").performScrollTo().assertIsOn()
        rule.onNodeWithTag("switch-route").performScrollTo().performClick()
        rule.onNodeWithTag("switch-reconnect").performClick()
        assertEquals(listOf("route:true", "reconnect:false"), calls)
    }

    @Test fun `bloquear ao desconectar explica o efeito, marca Em teste e alterna`() {
        show()
        rule.onNodeWithText("Bloquear ao desconectar").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Em teste").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText(
            "Marcado: o patinete deve bloquear sozinho quando o app desconectar e desbloquear ao reconectar. " +
                "Desmarcado: não deve bloquear ao desconectar.",
        ).performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag("switch-lock-pin").performScrollTo().assertIsOff().performClick()
        assertEquals(listOf("pin:true"), calls)
    }

    @Test fun `aviso do Air Lock aparece so quando existe`() {
        show()
        rule.onAllNodesWithTag("air-lock-notice").assertCountEquals(0)
    }

    @Test fun `aviso do Air Lock mostra a recusa`() {
        show(notice = "O patinete recusou o ajuste de bloqueio ao desconectar.")
        rule.onNodeWithTag("air-lock-notice").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("O patinete recusou o ajuste de bloqueio ao desconectar.").assertIsDisplayed()
    }

    @Test fun `bloquear ao desconectar reflete o estado ligado`() {
        show(Preferences(lockWithPin = true))
        rule.onNodeWithTag("switch-lock-pin").performScrollTo().assertIsOn()
    }

    @Test fun `esquecer patinete pede confirmacao`() {
        show()
        rule.onNodeWithText("Meu iScooter").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Esquecer este patinete").performScrollTo().performClick()
        rule.onNodeWithText("Esquecer este patinete?").assertIsDisplayed()
        assertEquals(emptyList<String>(), calls)
        rule.onNodeWithText("Esquecer").performClick()
        assertEquals(listOf("forget"), calls)
    }

    @Test fun `apagar historico pede confirmacao e cancelar nao apaga`() {
        show()
        rule.onNodeWithText("Apagar histórico de viagens").performScrollTo().performClick()
        rule.onNodeWithText("Apagar o histórico?").assertIsDisplayed()
        rule.onNodeWithText("Cancelar").performClick()
        assertEquals(emptyList<String>(), calls)
        rule.onNodeWithText("Apagar histórico de viagens").performClick()
        rule.onNodeWithText("Apagar").performClick()
        assertEquals(listOf("clear"), calls)
    }

    @Test fun `voltar chama onBack`() {
        show()
        rule.onNodeWithContentDescription("Voltar").performClick()
        assertEquals(listOf("back"), calls)
    }

    @Test fun `tocar no texto do interruptor alterna (linha toggleable)`() {
        show(Preferences(autoReconnect = true))
        rule.onNodeWithText("Reconectar automaticamente").performScrollTo().performClick()
        assertEquals(listOf("reconnect:false"), calls)
    }

    @Test fun `dialogo de esquecer diz so o que acontece (desconecta)`() {
        show()
        rule.onNodeWithText("Esquecer este patinete").performScrollTo().performClick()
        rule.onNodeWithText("O app desconecta deste patinete e volta ao primeiro uso. Nada é enviado a ele.").assertIsDisplayed()
    }

    private fun lastRowsReachable(fontScale: Float) {
        show(fontScale = fontScale)
        rule.onNodeWithText("Tema").assertIsDisplayed()
        rule.onNodeWithTag("switch-route").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Esquecer este patinete").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Apagar histórico de viagens").performScrollTo().performClick()
        rule.onNodeWithText("Apagar o histórico?").assertIsDisplayed()
        rule.onNodeWithText("Cancelar").assertIsDisplayed()
        rule.onNodeWithText("Apagar").assertIsDisplayed()
    }

    @Config(sdk = [35], qualifiers = "w360dp-h780dp-xhdpi")
    @Test fun `360x780 fonte 1_5 todas as linhas e o dialogo ficam alcancaveis`() = lastRowsReachable(1.5f)

    @Config(sdk = [35], qualifiers = "w360dp-h780dp-xhdpi")
    @Test fun `360x780 fonte 2_0 todas as linhas e o dialogo ficam alcancaveis`() = lastRowsReachable(2f)

    @Test fun `iniciar viagem ao abrir a telemetria vem ligado e alterna`() {
        show()
        rule.onNodeWithText("Iniciar viagem ao abrir a Telemetria").performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag("switch-start-trip").performScrollTo().assertIsOn()
        rule.onNodeWithTag("switch-start-trip").performClick()
        assertEquals(listOf("trip:false"), calls)
    }
}

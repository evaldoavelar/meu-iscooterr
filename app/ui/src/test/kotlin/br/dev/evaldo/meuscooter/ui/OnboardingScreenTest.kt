package br.dev.evaldo.meuscooter.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import br.dev.evaldo.meuscooter.domain.OnboardingFlow
import br.dev.evaldo.meuscooter.ui.onboarding.OnboardingActions
import br.dev.evaldo.meuscooter.ui.onboarding.OnboardingScreen
import br.dev.evaldo.meuscooter.ui.theme.MeuPatineteTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** S4: 3 passos de design/screens.py `primeiro_uso` ("iX7 Pro" -> nome neutro), 360x780 dp, fonte 1,0 / 1,5 / 2,0. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h780dp-xhdpi")
class OnboardingScreenTest {
    @get:Rule val rule = createComposeRule()
    private val calls = mutableListOf<String>()
    private val searching = OnboardingFlow()
    private val found = searching.scooterFound()
    private val connected = found.confirm().connectionEstablished()

    private val current = mutableStateOf(OnboardingFlow())
    private var shown = false

    /** Compõe uma vez e depois só troca o estado (setContent não pode ser chamado duas vezes). */
    private fun show(flow: OnboardingFlow, fontScale: Float = 1f) {
        current.value = flow
        if (shown) { rule.waitForIdle(); return }
        shown = true
        rule.setContent {
        MeuPatineteTheme(darkTheme = false) {
            val d = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(d.density, fontScale)) {
                OnboardingScreen(current.value, "Meu iScooter", OnboardingActions(onConfirm = { calls += "confirm" }, onFinish = { calls += "finish" }, onCancel = { calls += "cancel" }))
            }
        }
        }
    }

    private fun button(label: String) {
        rule.onNodeWithText(label).assertIsDisplayed()
        rule.onNodeWithTag("onboarding-button").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
    }

    private fun step1() {
        show(searching)
        rule.onNodeWithText("Vamos conectar seu patinete").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Isso é feito só uma vez.").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Procurando seu patinete…").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("1. Ligue o patinete").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("2. Fique perto dele (1 a 2 metros)").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("3. Deixe o Bluetooth do celular ligado").performScrollTo().assertIsDisplayed()
        button("Cancelar")
        rule.onNodeWithText("Cancelar").performClick()
        assertEquals(listOf("cancel"), calls)
    }

    private fun step2() {
        calls.clear(); show(found)
        rule.onNodeWithText("Encontramos este patinete").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Meu iScooter").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("É o seu? Confirme antes de conectar. O app só controla patinetes que você escolher.").performScrollTo().assertIsDisplayed()
        button("Conectar a este patinete")
        rule.onNodeWithText("Conectar a este patinete").performClick()
        assertEquals(listOf("confirm"), calls)
    }

    private fun step3() {
        calls.clear(); show(connected)
        rule.onNodeWithText("Tudo certo!").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Meu iScooter conectado").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Agora é só usar o app.").performScrollTo().assertIsDisplayed()
        button("Começar")
        rule.onNodeWithText("Começar").performClick()
        assertEquals(listOf("finish"), calls)
    }

    @Test fun `passo 1 procurando (fonte 1_0)`() = step1()
    @Test fun `passo 2 encontrado (fonte 1_0)`() = step2()
    @Test fun `passo 3 conectado (fonte 1_0)`() = step3()

    @Test fun `depois de confirmar o botao vira Conectando e fica desabilitado`() {
        show(found.confirm())
        rule.onNodeWithText("Conectando…").assertIsDisplayed()
        rule.onNodeWithTag("onboarding-button").assertIsNotEnabled()
    }

    /** Codex r1 #1: depois do prazo o aviso aparece e o botão volta a funcionar como "Tentar novamente" (também com fonte 2,0). */
    @Test fun `falha de conexao mostra aviso e botao Tentar novamente`() {
        show(found.confirm().connectionFailed(), fontScale = 2f)
        rule.onNodeWithText("Não foi possível conectar. Aproxime-se do patinete e tente de novo.").performScrollTo().assertIsDisplayed()
        button("Tentar novamente")
        rule.onNodeWithText("Tentar novamente").performClick()
        assertEquals(listOf("confirm"), calls)
    }

    private fun allSteps(fontScale: Float) {
        for ((flow, check) in listOf<Pair<OnboardingFlow, () -> Unit>>(searching to ::step1Reach, found to ::step2Reach, connected to ::step3Reach)) {
            if (!shown) show(flow, fontScale) else show(flow); check()
        }
    }

    private fun step1Reach() {
        rule.onNodeWithText("3. Deixe o Bluetooth do celular ligado").performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag("onboarding-button").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
    }
    private fun step2Reach() {
        rule.onNodeWithText("É o seu? Confirme antes de conectar. O app só controla patinetes que você escolher.").performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag("onboarding-button").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
    }
    private fun step3Reach() {
        rule.onNodeWithText("Agora é só usar o app.").performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag("onboarding-button").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
    }

    @Test fun `tres passos com fonte 1_5`() = allSteps(1.5f)
    @Test fun `tres passos com fonte 2_0`() = allSteps(2f)
}

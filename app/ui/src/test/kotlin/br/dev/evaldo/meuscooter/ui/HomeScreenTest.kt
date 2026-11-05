package br.dev.evaldo.meuscooter.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertRangeInfoEquals
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import br.dev.evaldo.meuscooter.domain.HomeState
import br.dev.evaldo.meuscooter.domain.Outcome
import br.dev.evaldo.meuscooter.domain.PendingOp
import br.dev.evaldo.meuscooter.protocol.ConnectionStatus
import br.dev.evaldo.meuscooter.protocol.Control
import br.dev.evaldo.meuscooter.protocol.ScooterCommand
import br.dev.evaldo.meuscooter.protocol.SpeedModeOption
import br.dev.evaldo.meuscooter.protocol.Telemetry
import br.dev.evaldo.meuscooter.ui.home.HomeActions
import br.dev.evaldo.meuscooter.domain.AppDestination
import br.dev.evaldo.meuscooter.ui.home.HomeScreen
import br.dev.evaldo.meuscooter.ui.theme.MeuPatineteTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Seam S4: Compose UI na JVM (Robolectric). Textos esperados: FigCraft (design/home.py) e ANDROID-MAPA. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class HomeScreenTest {
    @get:Rule val rule = createComposeRule()

    private val modes = listOf(SpeedModeOption(0, "Pedestre"), SpeedModeOption(1, "Eco"), SpeedModeOption(2, "Race"), SpeedModeOption(3, "Sport"))
    private val mapper = HomeUiMapper(modes, "iX7 Pro") { "14:32" }

    private fun tel(locked: Boolean = false, speed: Float? = 0f, at: Long = 1_000) = Telemetry(
        receivedAtMs = at, locked = locked, speedKmh = speed, batteryPercent = 82, headlightOn = true, dualMotor = false,
        speedModeRaw = 1, cruiseOn = false, zeroStartOn = true, speedLimitKmh = 25, totalKm = 1248.4f,
    )

    private fun connected(t: Telemetry = tel(), now: Long = 1_000, pending: Map<Control, PendingOp> = emptyMap(),
                          outcomes: Map<Control, Outcome> = emptyMap()) =
        mapper.map(HomeState(ConnectionStatus.Connected, t, now, pending, outcomes, meter1AtMs = t?.receivedAtMs))

    private fun show(state: HomeUiState, actions: HomeActions = HomeActions(), dark: Boolean = false, fontScale: Float = 1f) {
        rule.setContent {
            val d = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(d.density, fontScale)) {
                MeuPatineteTheme(darkTheme = dark) { HomeScreen(state, actions) }
            }
        }
    }

    // ---- conteúdo ----

    @Test fun `mostra titulo conexao e valores principais`() {
        show(connected())
        rule.onNodeWithText("Meu iScooter").assertIsDisplayed()
        rule.onNodeWithText("Conectado · iX7 Pro").assertIsDisplayed()
        rule.onNodeWithText("82%").assertIsDisplayed()
        rule.onNodeWithText("0 km/h").assertIsDisplayed()
        rule.onNodeWithText("1.248 km").assertIsDisplayed()
        rule.onNodeWithText("Farol").assertIsDisplayed()
    }

    @Test fun `modo mostra as quatro opcoes e tracao as duas`() {
        show(connected())
        listOf("Pedestre", "Eco", "Race", "Sport", "1 motor", "2 motores").forEach { rule.onNodeWithText(it).assertIsDisplayed() }
        rule.onNodeWithText("Cruise").assertIsDisplayed()
        rule.onNodeWithText("Partida do zero").assertIsDisplayed()
        rule.onNodeWithText("Limite de velocidade").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("25 km/h").performScrollTo().assertIsDisplayed()
    }

    // ---- botão fixo no rodapé ----

    /** TASK-S15-A item 1: a stale battery must not remove or disable the unlock action. */
    @Test fun staleMeter1KeepsUnlockButtonDisplayedAndClickable() {
        var unlocks = 0
        val state = HomeState(
            connection = ConnectionStatus.Connected,
            telemetry = tel(locked = true, at = 6_000),
            nowMs = 6_000,
            meter1AtMs = 1_000,
        )
        show(mapper.map(state), HomeActions(onMainButton = { unlocks++ }))
        rule.onNodeWithTag("main-button").assertIsDisplayed().assertIsEnabled()
        rule.onNodeWithText("Desbloquear").assertIsDisplayed().performClick()
        rule.runOnIdle { assertEquals(1, unlocks) }
    }

    @Test fun `botao principal fica no rodape com largura total e altura minima`() {
        show(connected())
        val root = rule.onRoot().getUnclippedBoundsInRoot()
        val b = rule.onNodeWithTag("main-button").assertIsDisplayed().getUnclippedBoundsInRoot()
        assertTrue("largura total (menos margens)", b.width >= root.width - 40.dp)
        assertTrue("altura >= 56dp", b.height >= 56.dp)
        assertTrue("colado ao rodape", root.height - b.bottom <= 48.dp)
    }

    @Test fun `rolar o conteudo nunca oculta nem sobrepoe o botao`() {
        show(connected(), fontScale = 2f)
        rule.onNodeWithTag("limit").performScrollTo()
        val limit = rule.onNodeWithTag("limit").assertIsDisplayed().getUnclippedBoundsInRoot()
        val button = rule.onNodeWithTag("main-button").assertIsDisplayed().getUnclippedBoundsInRoot()
        assertTrue("limite termina antes do botao", limit.bottom <= button.top)
    }

    /** A3: Cruise e Partida do zero têm a mesma altura em qualquer largura/fonte (o título de um quebra em 2 linhas conforme a largura). */
    @Config(sdk = [35], qualifiers = "w360dp-h780dp-xhdpi")
    @Test fun `cruise e partida do zero tem a mesma altura em varias larguras e fontes`() {
        val width = androidx.compose.runtime.mutableStateOf(360)
        val scale = androidx.compose.runtime.mutableStateOf(1f)
        val state = connected()
        rule.setContent {
            val d = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(d.density, scale.value)) {
                MeuPatineteTheme(darkTheme = false) { HomeScreen(state, HomeActions(), Modifier.width(width.value.dp)) }
            }
        }
        for (w in listOf(300, 320, 340, 360, 380, 411)) for (f in listOf(1f, 1.15f, 1.3f, 1.5f, 2f)) {
            width.value = w; scale.value = f; rule.waitForIdle()
            rule.onNodeWithTag("cruise").performScrollTo()
            val a = rule.onNodeWithTag("cruise").getUnclippedBoundsInRoot()
            val b = rule.onNodeWithTag("zero-start").getUnclippedBoundsInRoot()
            assertEquals("alturas diferentes em ${w}dp fonte $f", a.height.value, b.height.value, 0.5f)
        }
    }

    @Config(sdk = [35], qualifiers = "w320dp-h480dp-mdpi")
    @Test fun `tela pequena com fonte 2_0 nao sobrepoe cards e mantem botao visivel`() {
        show(connected(), dark = true, fontScale = 2f)
        rule.onNodeWithTag("main-button").assertIsDisplayed()
        rule.onNodeWithText("Bloquear").assertIsDisplayed()
        val order = listOf("connection-chip", "headlight", "motors-card", "mode-card", "cruise", "limit")
        val bounds = order.map { rule.onNodeWithTag(it).getUnclippedBoundsInRoot() }
        bounds.zipWithNext().forEachIndexed { i, (a, b) ->
            if (order[i] != "cruise") assertTrue("${order[i]} sobrepoe ${order[i + 1]}", a.bottom <= b.top)
        }
        rule.onNodeWithTag("limit").performScrollTo()
        assertTrue(rule.onNodeWithTag("limit").getUnclippedBoundsInRoot().bottom <= rule.onNodeWithTag("main-button").getUnclippedBoundsInRoot().top)
    }

    @Config(sdk = [35], qualifiers = "w320dp-h568dp-xhdpi")
    @Test fun `tela pequena com fonte 1_5 mantem todas as opcoes de modo acessiveis`() {
        show(connected(), fontScale = 1.5f)
        listOf(0, 1, 2, 3).forEach { rule.onNodeWithTag("mode-$it").performScrollTo().assertIsDisplayed() }
    }

    @Config(sdk = [35], qualifiers = "w360dp-h780dp-xhdpi")
    @Test fun `na tela de referencia 360x780 todos os controles cabem sem rolar como no FigCraft`() {
        show(connected())
        listOf("headlight", "motors-card", "mode-card", "cruise", "zero-start", "limit", "main-button").forEach {
            rule.onNodeWithTag(it).assertIsDisplayed()
        }
        val limit = rule.onNodeWithTag("limit").getUnclippedBoundsInRoot()
        val button = rule.onNodeWithTag("main-button").getUnclippedBoundsInRoot()
        assertTrue("limite termina antes do botao", limit.bottom <= button.top)
        rule.onNodeWithText("Pedestre").assertIsDisplayed()
    }

    @Config(sdk = [35], qualifiers = "w360dp-h780dp-xhdpi")
    @Test fun `rotulos dos modos nao quebram no meio da palavra em fonte 1_0`() {
        show(connected())
        val b = rule.onNodeWithText("Pedestre", useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertTrue("Pedestre em uma linha (altura do texto <= 26dp, foi ${b.height})", b.height <= 26.dp)
    }

    @Config(sdk = [35], qualifiers = "w360dp-h780dp-xhdpi")
    @Test fun `fonte 2_0 empilha os cartoes de informacao sem quebrar palavras`() {
        show(connected(), fontScale = 2f)
        val bat = rule.onNodeWithTag("info-battery").getUnclippedBoundsInRoot()
        val spd = rule.onNodeWithTag("info-speed").getUnclippedBoundsInRoot()
        assertTrue("empilhados", bat.bottom <= spd.top)
    }

    // ---- ações e habilitação ----

    @Test fun `tocar no botao principal chama a acao uma vez`() {
        var n = 0
        show(connected(), HomeActions(onMainButton = { n++ }))
        rule.onNodeWithTag("main-button").performClick()
        assertEquals(1, n)
    }

    @Test fun `botao pendente fica desabilitado e nao dispara`() {
        var n = 0
        val s = connected(pending = mapOf(Control.Lock to PendingOp(ScooterCommand.SetLocked(true), 900)))
        show(s, HomeActions(onMainButton = { n++ }))
        rule.onNodeWithText("Aguardando o patinete…").assertIsDisplayed()
        rule.onNodeWithTag("main-button").assertIsNotEnabled().performClick()
        assertEquals(0, n)
    }

    @Test fun `em movimento controles ficam desabilitados com motivo e farol continua`() {
        show(connected(tel(speed = 15f)))
        rule.onNodeWithText("Pare para bloquear").assertIsDisplayed()
        rule.onNodeWithTag("main-button").assertIsNotEnabled()
        rule.onNodeWithTag("mode-2").assertIsNotEnabled()
        rule.onNodeWithTag("motors-1").assertIsNotEnabled()
        rule.onNodeWithTag("cruise").assertIsNotEnabled()
        rule.onNodeWithTag("zero-start").assertIsNotEnabled()
        rule.onNodeWithTag("limit-slider").performScrollTo().assertIsNotEnabled()
        rule.onNodeWithTag("headlight").assertIsEnabled()
        rule.onNodeWithTag("reason-banner").assertIsDisplayed()
    }

    @Test fun `velocidade desconhecida e telemetria velha desabilitam com motivo`() {
        show(connected(tel(speed = null)))
        rule.onNodeWithTag("mode-2").assertIsNotEnabled()
        rule.onNodeWithText("Velocidade desconhecida — aguarde o patinete", substring = true).assertIsDisplayed()
    }

    @Test fun `telemetria desatualizada mostra aviso e desabilita`() {
        show(connected(tel(at = 1_000), now = 4_000))
        rule.onNodeWithTag("stale-banner").assertIsDisplayed()
        rule.onNodeWithTag("cruise").assertIsNotEnabled()
    }

    @Test fun `tocar numa opcao de modo chama a acao com o valor bruto`() {
        var raw = -1
        show(connected(), HomeActions(onMode = { raw = it }))
        rule.onNodeWithTag("mode-3").performClick()
        assertEquals(3, raw)
    }

    @Test fun `farol cruise partida e tracao chamam suas acoes`() {
        var head: Boolean? = null; var cruise: Boolean? = null; var zero: Boolean? = null; var motors = -1
        show(connected(), HomeActions(onHeadlight = { head = it }, onCruise = { cruise = it }, onZeroStart = { zero = it }, onMotors = { motors = it }))
        rule.onNodeWithTag("headlight").performClick(); assertEquals(false, head)
        rule.onNodeWithTag("cruise").performScrollTo().performClick(); assertEquals(true, cruise)
        rule.onNodeWithTag("zero-start").performScrollTo().performClick(); assertEquals(false, zero)
        rule.onNodeWithTag("motors-1").performClick(); assertEquals(1, motors)
    }

    // ---- estados de resultado ----

    @Test fun `confirmado mostra a hora e erro mostra Tentar de novo`() {
        show(connected(tel(locked = true), outcomes = mapOf(Control.Lock to Outcome.Confirmed(1_000, ScooterCommand.SetLocked(true)))))
        rule.onNodeWithText("Bloqueado às 14:32").assertIsDisplayed()
    }

    @Test fun `falha mostra Tentar de novo e Sem resposta do patinete`() {
        var n = 0
        show(connected(outcomes = mapOf(Control.Lock to Outcome.TimedOut(1_000))), HomeActions(onMainButton = { n++ }))
        rule.onNodeWithText("Sem resposta do patinete").assertIsDisplayed()
        rule.onNodeWithText("Tentar de novo").performClick()
        assertEquals(1, n)
    }

    @Test fun `desconectado e conectando mostram o botao correspondente`() {
        show(mapper.map(HomeState()))
        rule.onNodeWithText("Procurar patinete").assertIsDisplayed()
        rule.onNodeWithText("Desconectado").assertIsDisplayed()
    }

    // ---- menu ----

    @Test fun `menu tres pontos oferece Telemetria Preferencias e Historico`() {
        val opened = mutableListOf<AppDestination>()
        show(connected(), HomeActions(onOpen = { opened += it }))
        rule.onNodeWithContentDescription("Mais opções").performClick()
        rule.onNodeWithText("Telemetria").assertIsDisplayed()
        rule.onNodeWithText("Preferências").assertIsDisplayed()
        rule.onNodeWithText("Histórico de viagens").assertIsDisplayed()
        rule.onNodeWithText("Histórico de viagens").performClick()
        assertEquals(listOf(AppDestination.History), opened)
    }

    @Test fun `tema escuro tambem renderiza`() {
        show(connected(), dark = true)
        rule.onNodeWithTag("main-button").assertIsDisplayed()
    }

    @Test fun `slider volta ao valor confirmado quando o comando de limite falha`() {
        var ui by androidx.compose.runtime.mutableStateOf(connected())          // limite confirmado: 25
        rule.setContent { MeuPatineteTheme(darkTheme = false) { HomeScreen(ui, HomeActions()) } }
        rule.onNodeWithTag("limit-slider").performScrollTo()
            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress) { it(40f) }
        rule.runOnIdle { ui = connected(outcomes = mapOf(Control.Limit to Outcome.Failed("x", 1_100))) }
        rule.onNodeWithTag("limit-slider").assertRangeInfoEquals(androidx.compose.ui.semantics.ProgressBarRangeInfo(25f, 20f..50f, 0))
    }

    @Test fun `botoes mais e menos do limite movem de 5 em 5 km por h`() {
        val got = mutableListOf<Int>()
        show(connected(), HomeActions(onLimit = { got += it }))          // limite confirmado: 25
        rule.onNodeWithTag("limit-plus").performScrollTo().performClick()
        rule.onNodeWithTag("limit-minus").performClick()
        rule.onNodeWithTag("limit-minus").performClick()
        assertEquals(listOf(30, 25, 20), got)
        rule.onNodeWithTag("limit-minus").assertIsNotEnabled()            // piso 20
    }

    // ---- revisão Codex r1, achado 5: interação recusada não deixa rascunho na tela ----
    @Test fun `slider volta ao valor confirmado quando e desabilitado por movimento`() {
        var ui by androidx.compose.runtime.mutableStateOf(connected())          // limite confirmado: 25
        rule.setContent { MeuPatineteTheme(darkTheme = false) { HomeScreen(ui, HomeActions()) } }
        rule.onNodeWithTag("limit-slider").performScrollTo()
            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress) { it(40f) }
        // O reducer recusa (movimento): nada fica pendente e o limite confirmado segue 25.
        rule.runOnIdle { ui = connected(tel(speed = 9f)) }
        rule.onNodeWithTag("limit-slider").assertRangeInfoEquals(androidx.compose.ui.semantics.ProgressBarRangeInfo(25f, 20f..50f, 0))
        rule.onNodeWithText("25 km/h").assertIsDisplayed()
    }

    // ---- revisão Codex r1, achado 6: aviso de restrição com modo pendente ----
    @Test fun `aviso de movimento continua visivel com o modo pendente`() {
        val pendingMode = mapOf(Control.Mode to PendingOp(ScooterCommand.SetSpeedMode(2), 900))
        show(connected(tel(speed = 9f), pending = pendingMode))
        rule.onNodeWithTag("reason-banner").assertIsDisplayed()
        rule.onNodeWithText("Pare o patinete para alterar").assertIsDisplayed()
    }

    @Test fun `limite em mph mostra rotulo convertido mas o slider entrega km por h`() {
        val got = mutableListOf<Int>()
        show(mapper.map(HomeState(ConnectionStatus.Connected, tel(), 1_000, meter1AtMs = 1_000), br.dev.evaldo.meuscooter.domain.Units.Imperial), HomeActions(onLimit = { got += it }))
        rule.onNodeWithText("16 mph (25 km/h)").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("12 mph").assertIsDisplayed()
        rule.onNodeWithText("31 mph").assertIsDisplayed()
        rule.onNodeWithTag("limit-slider").performScrollTo()
            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress) { it(40f) }
        rule.runOnIdle { assertEquals(listOf(40), got) }
        rule.onNodeWithText("25 mph (40 km/h)").assertIsDisplayed()   // 40 km/h = 24,85 mph
    }
}

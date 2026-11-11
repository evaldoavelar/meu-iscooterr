package br.dev.evaldo.meuscooter.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import br.dev.evaldo.meuscooter.domain.SpeedSource
import androidx.compose.ui.unit.Density
import br.dev.evaldo.meuscooter.domain.TripSummary
import br.dev.evaldo.meuscooter.protocol.Telemetry
import br.dev.evaldo.meuscooter.ui.telemetry.TelemetryScreen
import br.dev.evaldo.meuscooter.ui.theme.MeuPatineteTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** S4: textos esperados de design/telemetry.py e design/history.py. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class TelemetryScreenTest {
    @get:Rule val rule = createComposeRule()

    private val state = TelemetryUiMapper().map(
        Telemetry(receivedAtMs = 1, speedKmh = 18f, scooterSpeedKmh = 18f, batteryPercent = 82, totalKm = 1248.4f, voltageV = 54.9f, currentA = 8.4f, motor1TempC = 41, motor2TempC = 39),
        TripSummary(2.5f, 760_000, 16.2f, 27f),
    )

    private var backs = 0
    private var histories = 0

    private fun show(s: TelemetryUiState = state, fontScale: Float = 1f) = rule.setContent {
        MeuPatineteTheme(darkTheme = false) {
            val d = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(d.density, fontScale)) {
                TelemetryScreen(s, onBack = { backs++ }, onOpenHistory = { histories++ })
            }
        }
    }

    @Test fun `mostra velocidade, bateria e consumo`() {
        show()
        rule.onNodeWithText("Telemetria").assertIsDisplayed()
        rule.onNodeWithTag("speed-value", useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithTag("speed-value", useUnmergedTree = true).assertTextEquals("18")
        rule.onNodeWithTag("battery-pill").assertIsDisplayed()
        rule.onNodeWithContentDescription("Bateria 82%").assertIsDisplayed()
        rule.onNodeWithTag("battery-value", useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithText("461 W").assertIsDisplayed()
        rule.onNodeWithText("25,6 Wh/km").assertIsDisplayed()
        rule.onNodeWithTag("power-bars", useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithText("últimos 30 s", useUnmergedTree = true).assertIsDisplayed()
    }

    /** A1: ícone do patinete sob o velocímetro (design/telemetry.py: icon "scooter" no arco). */
    @Test fun `velocimetro mostra o icone do patinete`() {
        show()
        rule.onNodeWithTag("gauge-scooter", useUnmergedTree = true).assertIsDisplayed()
    }

    /** A2: sem dados não há leitura inventada: barras no piso e "Sem leitura"; com dados há uma barra por amostra. */
    @Test fun `altimetria sem dados mostra grafico vazio e Sem leitura`() {
        show()
        rule.onNodeWithTag("altitude-chart").performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag("altitude-empty").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Sem leitura").assertExists()
    }

    @Test fun `cartoes sem tag de valor nao aplicam testTag vazia`() {
        show(TelemetryUiMapper().map(Telemetry(receivedAtMs = 1, speedKmh = 18f, scooterSpeedKmh = 18f), null, altitudesM = listOf(10f, 12f, 11f, 20f)))
        rule.onAllNodesWithTag("").assertCountEquals(0)
    }

    @Test fun `altimetria com dados mostra as barras e a subida`() {
        show(TelemetryUiMapper().map(Telemetry(receivedAtMs = 1, speedKmh = 18f, scooterSpeedKmh = 18f), null, altitudesM = listOf(10f, 12f, 11f, 20f)))
        rule.onNodeWithTag("altitude-chart").performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag("altitude-empty").assertDoesNotExist()
        rule.onNodeWithText("+11 m").assertExists()
    }

    /** A5: ícones do protótipo nos cartões (design/telemetry.py `stat`). */
    @Test fun `cartoes da telemetria tem os icones do prototipo`() {
        show()
        listOf("tile-pulse", "tile-bolt", "tile-thermo", "tile-route", "tile-speed", "tile-trend", "tile-clock").forEach {
            rule.onAllNodesWithTag(it).onFirst().performScrollTo().assertIsDisplayed()
        }
    }

    @Test fun `rolando mostra temperaturas, odometros e viagem atual`() {
        show()
        rule.onNodeWithText("Viagem atual").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("12:40").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("1.248 km").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Velocidade máxima").performScrollTo().assertIsDisplayed()
    }

    @Test fun `voltar aciona onBack`() {
        show()
        rule.onNodeWithContentDescription("Voltar").performClick()
        assertEquals(1, backs)
    }

    @Test fun `menu tem Ver historico`() {
        show()
        rule.onNodeWithContentDescription("Mais opções").performClick()
        rule.onNodeWithText("Ver histórico").performClick()
        assertEquals(1, histories)
    }

    @Test fun `menu nao tem Zerar viagem e so tem Ver historico`() {
        show()
        rule.onNodeWithContentDescription("Mais opções").performClick()
        rule.onNodeWithText("Ver histórico").assertIsDisplayed()
        rule.onNodeWithText("Zerar viagem").assertDoesNotExist()
        rule.onNodeWithText("Zerar a viagem atual?").assertDoesNotExist()
    }

    @Test fun `motor 2 sem leitura mostra a nota`() {
        show(TelemetryUiMapper().map(Telemetry(receivedAtMs = 1, speedKmh = 0f, motor1TempC = 22), null))
        rule.onNodeWithTag("motor2-note").performScrollTo().assertIsDisplayed()
    }

    @Test fun `escala de fonte 2_0 continua exibindo o essencial`() {
        show(fontScale = 2f)
        rule.onNodeWithTag("speed-value", useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithContentDescription("Voltar").assertIsDisplayed()
    }

    /** Achado 1: com fonte 2,0 em 360 dp a pílula e o card de consumo crescem; nada corta nem passa da tela. */
    @Config(sdk = [35], qualifiers = "w360dp-h780dp-xhdpi")
    @Test fun `360x780 fonte 2_0 pilula de bateria e card de consumo nao cortam nem passam da tela`() {
        show(fontScale = 2f)
        val screenW = rule.onRoot().fetchSemanticsNode().boundsInRoot.width
        fun inside(tag: String, parent: Rect? = null, unmerged: Boolean = false): Rect {
            rule.onNodeWithTag(tag, useUnmergedTree = unmerged).assertIsDisplayed()
            val b = rule.onNodeWithTag(tag, useUnmergedTree = unmerged).fetchSemanticsNode().boundsInRoot
            assertEquals("$tag dentro da tela", true, b.left >= -0.5f && b.right <= screenW + 0.5f)
            parent?.let { assertEquals("$tag dentro do pai", true, b.left >= it.left - 0.5f && b.right <= it.right + 0.5f && b.top >= it.top - 0.5f && b.bottom <= it.bottom + 0.5f) }
            return b
        }
        val pill = inside("battery-pill")
        inside("battery-value", pill, unmerged = true)
        inside("battery-label", pill, unmerged = true)
        val gauge = rule.onNodeWithTag("gauge-scooter", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertEquals("pilula nao sobrepoe o patinete", true, pill.top >= gauge.bottom - 0.5f || pill.bottom <= gauge.top + 0.5f)
        val card = inside("power-card")
        inside("power-bars", card, unmerged = true)
    }

    /** Achado 5: a pílula anuncia uma vez só ("Bateria 82%"); as barras são decorativas. */
    @Test fun `pilula anuncia so Bateria 82 por cento e grafico tem resumo unico`() {
        show(TelemetryUiMapper().map(state.let { Telemetry(receivedAtMs = 1, speedKmh = 18f, scooterSpeedKmh = 18f, batteryPercent = 82, voltageV = 54.9f, currentA = 14f) }, null, powerBars = listOf(0.5f, 1f)))
        rule.onAllNodesWithText("82%").assertCountEquals(0)
        rule.onAllNodesWithText("Bateria").assertCountEquals(0)
        rule.onNodeWithContentDescription("Bateria 82%").assertIsDisplayed()
        // Gráfico: uma única leitura-resumo; legenda e canvas não são anunciados à parte.
        rule.onNodeWithContentDescription("Consumo dos últimos 30 s, escala fixa de 0 a 750 W: agora próximo do teto").assertIsDisplayed()
        rule.onNodeWithTag("power-bars-ceiling", useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithTag("power-bars", useUnmergedTree = true).assertIsDisplayed()
        rule.onAllNodesWithText("últimos 30 s").assertCountEquals(0)
    }

    @Test fun `grafico de consumo sem dado mostra Sem dado do patinete, rotulo 750 W e resumo sem dados`() {
        show(TelemetryUiMapper().map(null, null))
        rule.onNodeWithText("Sem dado do patinete").assertIsDisplayed()
        rule.onNodeWithText("750 W", useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithContentDescription("Consumo dos últimos 30 s: sem dados").assertIsDisplayed()
    }

    @Test fun `resumo a11y descreve a potencia atual e nao a media da ultima fatia`() {
        // Última barra = 0,3 (<= 0,4 daria "bem abaixo"), mas a potência ATUAL é 450 W = 0,6: "abaixo do teto".
        val t = Telemetry(receivedAtMs = 1, speedKmh = 18f, scooterSpeedKmh = 18f, batteryPercent = 82, powerW = 450f, voltageV = 54f, currentA = 8.3f)
        show(TelemetryUiMapper().map(t, null, powerBars = List(23) { 0f } + 0.3f))
        rule.onNodeWithContentDescription("Consumo dos últimos 30 s, escala fixa de 0 a 750 W: agora abaixo do teto").assertIsDisplayed()
    }

    @Test fun `sem dado mostra -- W e esconde barras e linha mesmo com historico`() {
        show(TelemetryUiMapper().map(null, null, powerBars = List(24) { 0.9f }))
        rule.onNodeWithText("-- W").assertIsDisplayed()
        rule.onNodeWithContentDescription("Consumo dos últimos 30 s: sem dados").assertIsDisplayed()
    }

    @Test fun `altura do grafico e 44 dp com fonte normal`() {
        show()
        val d = rule.density.density
        val h1 = rule.onNodeWithTag("power-bars", useUnmergedTree = true).performScrollTo().fetchSemanticsNode().boundsInRoot.height
        assertEquals(44f * d, h1, 1f)
    }

    @Test fun `grafico de consumo parado 0 W e baixo na escala fixa e sem leitura duplicada`() {
        val parado = Telemetry(receivedAtMs = 1, speedKmh = 0f, scooterSpeedKmh = 0f, batteryPercent = 82, voltageV = 54f, currentA = 0f)
        show(TelemetryUiMapper().map(parado, null, powerBars = List(24) { 0f }))
        rule.onNodeWithContentDescription("Consumo dos últimos 30 s, escala fixa de 0 a 750 W: agora bem abaixo do teto").assertIsDisplayed()
        rule.onAllNodesWithText("0 W").assertCountEquals(1)   // o valor em W só aparece no cartão, não no gráfico
    }

    @Test fun `fonte 2_0 mantem grafico de consumo inteiro na largura do cartao`() {
        show(fontScale = 2f)
        val card = rule.onNodeWithTag("power-card").performScrollTo().fetchSemanticsNode().boundsInRoot
        val chart = rule.onNodeWithTag("power-bars", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertEquals("grafico dentro do cartao", true, chart.left >= card.left - 0.5f && chart.right <= card.right + 0.5f)
        assertEquals("altura ampliada (mockup fonte 2,0)", 84f * rule.density.density, chart.height, 1f)
    }

    @Test fun `telemetria velha mostra o aviso e tracos`() {
        show(TelemetryUiMapper().map(Telemetry(receivedAtMs = 1, speedKmh = 18f, scooterSpeedKmh = 18f, batteryPercent = 82), null, stale = true))
        rule.onNodeWithTag("stale-note").assertIsDisplayed()
        rule.onNodeWithText("Dados desatualizados").assertIsDisplayed()
        rule.onNodeWithContentDescription("Bateria --").assertIsDisplayed()
    }

    @Test fun `telemetria atual nao mostra o aviso`() {
        show()
        rule.onNodeWithTag("stale-note").assertDoesNotExist()
    }

    private fun everythingReachable(fontScale: Float) {
        show(fontScale = fontScale)
        rule.onNodeWithContentDescription("Voltar").assertIsDisplayed()
        rule.onNodeWithTag("speed-value", useUnmergedTree = true).assertIsDisplayed()
        // O cabeçalho rola junto com o conteúdo: o menu é usado antes de rolar.
        rule.onNodeWithContentDescription("Mais opções").performClick()
        rule.onNodeWithText("Ver histórico").assertIsDisplayed()
        rule.onNodeWithText("Ver histórico").performClick()
        rule.onNodeWithText("Consumo em tempo real").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Odômetro total").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Velocidade máxima").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Altimetria").performScrollTo().assertIsDisplayed()
    }

    @Config(sdk = [35], qualifiers = "w360dp-h780dp-xhdpi")
    @Test fun `360x780 fonte 1_5 tudo alcancavel e dialogo visivel`() = everythingReachable(1.5f)

    @Config(sdk = [35], qualifiers = "w360dp-h780dp-xhdpi")
    @Test fun `360x780 fonte 2_0 tudo alcancavel e dialogo visivel`() = everythingReachable(2f)

    @Config(sdk = [35], qualifiers = "w360dp-h780dp-xhdpi")
    @Test fun `360x780 fonte 2_0 cabecalho fixo - menu e Voltar visiveis apos rolar ate o fim`() {
        show(fontScale = 2f)
        rule.onNodeWithText("Altimetria").performScrollTo()
        rule.onNodeWithContentDescription("Mais opções").assertIsDisplayed()
        rule.onNodeWithContentDescription("Voltar").assertIsDisplayed()
        rule.onNodeWithContentDescription("Mais opções").performClick()
        rule.onNodeWithText("Ver histórico").assertIsDisplayed()
    }

    // ---- S35: barra fixa Reset | Play/Pause | Parar (mockup telemetria-barra-fixa-*.png) ----
    private var plays = 0
    private var stops = 0
    private var resets = 0

    private fun showBar(rec: br.dev.evaldo.meuscooter.domain.TripRecState, fontScale: Float = 1f) = rule.setContent {
        MeuPatineteTheme(darkTheme = false) {
            val d = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(d.density, fontScale)) {
                TelemetryScreen(
                    TelemetryUiMapper().map(Telemetry(receivedAtMs = 1, speedKmh = 18f, scooterSpeedKmh = 18f), TripSummary(2.5f, 760_000, 16.2f, 27f), recState = rec),
                    onBack = {}, onOpenHistory = {}, onPlayPause = { plays++ }, onStop = { stops++ }, onReset = { resets++ },
                )
            }
        }
    }

    @Test fun `barra fica visivel sem rolar e contando mostra Pausar`() {
        showBar(br.dev.evaldo.meuscooter.domain.TripRecState.Running)
        rule.onNodeWithTag("trip-bar").assertIsDisplayed()
        rule.onNodeWithContentDescription("Pausar viagem").assertIsDisplayed()
        rule.onNodeWithContentDescription("Iniciar viagem").assertDoesNotExist()
        rule.onNodeWithText("Contando").assertIsDisplayed()
    }

    @Test fun `pausada e parada mostram Play`() {
        showBar(br.dev.evaldo.meuscooter.domain.TripRecState.Paused)
        rule.onNodeWithContentDescription("Iniciar viagem").assertIsDisplayed()
        rule.onNodeWithText("Pausada").assertIsDisplayed()
    }

    @Test fun `botoes tem areas de toque de pelo menos 48dp e acionam callbacks`() {
        showBar(br.dev.evaldo.meuscooter.domain.TripRecState.Running)
        listOf("trip-reset", "trip-play-pause", "trip-stop").forEach {
            rule.onNodeWithTag(it).assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
        }
        rule.onNodeWithTag("trip-play-pause").performClick()
        rule.onNodeWithTag("trip-stop").performClick()
        assertEquals(1, plays); assertEquals(1, stops)
    }

    @Test fun `reset pede confirmacao e so descarta ao confirmar`() {
        showBar(br.dev.evaldo.meuscooter.domain.TripRecState.Running)
        rule.onNodeWithTag("trip-reset").performClick()
        rule.onNodeWithTag("reset-dialog").assertIsDisplayed()
        assertEquals(0, resets)
        rule.onNodeWithTag("reset-cancel").performClick()
        assertEquals(0, resets)
        rule.onNodeWithTag("trip-reset").performClick()
        rule.onNodeWithTag("reset-confirm").performClick()
        assertEquals(1, resets)
    }

    @Test fun `reset fica desabilitado com a viagem parada e nao abre dialogo`() {
        showBar(br.dev.evaldo.meuscooter.domain.TripRecState.Stopped)
        rule.onNodeWithTag("trip-reset").assertIsNotEnabled().performClick()
        rule.onNodeWithTag("reset-dialog").assertDoesNotExist()
        assertEquals(0, resets)
    }

    @Test fun `parar fica habilitado gravando e pausada`() {
        showBar(br.dev.evaldo.meuscooter.domain.TripRecState.Running)
        rule.onNodeWithTag("trip-stop").assertIsEnabled()
        rule.onNodeWithTag("trip-stop").performClick()
        assertEquals(1, stops)
    }

    @Test fun `parar fica desabilitado sem percurso em gravacao e o clique e ignorado`() {
        showBar(br.dev.evaldo.meuscooter.domain.TripRecState.Stopped)
        rule.onNodeWithTag("trip-stop").assertIsNotEnabled().performClick()
        assertEquals(0, stops)
        rule.onNodeWithContentDescription("Parar viagem").assertIsNotEnabled()
    }

    @Test fun `icone GPS do velocimetro tem 28dp dentro de borda de 40dp`() {
        showSwitchable()
        toggleGauge()
        rule.waitForIdle()
        rule.onNodeWithTag("gauge-gps-ring", useUnmergedTree = true).assertWidthIsEqualTo(40.dp).assertHeightIsEqualTo(40.dp)
        rule.onNodeWithTag("gauge-gps", useUnmergedTree = true).assertWidthIsEqualTo(28.dp).assertHeightIsEqualTo(28.dp)
    }

    @Test fun `reset fica habilitado contando e pausada`() {
        showBar(br.dev.evaldo.meuscooter.domain.TripRecState.Paused)
        rule.onNodeWithTag("trip-reset").assertIsEnabled()
    }

    @Test fun `duracao e o primeiro cartao e altimetria ocupa a largura do cartao da viagem`() {
        showBar(br.dev.evaldo.meuscooter.domain.TripRecState.Running)
        val gauge = rule.onNodeWithTag("duration-card").fetchSemanticsNode().boundsInRoot
        val power = rule.onNodeWithTag("power-card").fetchSemanticsNode().boundsInRoot
        assertEquals(true, gauge.bottom <= power.top)
        rule.onNodeWithTag("duration-card").assertWidthIsAtLeast(300.dp)
        rule.onNodeWithTag("power-card").assertWidthIsAtLeast(300.dp)
        rule.onNodeWithTag("trip-card").performScrollTo()
        rule.onNodeWithTag("altitude-chart").performScrollTo().assertIsDisplayed()
    }

    @Test fun `com fonte 2 a barra continua acessivel`() {
        showBar(br.dev.evaldo.meuscooter.domain.TripRecState.Running, fontScale = 2f)
        rule.onNodeWithTag("trip-play-pause").assertIsDisplayed()
    }

    // ---- S42: fonte do velocímetro (deslizar) ----
    private val both = Telemetry(receivedAtMs = 1, speedKmh = 17f, scooterSpeedKmh = 19.8f, batteryPercent = 82)
    private val chosen = mutableListOf<SpeedSource>()

    /** Hospedeiro com estado, como o ViewModel: o mapper recebe a fonte escolhida. */
    private fun showSwitchable(initial: SpeedSource = SpeedSource.Scooter) {
        chosen.clear()
        rule.setContent {
            MeuPatineteTheme(darkTheme = false) {
                var src by androidx.compose.runtime.remember { mutableStateOf(initial) }
                TelemetryScreen(
                    TelemetryUiMapper().map(both, null, speedSource = src), onBack = {}, onOpenHistory = {},
                    onSpeedSource = { src = it; chosen += it },
                )
            }
        }
    }

    @Test fun `padrao mostra patinete com icone e pontinho 1 ativo, sem seta`() {
        showSwitchable()
        rule.onNodeWithTag("speed-value", useUnmergedTree = true).assertTextEquals("20")
        rule.onNodeWithTag("gauge-scooter", useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithTag("gauge-gps", useUnmergedTree = true).assertDoesNotExist()
        rule.onAllNodesWithTag("speed-dot-0", useUnmergedTree = true).assertCountEquals(1); rule.onAllNodesWithTag("speed-dot-1", useUnmergedTree = true).assertCountEquals(1)
    }

    @Test fun `deslizar para a esquerda mostra o GPS e para a direita volta ao patinete`() {
        showSwitchable()
        rule.onNodeWithTag("speed-pager", useUnmergedTree = true).performTouchInput { swipeLeft() }
        rule.waitForIdle()
        rule.onNodeWithTag("speed-value", useUnmergedTree = true).assertTextEquals("17")
        rule.onNodeWithTag("gauge-gps", useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithTag("gauge-scooter", useUnmergedTree = true).assertDoesNotExist()
        assertEquals(listOf(SpeedSource.Gps), chosen)
        rule.onNodeWithTag("speed-pager", useUnmergedTree = true).performTouchInput { swipeRight() }
        rule.waitForIdle()
        rule.onNodeWithTag("speed-value", useUnmergedTree = true).assertTextEquals("20")
        rule.onNodeWithTag("gauge-scooter", useUnmergedTree = true).assertIsDisplayed()
        assertEquals(listOf(SpeedSource.Gps, SpeedSource.Scooter), chosen)
    }

    @Test fun `deslizar para a direita no padrao nao muda nada`() {
        showSwitchable()
        rule.onNodeWithTag("speed-pager", useUnmergedTree = true).performTouchInput { swipeRight() }
        rule.waitForIdle()
        assertEquals(emptyList<SpeedSource>(), chosen)
        rule.onNodeWithTag("speed-value", useUnmergedTree = true).assertTextEquals("20")
    }

    @Test fun `fonte lembrada GPS abre ja no GPS`() {
        showSwitchable(SpeedSource.Gps)
        rule.onNodeWithTag("speed-value", useUnmergedTree = true).assertTextEquals("17")
        rule.onNodeWithTag("gauge-gps", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test fun `acessibilidade expoe acao de trocar a fonte e o estado atual`() {
        showSwitchable()
        fun actions() = rule.onNodeWithTag("speed-gauge").fetchSemanticsNode().config[SemanticsActions.CustomActions].map { it.label }
        assertEquals(listOf("Mostrar velocidade do GPS"), actions())
        assertEquals("Velocidade do patinete", rule.onNodeWithTag("speed-gauge").fetchSemanticsNode().config[SemanticsProperties.StateDescription])
        rule.runOnUiThread { rule.onNodeWithTag("speed-gauge").fetchSemanticsNode().config[SemanticsActions.CustomActions].first().action() }
        rule.waitForIdle()
        assertEquals(listOf(SpeedSource.Gps), chosen)
        rule.onNodeWithTag("speed-value", useUnmergedTree = true).assertTextEquals("17")
        assertEquals(listOf("Mostrar velocidade do patinete"), actions())
    }

    private fun speedNodes() = rule.onAllNodesWithTag("speed-value", useUnmergedTree = true).fetchSemanticsNodes()
    private fun leftOf(text: String): Float? = speedNodes().firstOrNull { it.config[SemanticsProperties.Text].joinToString("") == text }?.positionInRoot?.x
    private fun gaugeWidth() = rule.onNodeWithTag("speed-gauge").fetchSemanticsNode().boundsInRoot.width
    private fun toggleGauge() = rule.runOnUiThread { rule.onNodeWithTag("speed-gauge").fetchSemanticsNode().config[SemanticsActions.CustomActions].first().action() }
    /** Avança o relógio e consulta a árvore (sem consulta a recomposição não é entregue). */
    private fun advance(ms: Long) { rule.mainClock.advanceTimeBy(ms); speedNodes() }

    private fun iconCenterX(tag: String) = rule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().let { it.positionInRoot.x + it.size.width / 2f }
    private fun gaugeCenterX() = rule.onNodeWithTag("speed-gauge").fetchSemanticsNode().let { it.boundsInRoot.left + it.boundsInRoot.width / 2f }

    /** Avança em quadros de 16 ms até [ms] depois de [start] (relógio de teste), consultando a árvore a cada quadro. */
    private fun advanceTo(start: Long, ms: Long) { while (rule.mainClock.currentTime - start < ms) advance(16) }

    /**
     * Troca a fonte e confere, relativo ao toggle: entra pela direita, sai pela esquerda, tween(300) com FastOutSlowIn.
     * Curva FastOutSlowIn (0.4,0,0.2,1) calculada a mão: t=0,5 -> 0,77; t=0,83 -> 0,98 (tween(200) já terminaria em +250 ms).
     */
    private fun labelXs() = rule.onAllNodesWithTag("speed-label", useUnmergedTree = true).fetchSemanticsNodes().map { it.positionInRoot.x }.sorted()

    private fun assertSlide(outText: String, inText: String, outRest: Float, inRest: Float) {
        rule.mainClock.autoAdvance = true; rule.waitForIdle(); rule.mainClock.autoAdvance = false   // semântica atual (a ação de toggle não fica velha)
        val w = gaugeWidth()
        val labelRest = labelXs().single()   // "Velocidade atual" faz parte da face: 1 só em repouso
        val start = rule.mainClock.currentTime
        toggleGauge()
        while (speedNodes().size < 2 && rule.mainClock.currentTime - start < 64) advance(16)
        assertEquals(2, speedNodes().size)
        assertEquals(true, leftOf(inText)!! - inRest >= w * 0.8f)
        assertEquals(true, leftOf(outText)!! - outRest <= 0.5f)
        advanceTo(start, 150)
        val inMid = (leftOf(inText)!! - inRest) / w   // fração restante do caminho (0 = repouso)
        val outMid = leftOf(outText)!! - outRest
        assertEquals("em +150 ms percorreu 65-90% (esperado ~77%): restante=$inMid", true, inMid in 0.10f..0.35f)
        assertEquals("sainte ja deslocada para a esquerda", true, outMid < -1f)
        rule.onAllNodesWithTag("gauge-scooter", useUnmergedTree = true).assertCountEquals(1)
        labelXs().let { xs ->   // o rótulo desliza com a face: um saindo (à esquerda) e outro entrando (à direita)
            assertEquals(2, xs.size)
            assertEquals(true, xs[0] - labelRest < -1f)
            assertEquals(true, xs[1] - labelRest > 1f)
        }
        advanceTo(start, 250)
        assertEquals("em +250 ms ainda nao terminou (esperado ~1,6% restante)", 2, speedNodes().size)
        assertEquals(true, leftOf(inText)!! - inRest > 0f)
        advanceTo(start, 316)   // 300 ms + 1 quadro
        advance(16)
        assertEquals(1, speedNodes().size)
        assertEquals(inRest, leftOf(inText)!!, 0.5f)
        assertEquals(null, leftOf(outText))
        assertEquals(labelRest, labelXs().single(), 0.5f)
    }

    @Test fun `trocar a fonte desliza a face antiga para a esquerda e a nova vem da direita nas duas direcoes`() {
        // 1ª composição não anima: relógio parado ANTES de compor; sem avanço, 1 face já no repouso (centro do gauge).
        rule.mainClock.autoAdvance = false
        showSwitchable()
        assertEquals(1, speedNodes().size)
        assertEquals(gaugeCenterX(), iconCenterX("gauge-scooter"), 0.5f)   // ícone da face (centrado no gauge) em repouso
        val rest20 = leftOf("20")!!
        rule.mainClock.autoAdvance = true
        toggleGauge()
        rule.waitForIdle()   // relógio automático: assenta a troca para medir a posição de repouso
        rule.mainClock.autoAdvance = false
        val rest17 = leftOf("17")!!
        assertEquals(1, speedNodes().size)
        assertEquals(gaugeCenterX(), iconCenterX("gauge-gps"), 0.5f)
        // a11y: o pai segue com leitura única (sem nós filhos expostos).
        rule.onAllNodesWithText("17").assertCountEquals(0); rule.onAllNodesWithText("20").assertCountEquals(0)
        // volta GPS -> patinete: sai "17" pela esquerda, entra "20" pela direita
        assertSlide(outText = "17", inText = "20", outRest = rest17, inRest = rest20)
        // ida patinete -> GPS
        assertSlide(outText = "20", inText = "17", outRest = rest20, inRest = rest17)
    }

    @Test fun `velocimetro tem leitura unica para o TalkBack sem nos duplicados`() {
        showSwitchable()
        val cfg = rule.onNodeWithTag("speed-gauge").fetchSemanticsNode().config
        assertEquals(listOf("Velocidade 20 km/h"), cfg[SemanticsProperties.ContentDescription])
        assertEquals(false, cfg.contains(SemanticsProperties.Text))
        rule.onAllNodesWithText("Velocidade atual").assertCountEquals(0)   // árvore mesclada: só a leitura única
        rule.onAllNodesWithText("20").assertCountEquals(0)
    }

    @Test fun `fonte 2_0 nao corta icone nem pontinhos do velocimetro`() {
        rule.setContent {
            val d = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(d.density, 2f)) {
                MeuPatineteTheme(darkTheme = false) {
                    TelemetryScreen(TelemetryUiMapper().map(both, null), onBack = {}, onOpenHistory = {})
                }
            }
        }
        val density = rule.density.density
        fun bounds(tag: String) = rule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val gauge = bounds("speed-gauge"); val icon = bounds("gauge-scooter"); val dots = bounds("speed-dots")
        assertEquals(40f * density, icon.width, 1f)       // ícone com o tamanho cheio (40 dp): não cortado
        assertEquals(40f * density, icon.height, 1f)
        assertTrue("ícone fora do velocímetro: $icon em $gauge", icon.left >= gauge.left && icon.right <= gauge.right && icon.bottom <= gauge.bottom + 1f)
        assertTrue("pontinhos sobrepõem o ícone: $dots vs $icon", dots.top >= icon.bottom - 1f)
        assertEquals(22f * density, dots.width, 1f)       // 2 x 8 dp + 6 dp de espaço: nenhum ponto cortado
        assertEquals(8f * density, dots.height, 1f)
    }
}

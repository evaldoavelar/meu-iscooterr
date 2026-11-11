package br.dev.evaldo.meuscooter.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.unit.Density
import br.dev.evaldo.meuscooter.domain.GeoPoint
import br.dev.evaldo.meuscooter.domain.Trip
import br.dev.evaldo.meuscooter.ui.history.HistoryScreen
import br.dev.evaldo.meuscooter.ui.history.TripDetailScreen
import br.dev.evaldo.meuscooter.ui.theme.MeuPatineteTheme
import java.time.ZoneOffset
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** S4: textos de design/history.py ("Histórico", "Nenhuma viagem ainda", "Bateria na viagem", rodapé). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class HistoryScreensTest {
    @get:Rule val rule = createComposeRule()

    private val zone = ZoneOffset.UTC
    private val now = ZonedDateTime.of(2026, 11, 3, 20, 0, 0, 0, zone).toInstant().toEpochMilli()
    private val mapper = HistoryUiMapper(zone)
    private val route = listOf(GeoPoint(-23.55, -46.63), GeoPoint(-23.54, -46.62), GeoPoint(-23.53, -46.60))
    private val t1 = Trip(1, ZonedDateTime.of(2026, 11, 3, 18, 42, 0, 0, zone).toInstant().toEpochMilli(), 4.8f, 21 * 60_000L, 14f, 27f,
        avgWhPerKm = 24f, batteryStartPercent = 82, batteryEndPercent = 76, route = route)
    private val t2 = Trip(2, ZonedDateTime.of(2026, 11, 2, 19, 3, 0, 0, zone).toInstant().toEpochMilli(), 7.9f, 32 * 60_000L, 15f, 31f)

    private var opened = mutableListOf<Long>()
    private var deleted = mutableListOf<Long>()
    private var backs = 0

    private fun showHistory(trips: List<Trip>, fontScale: Float = 1f) = rule.setContent {
        MeuPatineteTheme(darkTheme = false) {
            val d = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(d.density, fontScale)) {
                HistoryScreen(
                    mapper.map(trips, now),
                    onBack = { backs++ },
                    onOpenTrip = { opened += it },
                    onDeleteTrip = { deleted += it },
                )
            }
        }
    }

    @Test fun `gesto revela acao sem abrir dialogo e so o toque no bloco abre`() {
        showHistory(listOf(t1, t2))
        rule.onNodeWithTag("trip-1").performTouchInput { swipeLeft() }
        rule.waitForIdle()
        rule.onNodeWithTag("delete-dialog").assertDoesNotExist()
        rule.onNodeWithTag("action-delete-1").assertIsDisplayed()
        rule.onNodeWithTag("action-delete-1").performClick()
        rule.onNodeWithTag("delete-dialog").assertIsDisplayed()
        rule.onNodeWithText("Excluir esta viagem?").assertIsDisplayed()
        rule.onNodeWithText("Hoje, 18:42 · 4,8 km · 21 min. O trajeto no mapa também será apagado. Isso não pode ser desfeito.").assertIsDisplayed()
    }

    @Test fun `dialogo cancelar fecha o dialogo e o card e nao exclui`() {
        showHistory(listOf(t1, t2))
        rule.onNodeWithTag("trip-1").performTouchInput { swipeLeft() }
        rule.onNodeWithTag("action-delete-1").performClick()
        rule.onNodeWithTag("delete-cancel").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("delete-dialog").assertDoesNotExist()
        rule.onNodeWithTag("action-delete-1").assertDoesNotExist()
        assertEquals(emptyList<Long>(), deleted)
    }

    @Test fun `dialogo confirmar chama callback com o id certo`() {
        showHistory(listOf(t1, t2))
        rule.onNodeWithTag("trip-1").performTouchInput { swipeLeft() }
        rule.onNodeWithTag("action-delete-1").performClick()
        rule.onNodeWithTag("delete-confirm").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("delete-dialog").assertDoesNotExist()
        rule.onNodeWithTag("action-delete-1").assertDoesNotExist()
        assertEquals(listOf(1L), deleted)
    }

    @Test fun `tocar no card aberto fecha sem abrir detalhe e arrastar de volta tambem fecha`() {
        showHistory(listOf(t1, t2))
        rule.onNodeWithTag("trip-1").performTouchInput { swipeLeft() }
        rule.waitForIdle()
        rule.onNodeWithTag("action-delete-1").assertIsDisplayed()
        rule.onNodeWithTag("trip-1").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("action-delete-1").assertDoesNotExist()
        assertEquals(emptyList<Long>(), opened)
        rule.onNodeWithTag("trip-1").performTouchInput { swipeLeft() }
        rule.waitForIdle()
        rule.onNodeWithTag("trip-1").performTouchInput { swipeRight() }
        rule.waitForIdle()
        rule.onNodeWithTag("action-delete-1").assertDoesNotExist()
    }

    @Test fun `acao customizada de acessibilidade abre o dialogo de exclusao`() {
        showHistory(listOf(t1, t2))
        val node = rule.onNodeWithTag("trip-1").fetchSemanticsNode()
        val custom = node.config[androidx.compose.ui.semantics.SemanticsActions.CustomActions].first { it.label == "Excluir viagem" }
        custom.action()
        rule.onNodeWithTag("delete-dialog").assertIsDisplayed()
    }

    @Test fun `lista mostra resumo, secoes e viagens`() {
        showHistory(listOf(t1, t2))
        rule.onNodeWithText("Histórico").assertIsDisplayed()
        rule.onNodeWithText("Viagens").assertIsDisplayed()
        rule.onNodeWithText("12,7 km").assertIsDisplayed()           // 4,8 + 7,9
        rule.onNodeWithText("Hoje").assertIsDisplayed()
        rule.onNodeWithText("Hoje, 18:42").assertIsDisplayed()
        rule.onNodeWithText("4,8 km · 21 min").assertIsDisplayed()
        rule.onNodeWithText("Ontem, 19:03").assertIsDisplayed()
    }

    /** A7: o resumo nunca quebra linha — com fonte 2,0 o texto encolhe em vez de quebrar. */
    private fun assertSingleLine(text: String) {
        val results = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        rule.onNodeWithText(text).fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult].action!!.invoke(results)
        assertEquals("$text quebrou linha", 1, results.single().lineCount)
        assertEquals("$text cortado", false, results.single().hasVisualOverflow)
    }

    private fun showSummary(state: HistoryUiState, fontScale: Float) = rule.setContent {
        MeuPatineteTheme(darkTheme = false) {
            val d = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(d.density, fontScale)) { HistoryScreen(state, onBack = {}, onOpenTrip = {}) }
        }
    }

    private val longSummary = HistoryUiState(HistorySummaryUi("13", "86,4 km", "5 h 15"), emptyList(), false)

    @Test fun `resumo tem 3 colunas iguais centralizadas com padding 20x16 e divisores`() {
        showSummary(longSummary, 1f)
        rule.waitForIdle()
        val dp = rule.density.density
        val card = rule.onNodeWithTag("history-summary").fetchSemanticsNode().boundsInRoot
        val b = (0..2).map { rule.onNodeWithTag("summary-item-$it").fetchSemanticsNode().boundsInRoot }
        b.forEach { assertEquals(b[0].width, it.width, 1f) }
        assertEquals(card.left + 20f * dp, b[0].left, 1.5f)   // padding 20dp (a borda não insere espaço)
        assertEquals(card.right - 20f * dp, b[2].right, 1.5f)
        assertEquals(card.top + 16f * dp, b.minOf { it.top }, 1.5f)
        // centralização: texto do valor centralizado na coluna
        val v = rule.onNodeWithText("13").fetchSemanticsNode().boundsInRoot
        assertEquals(b[0].center.x, v.center.x, 1.5f)
        (1..2).forEach { rule.onNodeWithTag("summary-divider-$it").assertIsDisplayed() }
        assertEquals(1f * dp, rule.onNodeWithTag("summary-divider-1").fetchSemanticsNode().boundsInRoot.width, 0.6f)
    }

    @Config(sdk = [35], qualifiers = "w360dp-h780dp-xhdpi")
    @Test fun `resumo longo sem corte em fonte 1_0 e empilhado com divisores horizontais em fonte 2_0`() {
        showSummary(longSummary, 1f)
        rule.waitForIdle()
        listOf("13", "86,4 km", "5 h 15", "Viagens", "Distância", "Tempo").forEach { assertSingleLine(it) }
    }

    @Config(sdk = [35], qualifiers = "w360dp-h780dp-xhdpi")
    @Test fun `resumo empilhado em fonte 2_0 sem corte`() {
        showSummary(longSummary, 2f)
        rule.waitForIdle()
        val b = (0..2).map { rule.onNodeWithTag("summary-item-$it").fetchSemanticsNode().boundsInRoot }
        assertEquals(b[0].left, b[1].left, 1f)
        assertEquals(true, b[1].top >= b[0].bottom && b[2].top >= b[1].bottom)
        (1..2).forEach { assertEquals(true, rule.onNodeWithTag("summary-divider-$it").fetchSemanticsNode().boundsInRoot.let { d -> d.width > d.height }) }
        // fonte ampliada (mockup resumo-historico-A-fonte2): rótulo ACIMA do valor, em cada item
        listOf("Viagens" to "13", "Distância" to "86,4 km", "Tempo" to "5 h 15").forEachIndexed { i, (l, v) ->
            val lb = rule.onNodeWithText(l).fetchSemanticsNode().boundsInRoot
            val vb = rule.onNodeWithText(v).fetchSemanticsNode().boundsInRoot
            assertEquals("rótulo $l acima de $v", true, lb.bottom <= vb.top + 0.5f)
            assertEquals(true, lb.top >= b[i].top - 0.5f && vb.bottom <= b[i].bottom + 0.5f)
        }
        listOf("13", "86,4 km", "5 h 15", "Viagens", "Distância", "Tempo").forEach { assertSingleLine(it) }
    }

    @Config(sdk = [35], qualifiers = "w360dp-h780dp-xhdpi")
    @Test fun `resumo do historico nao quebra linha com fonte 2_0`() {
        showHistory(listOf(t1, t2), fontScale = 2f)
        rule.waitForIdle()
        listOf("Viagens", "Distância", "Tempo").forEach { assertSingleLine(it) }
    }

    @Config(sdk = [35], qualifiers = "w360dp-h780dp-xhdpi")
    @Test fun `resumo do historico nao quebra linha com fonte 1_5`() {
        showHistory(listOf(t1, t2), fontScale = 1.5f)
        rule.waitForIdle()
        listOf("Viagens", "Distância", "Tempo").forEach { assertSingleLine(it) }
    }

    /** A5: ícones do protótipo nas estatísticas da viagem (design/history.py `detalhe`). */
    @Test fun `detalhe da viagem mostra os icones das estatisticas`() {
        showDetail(t1)
        listOf("tile-route", "tile-clock", "tile-speed", "tile-trend", "tile-mountain", "tile-bolt", "tile-battery").forEach {
            rule.onNodeWithTag(it).performScrollTo().assertIsDisplayed()
        }
    }

    @Test fun `tocar numa viagem abre o detalhe com o id`() {
        showHistory(listOf(t1, t2))
        rule.onNodeWithText("Ontem, 19:03").performClick()
        assertEquals(listOf(2L), opened)
    }

    @Test fun `voltar aciona onBack`() {
        showHistory(listOf(t1))
        rule.onNodeWithContentDescription("Voltar").performClick()
        assertEquals(1, backs)
    }

    @Test fun `sem viagens mostra o estado vazio`() {
        showHistory(emptyList())
        rule.onNodeWithText("Nenhuma viagem ainda").assertIsDisplayed()
        rule.onNodeWithText("Suas viagens aparecem aqui, com o trajeto no mapa. Tudo fica só no seu celular.").assertIsDisplayed()
    }

    @Test fun `escala de fonte 2_0 mantem a lista utilizavel`() {
        showHistory(listOf(t1, t2), fontScale = 2f)
        rule.onNodeWithText("Hoje, 18:42").performScrollTo().assertIsDisplayed()
    }

    private fun showDetail(t: Trip) = rule.setContent {
        MeuPatineteTheme(darkTheme = false) { TripDetailScreen(mapper.detail(t, now), t.route, onBack = { backs++ }) }
    }

    @Test fun `detalhe mostra mapa, estatisticas, bateria e rodape`() {
        showDetail(t1)
        rule.onNodeWithText("Hoje, 18:42").assertIsDisplayed()
        rule.onNodeWithTag("route-map").assertIsDisplayed()
        rule.onNodeWithText("Velocidade máxima").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("24 Wh/km").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Bateria na viagem").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("82% → 76%").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Trajetos e viagens ficam salvos só no seu celular.").performScrollTo().assertIsDisplayed()
    }

    @Test fun `detalhe sem trajeto avisa e sem bateria omite o cartao`() {
        showDetail(t2)
        rule.onNodeWithTag("route-empty").assertIsDisplayed()
        rule.onNodeWithText("Bateria na viagem").assertDoesNotExist()
    }

    @Test fun `detalhe de viagem com distancia zero e rota de um ponto mostra 0,0 km e avisa sem trajeto`() {
        val zeroTrip = Trip(
            id = 3,
            startedAtMs = ZonedDateTime.of(2026, 11, 3, 10, 0, 0, 0, zone).toInstant().toEpochMilli(),
            distanceKm = 0f,
            durationMs = 120_000L,
            avgKmh = 0f,
            maxKmh = 10f,
            route = listOf(GeoPoint(-23.55, -46.63)),
        )
        showDetail(zeroTrip)
        rule.onNodeWithText("0,0 km").assertIsDisplayed()
        rule.onNodeWithText("0 km/h").assertIsDisplayed()
        rule.onNodeWithText("10 km/h").assertIsDisplayed()
        rule.onNodeWithTag("route-empty").assertIsDisplayed()
    }

    private fun showDetailScaled(t: Trip, fontScale: Float) = rule.setContent {
        MeuPatineteTheme(darkTheme = false) {
            val d = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(d.density, fontScale)) { TripDetailScreen(mapper.detail(t, now), t.route, onBack = { backs++ }) }
        }
    }

    private fun historyReachable(fontScale: Float) {
        showHistory(listOf(t1, t2), fontScale)
        rule.onNodeWithContentDescription("Voltar").assertIsDisplayed()
        rule.onNodeWithText("Viagens").assertIsDisplayed()
        rule.onNodeWithText("Ontem, 19:03").performScrollTo().assertIsDisplayed()
    }

    private fun detailReachable(fontScale: Float) {
        showDetailScaled(t1, fontScale)
        rule.onNodeWithContentDescription("Voltar").assertIsDisplayed()
        rule.onNodeWithTag("route-map").assertIsDisplayed()
        rule.onNodeWithText("82% → 76%").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Trajetos e viagens ficam salvos só no seu celular.").performScrollTo().assertIsDisplayed()
    }

    @Config(sdk = [35], qualifiers = "w360dp-h780dp-xhdpi")
    @Test fun `360x780 fonte 1_5 historico alcancavel`() = historyReachable(1.5f)

    @Config(sdk = [35], qualifiers = "w360dp-h780dp-xhdpi")
    @Test fun `360x780 fonte 2_0 historico alcancavel`() = historyReachable(2f)

    @Config(sdk = [35], qualifiers = "w360dp-h780dp-xhdpi")
    @Test fun `360x780 fonte 1_5 detalhe alcancavel`() = detailReachable(1.5f)

    @Config(sdk = [35], qualifiers = "w360dp-h780dp-xhdpi")
    @Test fun `360x780 fonte 2_0 detalhe alcancavel`() = detailReachable(2f)
}

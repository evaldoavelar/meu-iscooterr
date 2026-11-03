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
    private var backs = 0

    private fun showHistory(trips: List<Trip>, fontScale: Float = 1f) = rule.setContent {
        MeuPatineteTheme(darkTheme = false) {
            val d = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(d.density, fontScale)) {
                HistoryScreen(mapper.map(trips, now), onBack = { backs++ }, onOpenTrip = { opened += it })
            }
        }
    }

    @Test fun `lista mostra resumo, secoes e viagens`() {
        showHistory(listOf(t1, t2))
        rule.onNodeWithText("Histórico").assertIsDisplayed()
        rule.onNodeWithText("Viagens · 30 dias").assertIsDisplayed()
        rule.onNodeWithText("12,7 km").assertIsDisplayed()           // 4,8 + 7,9
        rule.onNodeWithText("Hoje").assertIsDisplayed()
        rule.onNodeWithText("Hoje, 18:42").assertIsDisplayed()
        rule.onNodeWithText("4,8 km · 21 min").assertIsDisplayed()
        rule.onNodeWithText("Ontem, 19:03").assertIsDisplayed()
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
}

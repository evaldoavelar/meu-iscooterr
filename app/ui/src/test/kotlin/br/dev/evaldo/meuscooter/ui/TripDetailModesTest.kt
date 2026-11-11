package br.dev.evaldo.meuscooter.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import br.dev.evaldo.meuscooter.domain.GeoPoint
import br.dev.evaldo.meuscooter.domain.Trip
import br.dev.evaldo.meuscooter.domain.TripModeEvent
import br.dev.evaldo.meuscooter.ui.history.NO_MODE_NOTICE
import br.dev.evaldo.meuscooter.ui.history.TripDetailScreen
import br.dev.evaldo.meuscooter.ui.theme.MeuPatineteTheme
import java.time.ZoneOffset
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** S43-TRIPUI: mapper domínio→UI (cores/padrões, %, horários) e estados do detalhe da viagem (design/s43.py). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class TripDetailModesTest {
    @get:Rule val rule = createComposeRule()

    private val zone = ZoneOffset.UTC
    private val now = ZonedDateTime.of(2026, 11, 3, 20, 0, 0, 0, zone).toInstant().toEpochMilli()
    private val start = ZonedDateTime.of(2026, 11, 3, 18, 42, 0, 0, zone).toInstant().toEpochMilli()
    private val mapper = HistoryUiMapper(zone)
    private val min = 60_000L
    private val route = (0..20).map { GeoPoint(-23.55 + it * 0.001, -46.63 + it * 0.001 + (it % 3) * 0.0002) }

    // Walking 4 min, Eco 6, Sport 5+3, Race 3 (duração 21 min). Motor: 2 (0-8), 1 (8-13), 2 (13-21).
    private val events = listOf(
        TripModeEvent(0, 0, true), TripModeEvent(4 * min, 1, true), TripModeEvent(8 * min, 1, false), TripModeEvent(10 * min, 3, false),
        TripModeEvent(13 * min, 3, true), TripModeEvent(15 * min, 2, true), TripModeEvent(18 * min, 3, true),
    )
    private fun trip(ev: List<TripModeEvent>, r: List<GeoPoint> = route) =
        Trip(1, start, 4.8f, 21 * min, 14f, 27f, 24f, 82, 76, r, ev)

    // ---- mapper ----
    @Test fun `mapper por modo - cores padroes percentuais minutos e horarios das trocas`() {
        val m = mapper.detail(trip(events), now).modes
        val v = requireNotNull(m.mode)
        assertEquals(listOf(SegTone.Success, SegTone.Primary, SegTone.Pending, SegTone.Error, SegTone.Pending), v.segments.map { it.tone })
        assertEquals(listOf(LinePattern.Dotted, LinePattern.Dashed, LinePattern.Solid, LinePattern.Thick, LinePattern.Solid), v.segments.map { it.pattern })
        assertEquals(listOf("Walking", "Eco", "Sport", "Race"), v.legend.map { it.label })
        assertEquals(listOf("4 min · 19 %", "6 min · 29 %", "8 min · 38 %", "3 min · 14 %"), v.timeRows.map { it.value })
        assertEquals(listOf("18:46", "18:52", "18:57", "19:00"), v.changes.map { it.time })
        assertEquals(listOf("Walking → Eco", "Eco → Sport", "Sport → Race", "Race → Sport"), v.changes.map { it.text })
        assertEquals("18:42", v.startTime); assertEquals("19:03", v.endTime)
        assertEquals(4, v.markers.size); assertTrue(v.markers.all { it.at != null })
        assertNull(v.singleText)
    }

    @Test fun `mapper por motor - 2 motores primary solido e 1 motor pending tracejado`() {
        val v = requireNotNull(mapper.detail(trip(events), now).modes.motor)
        assertEquals(listOf(SegTone.Primary, SegTone.Pending, SegTone.Primary), v.segments.map { it.tone })
        assertEquals(listOf(LinePattern.Solid, LinePattern.Dashed, LinePattern.Solid), v.segments.map { it.pattern })
        assertEquals(listOf("2 motores", "1 motor"), v.timeRows.map { it.label })
        assertEquals(listOf("16 min · 76 %", "5 min · 24 %"), v.timeRows.map { it.value })
        assertEquals(listOf("18:50 2 motores → 1 motor", "18:55 1 motor → 2 motores"), v.changes.map { "${it.time} ${it.text}" })
        assertEquals("Tempo com cada motor", v.timesTitle)
    }

    @Test fun `mapper modo unico nao tem trocas e diz isso`() {
        val v = requireNotNull(mapper.detail(trip(listOf(TripModeEvent(0, 1, true))), now).modes.mode)
        assertEquals("Sem trocas: Eco durante toda a viagem.", v.singleText)
        assertTrue(v.changes.isEmpty()); assertTrue(v.markers.isEmpty())
        assertEquals(listOf("21 min · 100 %"), v.timeRows.map { it.value })
    }

    @Test fun `mapper sem eventos nao tem dado e sem trajeto mantem linha do tempo sem segmentos`() {
        assertTrue(!mapper.detail(trip(emptyList()), now).modes.hasData)
        val v = requireNotNull(mapper.detail(trip(events, emptyList()), now).modes.mode)
        assertTrue(v.segments.isEmpty()); assertTrue(v.markers.isEmpty()); assertEquals(5, v.blocks.size); assertEquals(4, v.changes.size)
    }

    @Test fun `mapper descricoes de acessibilidade citam textos equivalentes`() {
        val v = requireNotNull(mapper.detail(trip(events), now).modes.mode)
        assertTrue(v.timelineDescription.contains("Walking das 18:42 (4 min)"))
        assertTrue(v.mapDescription.contains("18:46 Walking → Eco"))
    }

    // ---- telas ----
    private fun show(t: Trip, scale: Float = 1f, notices: MutableList<String> = mutableListOf()) = rule.setContent {
        MeuPatineteTheme(darkTheme = false) {
            val d = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(d.density, scale)) { TripDetailScreen(mapper.detail(t, now), t.route, {}, onNotice = { notices += it }) }
        }
    }

    @Test fun `com dado abre em Modo com legenda linha do tempo e tempo em cada modo`() {
        val n = mutableListOf<String>()
        show(trip(events), notices = n)
        rule.onNodeWithTag("trip-view-Modo").assertIsSelected().assertIsEnabled()
        rule.onNodeWithTag("route-map").assertIsDisplayed()
        rule.onNodeWithTag("trip-legend").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Linha do tempo").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("18:46  Walking → Eco").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Tempo em cada modo").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("8 min · 38 %").performScrollTo().assertIsDisplayed()
        assertTrue(n.isEmpty())
    }

    @Test fun `trocar para Motor e Nenhum`() {
        show(trip(events))
        rule.onNodeWithTag("trip-view-Motor").performClick()
        rule.onNodeWithTag("trip-view-Motor").assertIsSelected()
        rule.onNodeWithText("Tempo com cada motor").performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag("trip-view-Nenhum").performClick()
        rule.onNodeWithTag("route-map").assertIsDisplayed()
        rule.onNodeWithTag("trip-legend").assertDoesNotExist()
    }

    @Test fun `modo unico mostra texto de sem trocas`() {
        show(trip(listOf(TripModeEvent(0, 1, true))))
        rule.onNodeWithText("Sem trocas: Eco durante toda a viagem.").performScrollTo().assertIsDisplayed()
    }

    @Test fun `sem trajeto mantem linha do tempo`() {
        show(trip(events, emptyList()))
        rule.onNodeWithTag("route-empty").assertIsDisplayed()
        rule.onNodeWithText("Linha do tempo").performScrollTo().assertIsDisplayed()
    }

    @Test fun `sem dado de modo - sem cartao, seletor desabilitado em Nenhum, trajeto sem cores e aviso uma vez`() {
        val n = mutableListOf<String>()
        show(trip(emptyList()), notices = n)
        rule.waitForIdle()
        assertEquals(listOf(NO_MODE_NOTICE), n)
        rule.onNodeWithTag("trip-view-Nenhum").assertIsSelected()
        rule.onNodeWithTag("trip-view-Modo").assertIsNotEnabled()
        rule.onNodeWithTag("trip-view-Motor").assertIsNotEnabled()
        rule.onNodeWithText("Modo não registrado").assertDoesNotExist()
        rule.onNodeWithTag("route-map").assertIsDisplayed()
        rule.onNodeWithTag("trip-legend").assertDoesNotExist()
    }

    @Test fun `fonte 2,0 mantem seletor, linha do tempo e tempos alcancaveis`() {
        show(trip(events), scale = 2f)
        rule.onNodeWithTag("trip-view-Modo").assertIsDisplayed()
        rule.onNodeWithTag("trip-timeline").performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag("trip-times").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Trajetos e viagens ficam salvos só no seu celular.").performScrollTo().assertIsDisplayed()
    }

    @Test fun `acessibilidade - mapa e linha do tempo tem descricao textual`() {
        show(trip(events))
        val map = rule.onNodeWithTag("route-map").fetchSemanticsNode()
        assertTrue(map.config.toString().contains("Trajeto colorido por modo"))
        val tl = rule.onNodeWithTag("trip-timeline").performScrollTo().fetchSemanticsNode()
        assertTrue(tl.config.toString().contains("Linha do tempo de 18:42 a 19:03"))
        assertNotNull(tl)
    }
}


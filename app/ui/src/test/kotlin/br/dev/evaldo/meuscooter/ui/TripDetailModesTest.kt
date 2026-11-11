package br.dev.evaldo.meuscooter.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.graphics.asAndroidBitmap
import kotlin.math.abs
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
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
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
        assertEquals(listOf("04:00", "10:00", "15:00", "18:00"), v.changes.map { it.time })
        assertEquals(listOf("Walking → Eco", "Eco → Sport", "Sport → Race", "Race → Sport"), v.changes.map { it.text })
        assertEquals("00:00", v.startTime); assertEquals("21:00", v.endTime)
        assertEquals(4, v.markers.size); assertTrue(v.markers.all { it.at != null })
        assertNull(v.singleText)
    }

    @Test fun `mapper por motor - 2 motores primary solido e 1 motor pending tracejado`() {
        val v = requireNotNull(mapper.detail(trip(events), now).modes.motor)
        assertEquals(listOf(SegTone.Primary, SegTone.Pending, SegTone.Primary), v.segments.map { it.tone })
        assertEquals(listOf(LinePattern.Solid, LinePattern.Dashed, LinePattern.Solid), v.segments.map { it.pattern })
        assertEquals(listOf("2 motores", "1 motor"), v.timeRows.map { it.label })
        assertEquals(listOf("16 min · 76 %", "5 min · 24 %"), v.timeRows.map { it.value })
        assertEquals(listOf("08:00 2 motores → 1 motor", "13:00 1 motor → 2 motores"), v.changes.map { "${it.time} ${it.text}" })
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
        assertTrue(v.timelineDescription.contains("Walking a partir de 00:00 (4 min)"))
        assertTrue(v.mapDescription.contains("Walking → Eco aos 04:00"))
    }

    @Test fun `horarios das trocas sao tempo ativo e nao dependem do relogio de inicio`() {
        // Pausa não conta: mesma viagem iniciada em outra hora mostra os mesmos tempos relativos.
        val other = trip(events).copy(startedAtMs = start + 5 * 3_600_000L)
        assertEquals(mapper.detail(trip(events), now).modes.mode!!.changes, mapper.detail(other, now).modes.mode!!.changes)
        assertEquals("65:30", mapper.detail(trip(listOf(TripModeEvent(0, 1, true), TripModeEvent(65 * min + 30_000, 2, true))), now).modes.mode!!.changes[0].time)
    }

    @Test fun `resto dos percentuais segue a ordem de aparicao como o dominio`() {
        // Eco -> Walking -> Sport, 7 min cada: o 1 ponto extra vai para Eco (1o a aparecer), não para Walking.
        val ev = listOf(TripModeEvent(0, 1, true), TripModeEvent(7 * min, 0, true), TripModeEvent(14 * min, 2, true))
        val rows = mapper.detail(trip(ev), now).modes.mode!!.timeRows.associate { it.label to it.value.substringAfter("· ").substringBefore(" %").toInt() }
        assertEquals(mapOf("Walking" to 33, "Eco" to 34, "Race" to 33), rows)
        val dom = br.dev.evaldo.meuscooter.domain.analyzeTripModes(route, ev, 21 * min).modeTimes.associate { br.dev.evaldo.meuscooter.domain.DriveModes.labelOf(it.mode) to it.percent }
        assertEquals(dom, rows)
    }

    @Test fun `percentuais somam exatamente 100 por maior resto em modo e motor`() {
        // 3 fatias iguais de 7 min: 33+33+33 = 99 com arredondamento simples; maior resto dá 34+33+33.
        val ev = listOf(TripModeEvent(0, 0, true), TripModeEvent(7 * min, 1, false), TripModeEvent(14 * min, 3, true))
        val m = mapper.detail(trip(ev), now).modes
        val modePcts = m.mode!!.timeRows.map { it.value.substringAfter("· ").substringBefore(" %").toInt() }
        assertEquals(100, modePcts.sum())
        assertEquals(listOf(34, 33, 33), modePcts)
        assertEquals(100, m.motor!!.timeRows.sumOf { it.value.substringAfter("· ").substringBefore(" %").toInt() })
        // Igual ao percentual do domínio.
        val dom = br.dev.evaldo.meuscooter.domain.analyzeTripModes(route, ev, 21 * min).modeTimes.sortedBy { it.mode }.map { it.percent }
        assertEquals(dom, modePcts)
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
        rule.onNodeWithText("04:00  Walking → Eco").performScrollTo().assertIsDisplayed()
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
        rule.onNodeWithText("Sem trajeto gravado").assertIsDisplayed()
        rule.onNodeWithText("GPS sem leitura").assertIsDisplayed()
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

    private fun layoutOf(tag: String, idx: Int = 0): androidx.compose.ui.text.TextLayoutResult {
        val n = rule.onAllNodesWithTag(tag).fetchSemanticsNodes()[idx]
        val out = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        n.config[androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult].action?.invoke(out)
        return out.first()
    }

    @Test fun `fonte 2,0 - legenda e estatisticas em uma coluna, nome e duracao em linhas separadas, sem corte`() {
        show(trip(events), scale = 2f)
        rule.onNodeWithTag("trip-view-Modo").assertIsDisplayed()
        // legenda: uma coluna (itens com mesmo x e y crescente)
        val legend = rule.onNodeWithTag("trip-legend").performScrollTo()
        listOf("Walking", "Eco", "Sport", "Race").map { rule.onNode(androidx.compose.ui.test.hasText(it) and androidx.compose.ui.test.hasAnyAncestor(androidx.compose.ui.test.hasTestTag("trip-legend"))).fetchSemanticsNode().boundsInRoot.let { r -> r.left to r.top } }
            .let { ps -> assertEquals(1, ps.map { it.first }.distinct().size); assertEquals(ps.sortedBy { it.second }, ps) }
        // sem quebra no meio da palavra: cada rótulo da legenda ocupa uma linha
        listOf("Walking", "Eco", "Sport", "Race").forEach { w ->
            val out = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            rule.onNode(androidx.compose.ui.test.hasText(w) and androidx.compose.ui.test.hasAnyAncestor(androidx.compose.ui.test.hasTestTag("trip-legend"))).fetchSemanticsNode()
                .config[androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult].action?.invoke(out)
            assertEquals("legenda '$w' quebrou", 1, out.first().lineCount)
        }
        // tempo por modo: nome e valor em linhas separadas e valor sem encolher nem cortar
        rule.onNodeWithTag("trip-times").performScrollTo()
        val name = rule.onAllNodesWithText("Walking")[1].fetchSemanticsNode().boundsInRoot
        val value = rule.onAllNodesWithTag("trip-time-value")[0].fetchSemanticsNode().boundsInRoot
        assertTrue("valor deve ficar abaixo do nome", value.top >= name.bottom - 1f)
        val lay = layoutOf("trip-time-value")
        assertTrue("linhas=${lay.lineCount} tam=${lay.size}", lay.lineCount == 1 && !lay.isLineEllipsized(0) && lay.size.width <= lay.layoutInput.constraints.maxWidth && lay.getLineEnd(0) == lay.layoutInput.text.length)
        assertEquals(androidx.compose.material3.Typography().bodyMedium.fontSize.value * 2f, lay.layoutInput.style.fontSize.value * 2f, 0.5f)
        // estatísticas: uma coluna
        rule.onNodeWithTag("trip-stats").performScrollTo()
        val dist = rule.onNodeWithText("Distância").fetchSemanticsNode().boundsInRoot
        val dur = rule.onNodeWithText("Duração").fetchSemanticsNode().boundsInRoot
        assertEquals(dist.left, dur.left, 1f); assertTrue(dur.top > dist.bottom)
        rule.onNodeWithTag("trip-timeline").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Trajetos e viagens ficam salvos só no seu celular.").performScrollTo().assertIsDisplayed()
    }

    @Test fun `sem dado - trajeto neutro, sem verde de modo no mapa`() {
        show(trip(emptyList()))
        rule.waitForIdle()
        val map = rule.onNodeWithTag("route-map")
        assertTrue(map.fetchSemanticsNode().config.toString().contains("Trajeto sem cores"))
        val img = map.captureToImage().asAndroidBitmap()
        val success = 0xFF0E9F63.toInt()
        var green = 0
        for (y in 0 until img.height) for (x in 0 until img.width) {
            val c = img.getPixel(x, y)
            if (abs((c shr 16 and 255) - (success shr 16 and 255)) < 8 && abs((c shr 8 and 255) - (success shr 8 and 255)) < 8 && abs((c and 255) - (success and 255)) < 8) green++
        }
        assertEquals("marcador de início não pode usar a cor de modo Walking", 0, green)
    }

    @Test fun `acessibilidade - mapa e linha do tempo tem descricao textual`() {
        show(trip(events))
        val map = rule.onNodeWithTag("route-map").fetchSemanticsNode()
        assertTrue(map.config.toString().contains("Trajeto colorido por modo"))
        val tl = rule.onNodeWithTag("trip-timeline").performScrollTo().fetchSemanticsNode()
        assertTrue(tl.config.toString().contains("Linha do tempo, tempo de viagem de 00:00 a 21:00"))
        assertNotNull(tl)
    }
}


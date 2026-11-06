package br.dev.evaldo.meuscooter.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import br.dev.evaldo.meuscooter.domain.AppEvent
import br.dev.evaldo.meuscooter.domain.AppEventType
import br.dev.evaldo.meuscooter.domain.EventFilter
import br.dev.evaldo.meuscooter.domain.EventSeverity
import br.dev.evaldo.meuscooter.ui.eventlog.EventLogScreen
import br.dev.evaldo.meuscooter.ui.theme.MeuPatineteTheme
import java.time.ZoneOffset
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class EventLogUiTest {
    @get:Rule val rule = createComposeRule()

    private val zone = ZoneOffset.ofHours(-3)
    private fun at(d: Int, h: Int, m: Int, s: Int) = ZonedDateTime.of(2026, 11, d, h, m, s, 0, zone).toInstant().toEpochMilli()
    private val now = at(6, 16, 0, 0)
    private fun e(id: Long, atMs: Long, t: AppEventType, detail: String, title: String = t.title) = AppEvent(id, atMs, t, title, detail)

    private val events = listOf(
        e(1, at(5, 23, 50, 0), AppEventType.Unlock, "PIN digitado"),
        e(2, at(6, 14, 35, 19), AppEventType.WrongPin, "Recusado pelo patinete (1 de 5)"),
        e(3, at(6, 15, 37, 53), AppEventType.Unlock, "PIN salvo (biometria) · confirmado em 0,8 s"),
        e(4, at(6, 15, 38, 0), AppEventType.Disconnected, "Pedido do usuário"),
        e(5, at(6, 15, 38, 28), AppEventType.Lock, "Bloqueio temporário (modo 2) · confirmado em 0,5 s"),
        e(6, at(6, 15, 38, 40), AppEventType.LinkDropped, "Queda do enlace"),
    )

    private var cleared = 0
    private var backs = 0

    private fun show(list: List<AppEvent>) = rule.setContent {
        MeuPatineteTheme(darkTheme = true) { EventLogScreen(list, zone, now, onBack = { backs++ }, onClear = { cleared++ }) }
    }

    @Test fun `lista agrupada por dia em ordem decrescente com horas HH_mm_ss`() {
        show(events)
        rule.onNodeWithText("Registro de eventos").assertIsDisplayed()
        rule.onNodeWithText("6 NOV 2026").assertIsDisplayed()
        rule.onNodeWithText("15:38:40").assertIsDisplayed()
        rule.onNodeWithText("PIN errado").assertIsDisplayed()
        rule.onNodeWithText("Recusado pelo patinete (1 de 5)").assertIsDisplayed()
        rule.onNodeWithText("Mantém os últimos 500 eventos · nunca guarda o PIN").assertExists()
    }

    @Test fun `cartao do dia resume desbloqueios bloqueios e quedas e o ultimo horario`() {
        show(events)
        rule.onNodeWithText("1 desbloqueio · 1 bloqueio · 1 queda").assertIsDisplayed()
        rule.onNodeWithText("Último: 15:38:40").assertIsDisplayed()
    }

    @Test fun `linha tem descricao de acessibilidade`() {
        show(events)
        rule.onNodeWithContentDescription("Bloqueado, Bloqueio temporário (modo 2) · confirmado em 0,5 s, às 15:38:28").assertIsDisplayed()
    }

    @Test fun `filtro Erros mostra so PIN errado`() {
        show(events)
        rule.onNodeWithTag("eventlog-filter-Errors").performClick()
        rule.onNodeWithText("PIN errado").assertIsDisplayed()
        rule.onNodeWithText("Bloqueado").assertDoesNotExist()
        rule.onNodeWithText("Queda de conexão").assertDoesNotExist()
    }

    @Test fun `filtro Conexao mostra Desconectado e Queda mas nao bloqueio`() {
        show(events)
        rule.onNodeWithTag("eventlog-filter-Connection").performClick()
        rule.onNodeWithText("Desconectado").assertIsDisplayed()
        rule.onNodeWithText("Queda de conexão").assertIsDisplayed()
        rule.onNodeWithText("Bloqueado").assertDoesNotExist()
    }

    @Test fun `filtro sem resultado mostra outro texto e mantem o resumo do dia`() {
        show(events.filter { it.type == AppEventType.Lock })
        rule.onNodeWithTag("eventlog-filter-Errors").performClick()
        rule.onNodeWithText("Nenhum evento neste filtro").assertIsDisplayed()
        rule.onNodeWithText("Nenhum evento ainda").assertDoesNotExist()
        rule.onNodeWithText("1 bloqueio").assertIsDisplayed()
        rule.onNodeWithTag("eventlog-filter-All").performClick()
        rule.onNodeWithText("Bloqueado").assertIsDisplayed()
    }

    @Test fun `sem eventos mostra estado vazio`() {
        show(emptyList())
        rule.onNodeWithText("Nenhum evento ainda").assertIsDisplayed()
    }

    @Test fun `voltar chama onBack`() {
        show(events)
        rule.onNodeWithContentDescription("Voltar").performClick()
        assertEquals(1, backs)
    }

    @Test fun `limpar registro pede confirmacao e Nao nao limpa`() {
        show(events)
        rule.onNodeWithTag("eventlog-menu").performClick()
        rule.onNodeWithText("Limpar registro").performClick()
        rule.onNodeWithTag("eventlog-clear-dialog").assertIsDisplayed()
        assertEquals(0, cleared)
        rule.onNodeWithTag("eventlog-clear-no").performClick()
        rule.onNodeWithTag("eventlog-clear-dialog").assertDoesNotExist()
        assertEquals(0, cleared)
    }

    @Test fun `limpar registro com Sim chama onClear uma vez`() {
        show(events)
        rule.onNodeWithTag("eventlog-menu").performClick()
        rule.onNodeWithText("Limpar registro").performClick()
        rule.onNodeWithTag("eventlog-clear-yes").performClick()
        assertEquals(1, cleared)
        rule.onNodeWithTag("eventlog-clear-dialog").assertDoesNotExist()
    }
}

class EventLogUiMapperTest {
    private val zone = ZoneOffset.ofHours(-3)
    private fun at(d: Int, h: Int, m: Int, s: Int) = ZonedDateTime.of(2026, 11, d, h, m, s, 0, zone).toInstant().toEpochMilli()
    private val mapper = EventLogUiMapper(zone)
    private fun e(id: Long, atMs: Long, t: AppEventType) = AppEvent(id, atMs, t, t.title, "d$id")

    @Test fun `grupos por dia, decrescente, e cabecalho com mes em portugues`() {
        val s = mapper.map(listOf(e(1, at(5, 10, 0, 0), AppEventType.Lock), e(2, at(6, 9, 0, 0), AppEventType.Lock), e(3, at(6, 9, 0, 1), AppEventType.Unlock)), EventFilter.All, at(6, 12, 0, 0))
        assertEquals(listOf("6 NOV 2026", "5 NOV 2026"), s.days.map { it.header })
        assertEquals(listOf(3L, 2L), s.days[0].rows.map { it.id })
        assertEquals(listOf("09:00:01", "09:00:00"), s.days[0].rows.map { it.time })
    }

    @Test fun `resumo ignora o filtro e sem eventos do dia diz nenhum`() {
        val ev = listOf(e(1, at(6, 9, 0, 0), AppEventType.Unlock), e(2, at(6, 9, 5, 0), AppEventType.Unlock), e(3, at(6, 9, 6, 0), AppEventType.Error))
        val s = mapper.map(ev, EventFilter.Errors, at(6, 12, 0, 0))
        assertEquals("2 desbloqueios", s.summary)
        assertEquals("09:06:00", s.last)
        assertEquals(1, s.days.single().rows.size)
        assertEquals("Nenhum desbloqueio, bloqueio ou queda hoje", mapper.map(ev, EventFilter.All, at(7, 12, 0, 0)).summary)
        assertNull(mapper.map(ev, EventFilter.All, at(7, 12, 0, 0)).last)
    }

    @Test fun `vazio so sem nenhum evento e filtro sem resultado e outro estado`() {
        val ev = listOf(e(1, at(6, 9, 0, 0), AppEventType.Lock))
        val filtered = mapper.map(ev, EventFilter.Errors, at(6, 12, 0, 0))
        assertEquals(false, filtered.empty); assertEquals(true, filtered.filteredEmpty); assertEquals("1 bloqueio", filtered.summary)
        val none = mapper.map(emptyList(), EventFilter.All, at(6, 12, 0, 0))
        assertEquals(true, none.empty); assertEquals(false, none.filteredEmpty)
    }

    @Test fun `severidade e glifo vem do tipo`() {
        val r = mapper.map(listOf(e(1, at(6, 9, 0, 0), AppEventType.WrongPin)), EventFilter.All, at(6, 12, 0, 0)).days.single().rows.single()
        assertEquals(EventSeverity.Error, r.severity)
        assertEquals("×", r.glyph)
    }
}

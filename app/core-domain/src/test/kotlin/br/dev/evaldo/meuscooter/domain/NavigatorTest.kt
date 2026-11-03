package br.dev.evaldo.meuscooter.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** S1: navegação como pilha imutável. Esperado vem de design/ANDROID-MAPA.md (menu ⋮: Telemetria · Preferências · Histórico). */
class NavigatorTest {
    @Test fun `inicia na Home sem poder voltar`() {
        val nav = Navigator()
        assertEquals(AppDestination.Home, nav.current)
        assertFalse(nav.canGoBack)
    }

    @Test fun `abrir empilha e voltar retorna a Home`() {
        val opened = Navigator().open(AppDestination.Telemetry)
        assertEquals(AppDestination.Telemetry, opened.current)
        assertTrue(opened.canGoBack)
        assertEquals(AppDestination.Home, opened.back().current)
    }

    @Test fun `voltar na Home devolve a mesma pilha`() {
        val nav = Navigator()
        assertEquals(nav, nav.back())
    }

    @Test fun `abrir o destino atual nao duplica a pilha`() {
        val nav = Navigator().open(AppDestination.History).open(AppDestination.History)
        assertEquals(listOf(AppDestination.Home, AppDestination.History), nav.stack)
    }

    @Test fun `pilha de dois niveis volta um por vez`() {
        val nav = Navigator().open(AppDestination.Telemetry).open(AppDestination.Settings)
        assertEquals(AppDestination.Telemetry, nav.back().current)
        assertEquals(AppDestination.Home, nav.back().back().current)
    }

    @Test fun `abrir Home limpa a pilha`() {
        val nav = Navigator().open(AppDestination.Telemetry).open(AppDestination.Home)
        assertEquals(listOf(AppDestination.Home), nav.stack)
    }

    @Test fun `restaura a pilha a partir de nomes salvos`() {
        val nav = Navigator().open(AppDestination.Settings)
        assertEquals(nav, Navigator.fromNames(nav.names))
    }

    @Test fun `nomes invalidos ou vazios restauram a Home`() {
        assertEquals(Navigator(), Navigator.fromNames(emptyList()))
        assertEquals(Navigator(), Navigator.fromNames(listOf("Nada")))
    }

    @Test fun `menu da Home lista Telemetria Preferencias e Historico nesta ordem`() {
        assertEquals(
            listOf(AppDestination.Telemetry, AppDestination.Settings, AppDestination.History),
            AppDestination.menu,
        )
    }
}

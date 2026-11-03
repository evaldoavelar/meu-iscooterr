package br.dev.evaldo.meuscooter.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** S1: navegação como pilha simples. Valores esperados vêm de design/ANDROID-MAPA.md (menu ⋮: Telemetria · Preferências · Histórico). */
class NavigatorTest {
    @Test fun `inicia na Home sem poder voltar`() {
        val nav = Navigator()
        assertEquals(AppDestination.Home, nav.current)
        assertFalse(nav.canGoBack)
    }

    @Test fun `abrir empilha e voltar retorna a Home`() {
        val nav = Navigator()
        nav.open(AppDestination.Telemetry)
        assertEquals(AppDestination.Telemetry, nav.current)
        assertTrue(nav.canGoBack)
        assertTrue(nav.back())
        assertEquals(AppDestination.Home, nav.current)
    }

    @Test fun `voltar na Home nao faz nada e retorna false`() {
        val nav = Navigator()
        assertFalse(nav.back())
        assertEquals(AppDestination.Home, nav.current)
    }

    @Test fun `abrir o destino atual nao duplica a pilha`() {
        val nav = Navigator()
        nav.open(AppDestination.History)
        nav.open(AppDestination.History)
        nav.back()
        assertEquals(AppDestination.Home, nav.current)
    }

    @Test fun `menu da Home lista Telemetria Preferencias e Historico nesta ordem`() {
        assertEquals(
            listOf(AppDestination.Telemetry, AppDestination.Settings, AppDestination.History),
            AppDestination.menu,
        )
    }
}

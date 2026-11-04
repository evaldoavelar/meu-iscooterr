package br.dev.evaldo.meuscooter.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** S1: etapas do primeiro uso (design/screens.py `primeiro_uso`: procurando → encontrado → conectado). */
class OnboardingFlowTest {
    @Test fun `comeca procurando`() {
        assertEquals(OnboardingStep.Searching, OnboardingFlow().step)
    }

    @Test fun `encontrar o patinete leva a Encontrado e confirmar ainda nao conecta`() {
        val f = OnboardingFlow().scooterFound().confirm()
        assertEquals(OnboardingStep.Found, f.step)
        assertTrue(f.confirmed)
    }

    @Test fun `so conecta depois da confirmacao do usuario`() {
        val found = OnboardingFlow().scooterFound()
        assertEquals(OnboardingStep.Found, found.connectionEstablished().step)
        assertEquals(OnboardingStep.Connected, found.confirm().connectionEstablished().step)
    }

    @Test fun `confirmar sem ter encontrado e ignorado`() {
        val f = OnboardingFlow()
        assertEquals(f, f.confirm())
        assertFalse(f.confirm().confirmed)
    }

    @Test fun `comecar a usar so apos conectado`() {
        val connected = OnboardingFlow().scooterFound().confirm().connectionEstablished()
        assertEquals(OnboardingStep.Ready, connected.finish().step)
        assertEquals(OnboardingStep.Searching, OnboardingFlow().finish().step)
    }

    @Test fun `cancelar encerra enquanto procura ou antes de confirmar`() {
        assertEquals(OnboardingStep.Cancelled, OnboardingFlow().cancel().step)
        assertEquals(OnboardingStep.Cancelled, OnboardingFlow().scooterFound().cancel().step)
    }

    @Test fun `nao cancela depois de confirmar nem depois de conectado`() {
        val confirmed = OnboardingFlow().scooterFound().confirm()
        assertEquals(OnboardingStep.Found, confirmed.cancel().step)
        assertEquals(OnboardingStep.Connected, confirmed.connectionEstablished().cancel().step)
    }

    @Test fun `eventos fora de ordem sao ignorados`() {
        val f = OnboardingFlow()
        assertEquals(f, f.connectionEstablished())
        assertEquals(f.scooterFound(), f.scooterFound().scooterFound())
    }

    /** Codex r1 #1: conexão que não completa volta a "Encontrado" (cancelável e com nova tentativa), marcando a falha. */
    @Test fun `falha de conexao volta a Encontrado com aviso e permite tentar de novo`() {
        val failed = OnboardingFlow().scooterFound().confirm().connectionFailed()
        assertEquals(OnboardingStep.Found, failed.step)
        assertFalse(failed.confirmed)
        assertTrue(failed.failed)
        val retry = failed.confirm()
        assertTrue(retry.confirmed)
        assertFalse(retry.failed)
        assertEquals(OnboardingStep.Cancelled, failed.cancel().step)
    }

    @Test fun `falha de conexao fora de confirmado e ignorada`() {
        val found = OnboardingFlow().scooterFound()
        assertEquals(found, found.connectionFailed())
        val connected = found.confirm().connectionEstablished()
        assertEquals(connected, connected.connectionFailed())
    }
}

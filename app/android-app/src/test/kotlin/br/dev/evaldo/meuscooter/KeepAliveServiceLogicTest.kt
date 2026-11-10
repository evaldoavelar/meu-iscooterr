package br.dev.evaldo.meuscooter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Testes JVM da lógica pura do serviço de primeiro plano (ADR 0007, S41 BAIXOs 2 e 3).
 * Testa o comportamento sem Context/NotificationManager/Robolectric.
 */
class KeepAliveServiceLogicTest {

    @Test
    fun `quando startForeground tem sucesso em inicio normal mantem servico em execucao`() {
        val decision = KeepAliveServiceLogic.evaluateStart(action = null, promoted = true)
        assertFalse("Não deve pedir desconexão do patinete", decision.shouldDisconnect)
        assertFalse("Não deve parar o serviço", decision.shouldStopSelf)
        assertFalse("Não deve registrar aviso", decision.logWarning)
    }

    @Test
    fun `quando startForeground falha em inicio normal para com seguranca sem desconectar e registra aviso`() {
        val decision = KeepAliveServiceLogic.evaluateStart(action = null, promoted = false)
        assertFalse("Não deve pedir desconexão do patinete em falha de inicialização", decision.shouldDisconnect)
        assertTrue("Deve parar a si mesmo com segurança para não ficar sem notificação", decision.shouldStopSelf)
        assertTrue("Deve registrar o erro ocorrido", decision.logWarning)
    }

    @Test
    fun `quando acao Desconectar solicitada desconecta e encerra o servico mesmo com promocao`() {
        val decision = KeepAliveServiceLogic.evaluateStart(
            action = KeepAliveServiceLogic.ACTION_DISCONNECT,
            promoted = true
        )
        assertTrue("Deve pedir desconexão ao ViewModel", decision.shouldDisconnect)
        assertTrue("Deve parar o serviço", decision.shouldStopSelf)
        assertFalse("Desconexão do usuário não é aviso de erro", decision.logWarning)
    }

    @Test
    fun `quando acao Desconectar solicitada com promocao falha ainda assim desconecta e encerra`() {
        val decision = KeepAliveServiceLogic.evaluateStart(
            action = KeepAliveServiceLogic.ACTION_DISCONNECT,
            promoted = false
        )
        assertTrue("Deve pedir desconexão mesmo se startForeground falhou", decision.shouldDisconnect)
        assertTrue("Deve parar o serviço", decision.shouldStopSelf)
    }

    @Test
    fun `quando tarefa e removida dos recentes solicita desconexao e encerra o servico`() {
        val decision = KeepAliveServiceLogic.evaluateTaskRemoved()
        assertTrue("onTaskRemoved deve pedir desconexão ao ViewModel", decision.shouldDisconnect)
        assertTrue("onTaskRemoved deve encerrar o serviço", decision.shouldStopSelf)
    }

    @Test
    fun `constantes da acao Desconectar estao definidas corretamente`() {
        assertEquals("br.dev.evaldo.meuscooter.action.DISCONNECT", KeepAliveServiceLogic.ACTION_DISCONNECT)
        assertEquals("Desconectar", KeepAliveServiceLogic.ACTION_DISCONNECT_TITLE)
        assertEquals("scooter_connection", KeepAliveServiceLogic.CHANNEL_ID)
        assertEquals(7, KeepAliveServiceLogic.NOTIFICATION_ID)
    }
}

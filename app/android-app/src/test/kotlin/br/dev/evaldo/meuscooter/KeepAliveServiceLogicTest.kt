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

    private val CD = KeepAliveServiceLogic.TYPE_CONNECTED_DEVICE
    private val LOC = KeepAliveServiceLogic.TYPE_LOCATION

    @Test
    fun `tipo de primeiro plano por viagem permissao e SDK`() {
        val f = KeepAliveServiceLogic::foregroundType
        assertEquals(CD or LOC, f(29, true, true))
        assertEquals(CD or LOC, f(34, true, true))
        assertEquals(CD, f(34, true, false))
        assertEquals(CD, f(34, false, true))
        assertEquals(CD, f(30, false, false))
        assertEquals(KeepAliveServiceLogic.TYPE_NONE, f(28, true, true))
    }

    @Test
    fun `promocao passa o tipo certo e marca location ativa so com viagem e permissao`() {
        val calls = mutableListOf<Pair<Int, Boolean>>()
        val r = KeepAliveServiceLogic.promote(34, true, true) { t, l -> calls += t to l }
        assertEquals(listOf((CD or LOC) to true), calls)
        assertTrue(r.promoted); assertTrue(r.locationActive); assertFalse(r.locationFellBack)
        calls.clear()
        val r2 = KeepAliveServiceLogic.promote(34, false, true) { t, l -> calls += t to l }
        assertEquals(listOf(CD to false), calls)
        assertFalse(r2.locationActive)
    }

    @Test
    fun `SecurityException com location cai para connectedDevice`() {
        val calls = mutableListOf<Int>()
        val r = KeepAliveServiceLogic.promote(34, true, true) { t, _ ->
            calls += t
            if ((t and LOC) != 0) throw SecurityException("sem permissao")
        }
        assertEquals(listOf(CD or LOC, CD), calls)
        assertTrue(r.promoted); assertTrue(r.locationFellBack); assertFalse(r.locationActive)
        assertEquals(CD, r.type)
    }

    @Test
    fun `SecurityException sem location nao tenta de novo e outras falhas nao promovem`() {
        var n = 0
        val r = KeepAliveServiceLogic.promote(34, false, false) { _, _ -> n++; throw SecurityException("x") }
        assertEquals(1, n); assertFalse(r.promoted)
        n = 0
        val r2 = KeepAliveServiceLogic.promote(34, true, true) { _, _ -> n++; throw IllegalStateException("x") }
        assertEquals(1, n); assertFalse(r2.promoted)
        n = 0
        val r3 = KeepAliveServiceLogic.promote(34, true, true) { _, _ -> n++; throw SecurityException("x") }
        assertEquals(2, n); assertFalse(r3.promoted)
    }

    @Test
    fun `re-promocao ao mudar a viagem repete startForeground com o novo tipo`() {
        val calls = mutableListOf<Int>()
        for (recording in listOf(false, true, false)) KeepAliveServiceLogic.promote(34, recording, true) { t, _ -> calls += t }
        assertEquals(listOf(CD, CD or LOC, CD), calls)
    }
}

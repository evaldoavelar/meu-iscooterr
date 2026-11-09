package br.dev.evaldo.meuscooter.domain

import br.dev.evaldo.meuscooter.protocol.ConnectionStatus
import org.junit.Assert.assertEquals
import org.junit.Test

/** ADR 0007 §2: quando o serviço em primeiro plano deve estar ativo (valores esperados tirados do ADR). */
class KeepAlivePolicyTest {
    private fun next(active: Boolean, c: ConnectionStatus, pending: Boolean = false) = KeepAlivePolicy.next(active, c, pending)

    @Test fun `comeca so com a conexao estabelecida`() {
        assertEquals(true, next(false, ConnectionStatus.Connected))
        assertEquals(false, next(false, ConnectionStatus.Connecting))
        assertEquals(false, next(false, ConnectionStatus.Disconnected, pending = true))
        assertEquals(false, next(false, ConnectionStatus.Connecting, pending = true))
    }

    @Test fun `continua ativo durante reconexao automatica pendente`() {
        assertEquals(true, next(true, ConnectionStatus.Disconnected, pending = true))
        assertEquals(true, next(true, ConnectionStatus.Connecting, pending = true))
    }

    @Test fun `conectando mantem o estado anterior`() {
        assertEquals(true, next(true, ConnectionStatus.Connecting))
    }

    @Test fun `desconectado sem reconexao pendente para`() {
        assertEquals(false, next(true, ConnectionStatus.Disconnected))
        assertEquals(false, next(false, ConnectionStatus.Disconnected))
    }

    @Test fun `None nao faz nada`() {
        SessionKeepAlive.None.start { error("nunca") }
        SessionKeepAlive.None.stop()
    }
}

package br.dev.evaldo.meuscooter.domain

import br.dev.evaldo.meuscooter.protocol.Clock
import br.dev.evaldo.meuscooter.protocol.ConnectionStatus
import br.dev.evaldo.meuscooter.protocol.LockRefusal
import br.dev.evaldo.meuscooter.protocol.LockResult
import br.dev.evaldo.meuscooter.protocol.UnconfirmedCause
import br.dev.evaldo.meuscooter.protocol.UnlockRefusal
import br.dev.evaldo.meuscooter.protocol.UnlockResult
import org.junit.Assert.assertEquals
import org.junit.Test

class EventRecorderTest {
    private var mono = 0L
    private val log = FakeEventLogStore(wallClock = Clock { 0L })
    private fun recorder(name: String? = "iX7 Pro") = EventRecorder(log, Clock { mono }, name)
    private fun lines() = log.events.value.map { "${it.type}|${it.title}|${it.detail}" }

    @Test fun `desbloqueio com PIN digitado confirma com o tempo`() {
        val r = recorder()
        r.unlock(UnlockStatus.Waiting(1_000), viaSavedPin = false)
        r.unlock(UnlockStatus.Finished(UnlockResult.Unlocked, 1_800), viaSavedPin = false)
        assertEquals(listOf("Unlock|Desbloqueado|PIN digitado · confirmado em 0,8 s"), lines())
    }

    @Test fun `desbloqueio por biometria diz PIN salvo e arredonda o tempo`() {
        val r = recorder()
        r.unlock(UnlockStatus.Waiting(0), viaSavedPin = true)
        r.unlock(UnlockStatus.Finished(UnlockResult.Unlocked, 1_249), viaSavedPin = true)
        assertEquals(listOf("Unlock|Desbloqueado|PIN salvo (biometria) · confirmado em 1,2 s"), lines())
    }

    @Test fun `sem Waiting visto nao inventa tempo`() {
        recorder().unlock(UnlockStatus.Finished(UnlockResult.Unlocked, 5), viaSavedPin = false)
        assertEquals(listOf("Unlock|Desbloqueado|PIN digitado"), lines())
    }

    @Test fun `mesmo resultado repetido nao duplica o evento`() {
        val r = recorder()
        val done = UnlockStatus.Finished(UnlockResult.Unlocked, 900)
        r.unlock(done, false); r.unlock(done, false); r.unlock(null, false); r.unlock(done, false)
        assertEquals(1, log.events.value.size)
    }

    @Test fun `PIN errado mostra n de 5 e nao inicia pausa`() {
        recorder().unlock(UnlockStatus.Finished(UnlockResult.PinRejected, 10), viaSavedPin = false, rejects = 2)
        assertEquals(listOf("WrongPin|PIN errado|Recusado pelo patinete (2 de 5)"), lines())
    }

    @Test fun `quinto PIN errado registra 5 de 5 e a pausa`() {
        recorder().unlock(UnlockStatus.Finished(UnlockResult.PinRejected, 10), viaSavedPin = false, rejects = 0, cooldownStarted = true)
        assertEquals(
            listOf("WrongPin|PIN errado|Recusado pelo patinete (5 de 5)", "Refused|Desbloqueio pausado|Aguarde 30 s para tentar de novo · nada foi enviado"),
            lines(),
        )
    }

    @Test fun `recusa de desbloqueio diz o motivo e que nada foi enviado`() {
        recorder().unlock(UnlockStatus.Finished(UnlockResult.Refused(UnlockRefusal.TelemetryStale), 10), false)
        assertEquals(listOf("Refused|Desbloqueio recusado|Aguarde a telemetria · nada foi enviado"), lines())
    }

    @Test fun `desbloqueio sem confirmacao e erro`() {
        recorder().unlock(UnlockStatus.Finished(UnlockResult.Unconfirmed(UnconfirmedCause.Timeout), 10), false)
        assertEquals(listOf("Error|Desbloqueio sem confirmação|Estado desconhecido · sem resposta no prazo"), lines())
    }

    @Test fun `bloqueio modo 2 confirmado`() {
        val r = recorder()
        r.lock(LockStatus.Waiting(100)); r.lock(LockStatus.Finished(LockResult.Locked, 600))
        assertEquals(listOf("Lock|Bloqueado|Bloqueio temporário (modo 2) · confirmado em 0,5 s"), lines())
    }

    @Test fun `bloqueio recusado rejeitado e sem confirmacao`() {
        val r = recorder()
        r.lock(LockStatus.Finished(LockResult.Refused(LockRefusal.Moving), 1))
        r.lock(LockStatus.Finished(LockResult.Rejected, 2))
        r.lock(LockStatus.Finished(LockResult.Unconfirmed(UnconfirmedCause.LinkLost), 3))
        assertEquals(
            listOf(
                "Refused|Bloqueio recusado|Patinete em movimento · nada foi enviado",
                "Error|Bloqueio rejeitado|O patinete respondeu com erro",
                "Error|Bloqueio sem confirmação|Estado desconhecido · enlace perdido",
            ),
            lines(),
        )
    }

    @Test fun `conexao registra nome e duracao`() {
        val r = recorder()
        mono = 1_000; r.connection(ConnectionStatus.Disconnected, ConnectionStatus.Connecting, false)
        mono = 2_200; r.connection(ConnectionStatus.Connecting, ConnectionStatus.Connected, false)
        assertEquals(listOf("Connected|Conectado|iX7 Pro · conexão em 1,2 s"), lines())
    }

    @Test fun `conexao sem Connecting visto ou sem nome cai para Conectado`() {
        recorder().connection(ConnectionStatus.Disconnected, ConnectionStatus.Connected, false)
        assertEquals(listOf("Connected|Conectado|Conectado"), lines())
    }

    @Test fun `desconexao pedida pelo usuario difere de queda do enlace`() {
        val r = recorder()
        r.connection(ConnectionStatus.Connected, ConnectionStatus.Disconnected, userInitiated = true)
        r.connection(ConnectionStatus.Connected, ConnectionStatus.Disconnected, userInitiated = false)
        assertEquals(listOf("Disconnected|Desconectado|Pedido do usuário", "LinkDropped|Queda de conexão|Queda do enlace"), lines())
    }

    @Test fun `falha ao conectar e erro mas cancelamento do usuario nao`() {
        val r = recorder()
        r.connection(ConnectionStatus.Connecting, ConnectionStatus.Disconnected, userInitiated = true)
        assertEquals(emptyList<String>(), lines())
        r.connection(ConnectionStatus.Connecting, ConnectionStatus.Disconnected, userInitiated = false)
        assertEquals(listOf("Error|Falha ao conectar|Não foi possível conectar"), lines())
    }

    @Test fun `mesmo estado nao gera evento e reconexao registra a tentativa`() {
        val r = recorder()
        r.connection(ConnectionStatus.Connected, ConnectionStatus.Connected, false)
        r.reconnectAttempt(2, 5)
        assertEquals(listOf("Reconnecting|Reconectando|Tentativa 2 de 5"), lines())
    }
}

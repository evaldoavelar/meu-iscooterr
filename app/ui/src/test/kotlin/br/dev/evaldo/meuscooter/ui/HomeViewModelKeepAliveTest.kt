package br.dev.evaldo.meuscooter.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import br.dev.evaldo.meuscooter.data.FakeScooterTransport
import br.dev.evaldo.meuscooter.domain.InMemoryPreferencesStore
import br.dev.evaldo.meuscooter.domain.Preferences
import br.dev.evaldo.meuscooter.domain.SessionKeepAlive
import br.dev.evaldo.meuscooter.protocol.Clock
import br.dev.evaldo.meuscooter.protocol.ConnectionStatus
import br.dev.evaldo.meuscooter.protocol.ScooterTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/** ADR 0007: o serviço em primeiro plano segue a conexão real (início, reconexão, desconexão do usuário, saída). */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelKeepAliveTest {
    private class Link(var failConnects: Int = 0) : (CoroutineScope, Clock) -> ScooterTransport {
        var connects = 0
        var sent = 0
        val status = MutableStateFlow(ConnectionStatus.Disconnected)
        override fun invoke(scope: CoroutineScope, clock: Clock): ScooterTransport {
            val fake = FakeScooterTransport(scope, clock)
            return object : ScooterTransport by fake {
                override val connection: StateFlow<ConnectionStatus> = status
                override suspend fun connect() {
                    if (status.value != ConnectionStatus.Disconnected) return
                    connects++
                    status.value = ConnectionStatus.Connecting
                    delay(100)
                    status.value = if (failConnects > 0) { failConnects--; ConnectionStatus.Disconnected } else ConnectionStatus.Connected
                }
                override suspend fun disconnect() { status.value = ConnectionStatus.Disconnected }
                override suspend fun send(command: br.dev.evaldo.meuscooter.protocol.ScooterCommand): br.dev.evaldo.meuscooter.protocol.Ack { sent++; return fake.send(command) }
            }
        }
    }

    /** Registra as chamadas; `active` = estado atual visto pelo "serviço". */
    private class Recorder : SessionKeepAlive {
        val calls = mutableListOf<String>()
        var handler: (() -> Unit)? = null
        val active get() = calls.lastOrNull() == "start"
        override fun start(onUserDisconnect: () -> Unit) { calls += "start"; handler = onUserDisconnect }
        override fun stop() { calls += "stop"; handler = null }
    }

    private var vmToCancel: HomeViewModel? = null
    @After fun tearDown() { Dispatchers.resetMain() }
    private fun kaTest(body: suspend TestScope.() -> Unit) = runTest { try { body() } finally { vmToCancel?.viewModelScope?.cancel() } }
    private fun TestScope.vm(t: Link, k: SessionKeepAlive, connectOnStart: Boolean = false, auto: Boolean = true): HomeViewModel {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        return HomeViewModel(t, Clock { testScheduler.currentTime }, deviceName = null,
            preferencesStore = InMemoryPreferencesStore(Preferences(autoReconnect = auto)), keepAlive = k,
            connectOnStart = connectOnStart, timeLabel = { "14:32" }).also { vmToCancel = it; runCurrent() }
    }
    private val D = HomeViewModel.AUTO_RECONNECT_DELAYS_MS

    @Test fun `nao inicia enquanto desconectado nem durante a primeira conexao`() = kaTest {
        val t = Link(); val k = Recorder(); vm(t, k, connectOnStart = true)
        assertEquals(emptyList<String>(), k.calls)
        advanceTimeBy(50); runCurrent()   // Conectando
        assertEquals(emptyList<String>(), k.calls)
        advanceTimeBy(100); runCurrent()
        assertEquals(listOf("start"), k.calls)
    }

    @Test fun `conexao que nunca completa nao inicia o servico`() = kaTest {
        val t = Link(failConnects = 100); val k = Recorder(); vm(t, k, connectOnStart = true)
        advanceTimeBy(D.sum() + 10_000); runCurrent()
        assertEquals(emptyList<String>(), k.calls)
    }

    @Test fun `queda com reconexao automatica mantem o servico e a reconexao funciona`() = kaTest {
        val t = Link(); val k = Recorder(); val vm = vm(t, k, connectOnStart = true)
        advanceTimeBy(200); runCurrent()
        t.status.value = ConnectionStatus.Disconnected; runCurrent()
        assertEquals(true, k.active)
        advanceTimeBy(D[0] + 200); runCurrent()
        assertEquals(ConnectionStatus.Connected, vm.controller.state.value.connection)
        assertEquals(2, t.connects)
        assertEquals(listOf("start"), k.calls)
    }

    @Test fun `reconexao esgotada para o servico`() = kaTest {
        val t = Link(); val k = Recorder(); vm(t, k, connectOnStart = true)
        advanceTimeBy(200); runCurrent()
        t.failConnects = 100
        t.status.value = ConnectionStatus.Disconnected; runCurrent()
        advanceTimeBy(D.sum() + 10_000); runCurrent()
        assertEquals(listOf("start", "stop"), k.calls)
    }

    @Test fun `queda com reconexao automatica desligada para o servico`() = kaTest {
        val t = Link(); val k = Recorder(); val vm = vm(t, k, auto = false)
        vm.onConnectionChip(); advanceTimeBy(200); runCurrent()
        assertEquals(listOf("start"), k.calls)
        t.status.value = ConnectionStatus.Disconnected; runCurrent()
        advanceTimeBy(D[0] + 1); runCurrent()
        assertEquals(listOf("start", "stop"), k.calls)
    }

    @Test fun `desconectar pelo menu para o servico sem reconectar`() = kaTest {
        val t = Link(); val k = Recorder(); val vm = vm(t, k, connectOnStart = true)
        advanceTimeBy(200); runCurrent()
        vm.onConnectionMenu(ConnectionMenuAction.Disconnect); runCurrent()
        assertEquals(listOf("start", "stop"), k.calls)
        advanceTimeBy(D.sum() * 2); runCurrent()
        assertEquals(1, t.connects)
    }

    @Test fun `Desconectar da notificacao desconecta, para o servico e nao reconecta nem envia comando`() = kaTest {
        val t = Link(); val k = Recorder(); val vm = vm(t, k, connectOnStart = true)
        advanceTimeBy(200); runCurrent()
        assertNotNull(k.handler)
        k.handler!!.invoke(); runCurrent()
        assertEquals(ConnectionStatus.Disconnected, vm.controller.state.value.connection)
        assertEquals(listOf("start", "stop"), k.calls)
        advanceTimeBy(D.sum() * 2); runCurrent()
        assertEquals(1, t.connects)
        assertEquals(0, t.sent)
    }

    @Test fun `Desconectar da notificacao durante reconexao pendente cancela a reconexao`() = kaTest {
        val t = Link(); val k = Recorder(); vm(t, k, connectOnStart = true)
        advanceTimeBy(200); runCurrent()
        t.status.value = ConnectionStatus.Disconnected; runCurrent()
        k.handler!!.invoke(); runCurrent()
        assertEquals(listOf("start", "stop"), k.calls)
        advanceTimeBy(D.sum() * 2); runCurrent()
        assertEquals(1, t.connects)
    }

    @Test fun `esquecer o patinete para o servico`() = kaTest {
        val t = Link(); val k = Recorder(); val vm = vm(t, k, connectOnStart = true)
        advanceTimeBy(200); runCurrent()
        vm.onForgetScooter(); runCurrent()
        assertEquals(listOf("start", "stop"), k.calls)
    }

    @Test fun `sair do app (onCleared) para o servico`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val t = Link(); val k = Recorder(); val store = ViewModelStore()
        val vm = ViewModelProvider(store, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = HomeViewModel(t, Clock { testScheduler.currentTime }, deviceName = null,
                keepAlive = k, connectOnStart = true, timeLabel = { "14:32" }) as T
        })[HomeViewModel::class.java]
        advanceTimeBy(200); runCurrent()
        assertEquals(ConnectionStatus.Connected, vm.controller.state.value.connection)
        store.clear()
        assertEquals(listOf("start", "stop"), k.calls)
    }
}

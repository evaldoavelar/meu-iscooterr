package br.dev.evaldo.meuscooter.ui

import androidx.lifecycle.viewModelScope
import br.dev.evaldo.meuscooter.data.FakeConfig
import br.dev.evaldo.meuscooter.data.FakePinVault
import br.dev.evaldo.meuscooter.data.FakeScooterTransport
import br.dev.evaldo.meuscooter.domain.AppEventType
import br.dev.evaldo.meuscooter.domain.FakeEventLogStore
import br.dev.evaldo.meuscooter.domain.InMemoryPreferencesStore
import br.dev.evaldo.meuscooter.domain.Preferences
import br.dev.evaldo.meuscooter.protocol.Clock
import br.dev.evaldo.meuscooter.protocol.ConnectionStatus
import br.dev.evaldo.meuscooter.protocol.Pin
import br.dev.evaldo.meuscooter.protocol.ScooterTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** Ganchos do Registro de eventos no ViewModel: um evento por transição real, textos por tipo (PIN sintético 1357). */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelEventLogTest {
    private class Factory : (CoroutineScope, Clock) -> ScooterTransport {
        override fun invoke(scope: CoroutineScope, clock: Clock): ScooterTransport = FakeScooterTransport(scope, clock, FakeConfig(), pinLock = Pin.of("1357"))
    }

    /** Enlace controlável pelo teste (queda/falha). */
    private class Link : (CoroutineScope, Clock) -> ScooterTransport {
        val status = MutableStateFlow(ConnectionStatus.Disconnected)
        var connects = 0
        override fun invoke(scope: CoroutineScope, clock: Clock): ScooterTransport {
            val fake = FakeScooterTransport(scope, clock)
            return object : ScooterTransport by fake {
                override val connection = status
                override suspend fun connect() {
                    if (status.value != ConnectionStatus.Disconnected) return
                    connects++
                    status.value = ConnectionStatus.Connecting
                    kotlinx.coroutines.delay(100)
                    status.value = ConnectionStatus.Connected
                }
                override suspend fun disconnect() { status.value = ConnectionStatus.Disconnected }
            }
        }
    }

    private var vmToCancel: HomeViewModel? = null
    private lateinit var log: FakeEventLogStore
    @After fun tearDown() { Dispatchers.resetMain() }

    private fun vmTest(body: suspend TestScope.() -> Unit) = runTest { try { body() } finally { vmToCancel?.viewModelScope?.cancel() } }

    private fun TestScope.vm(factory: (CoroutineScope, Clock) -> ScooterTransport, vault: FakePinVault = FakePinVault(), name: String? = null): HomeViewModel {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        log = FakeEventLogStore(wallClock = Clock { 5_000_000L + testScheduler.currentTime })
        return HomeViewModel(
            factory, Clock { testScheduler.currentTime }, deviceName = name, timeLabel = { "14:32" }, eventLog = log, pinVault = vault,
            preferencesStore = InMemoryPreferencesStore(Preferences(autoReconnect = true)),
        ).also { vmToCancel = it; runCurrent() }
    }

    private fun lines() = log.events.value.map { "${it.type}|${it.title}|${it.detail}" }

    private suspend fun TestScope.connected(vm: HomeViewModel) { vm.onMainButton(); advanceTimeBy(900); runCurrent() }

    @Test fun `conectar registra um unico evento com nome e tempo`() = vmTest {
        val vm = vm(Factory(), name = "iX7 Pro"); vm.onMainButton(); advanceTimeBy(2_000); runCurrent()
        assertEquals(listOf("Connected|Conectado|iX7 Pro · conexão em 0,8 s"), lines())
    }

    @Test fun `desbloqueio com PIN digitado registra tempo de confirmacao e nao repete com o passar do tempo`() = vmTest {
        val vm = vm(Factory()); connected(vm)
        vm.onUnlockPin(Pin.of("1357")); advanceTimeBy(500); runCurrent()
        advanceTimeBy(10_000); runCurrent()   // Ticks e recomposições não geram eventos novos
        assertEquals(listOf("Connected|Conectado|conexão em 0,8 s", "Unlock|Desbloqueado|PIN digitado · confirmado em 0,4 s"), lines())
    }

    @Test fun `desbloqueio com PIN salvo diz biometria`() = vmTest {
        val vault = FakePinVault(); val vm = vm(Factory(), vault); connected(vm)
        vm.onUnlockPin(Pin.of("1357"), saveAfterUnlock = true); advanceTimeBy(500); runCurrent()
        vm.controller.lock(); advanceTimeBy(1_000); runCurrent()   // trava de novo para poder desbloquear com o PIN salvo
        advanceTimeBy(1_000); runCurrent()
        vm.onUseSavedPin(); advanceTimeBy(1_000); runCurrent()
        assertEquals(
            listOf(
                "Connected|Conectado|conexão em 0,8 s", "Unlock|Desbloqueado|PIN digitado · confirmado em 0,4 s",
                "Lock|Bloqueado|Bloqueio temporário (modo 2) · confirmado em 0,4 s", "Unlock|Desbloqueado|PIN salvo (biometria) · confirmado em 0,4 s",
            ),
            lines(),
        )
    }

    @Test fun `PIN errado cinco vezes registra n de 5 e a pausa uma unica vez`() = vmTest {
        val vm = vm(Factory()); connected(vm)
        repeat(5) { vm.onUnlockPin(Pin.of("2468")); advanceTimeBy(1_000); runCurrent() }
        val wrong = log.events.value.filter { it.type == AppEventType.WrongPin }.map { it.detail }
        assertEquals((1..5).map { "Recusado pelo patinete ($it de 5)" }, wrong)
        assertEquals(listOf("Desbloqueio pausado"), log.events.value.filter { it.type == AppEventType.Refused }.map { it.title })
        assertEquals(7, log.events.value.size)   // conexão + 5 PINs errados + pausa
    }

    @Test fun `nenhum evento contem o PIN digitado`() = vmTest {
        val vm = vm(Factory()); connected(vm)
        vm.onUnlockPin(Pin.of("2468")); advanceTimeBy(1_000); runCurrent()
        vm.onUnlockPin(Pin.of("1357")); advanceTimeBy(1_000); runCurrent()
        log.events.value.forEach { e -> listOf("2468", "1357").forEach { assertFalse(it in e.title + e.detail) } }
        assertEquals(3, log.events.value.size)
    }

    @Test fun `bloqueio modo 2 confirmado registra Bloqueado`() = vmTest {
        val vm = vm(Factory()); connected(vm)
        vm.onUnlockPin(Pin.of("1357")); advanceTimeBy(1_500); runCurrent()
        vm.controller.lock(); advanceTimeBy(1_000); runCurrent()
        assertEquals("Lock|Bloqueado|Bloqueio temporário (modo 2) · confirmado em 0,4 s", lines().last())
    }

    @Test fun `bloqueio repetido recusado pelo reducer nao gera evento`() = vmTest {
        val vm = vm(Factory()); connected(vm)
        vm.onUnlockPin(Pin.of("1357")); advanceTimeBy(1_500); runCurrent()
        vm.controller.lock(); advanceTimeBy(1_000); runCurrent()      // trava
        // Já bloqueado: o reducer recusa antes de o transporte ser chamado, então não há resultado nem evento.
        val before = log.events.value.size
        vm.controller.lock(); advanceTimeBy(1_000); runCurrent()
        assertEquals(before, log.events.value.size)
    }

    @Test fun `desconectar pelo menu registra Pedido do usuario e nao Queda`() = vmTest {
        val link = Link(); val vm = vm(link); vm.onConnectionChip(); advanceTimeBy(200); runCurrent()
        vm.onConnectionMenu(ConnectionMenuAction.Disconnect); runCurrent()
        advanceTimeBy(60_000); runCurrent()
        assertEquals(listOf("Connected|Conectado|conexão em 0,1 s", "Disconnected|Desconectado|Pedido do usuário"), lines())
    }

    @Test fun `queda do enlace registra Queda, tentativa de reconexao e a nova conexao`() = vmTest {
        val link = Link(); val vm = vm(link); vm.onConnectionChip(); advanceTimeBy(200); runCurrent()
        link.status.value = ConnectionStatus.Disconnected; runCurrent()
        advanceTimeBy(HomeViewModel.AUTO_RECONNECT_DELAYS_MS[0] + 300); runCurrent()
        assertEquals(
            listOf(
                "Connected|Conectado|conexão em 0,1 s", "LinkDropped|Queda de conexão|Queda do enlace",
                "Reconnecting|Reconectando|Tentativa 1 de 5", "Connected|Conectado|conexão em 0,1 s",
            ),
            lines(),
        )
    }

    @Test fun `conexao inicial automatica nao registra tentativa de reconexao`() = vmTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val link = Link()
        log = FakeEventLogStore(wallClock = Clock { 1L })
        HomeViewModel(link, Clock { testScheduler.currentTime }, deviceName = null, timeLabel = { "" }, eventLog = log, connectOnStart = true).also { vmToCancel = it }
        runCurrent(); advanceTimeBy(500); runCurrent()
        assertEquals(listOf("Connected|Conectado|conexão em 0,1 s"), lines())
    }

    @Test fun `falha ao conectar registra erro uma vez`() = vmTest {
        val link = Link(); val vm = vm(link); vm.onConnectionChip(); runCurrent()
        link.status.value = ConnectionStatus.Disconnected; runCurrent()   // Conectando → Desconectado sem pedido do usuário
        assertEquals(listOf("Error|Falha ao conectar|Não foi possível conectar"), lines())
    }

    private class ThrowingLog : br.dev.evaldo.meuscooter.domain.EventLog {
        override val events = MutableStateFlow(emptyList<br.dev.evaldo.meuscooter.domain.AppEvent>())
        override fun append(type: AppEventType, detail: String, title: String) = throw IllegalStateException("disco cheio")
        override fun clear() = throw IllegalStateException("disco cheio")
    }

    @Test fun `registro que lanca nao impede desbloqueio nem wipe do PIN nem a reconexao`() = vmTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val vault = FakePinVault()
        val vm = HomeViewModel(Factory(), Clock { testScheduler.currentTime }, deviceName = null, timeLabel = { "" }, eventLog = ThrowingLog(), pinVault = vault)
            .also { vmToCancel = it; runCurrent() }
        connected(vm)
        val pin = Pin.of("1357"); val copy = Pin.of("1357")
        vm.onUnlockPin(pin, saveAfterUnlock = true); advanceTimeBy(1_000); runCurrent()
        assertEquals(MainButtonKind.Confirmed, vm.uiState.value.mainButton.kind)
        assertEquals(true, pin.isWiped)
        assertEquals(1, vault.saves)   // o salvamento do cofre também não depende do registro
        copy.wipe()
        // PIN errado + falha do registro: o aviso segue normal.
        vm.controller.lock(); advanceTimeBy(2_000); runCurrent()
        val bad = Pin.of("2468"); vm.onUnlockPin(bad); advanceTimeBy(1_000); runCurrent()
        assertEquals(true, bad.isWiped)
    }

    @Test fun `registro que lanca nao derruba a vigia de queda e a reconexao automatica`() = vmTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val link = Link()
        val vm = HomeViewModel(link, Clock { testScheduler.currentTime }, deviceName = null, timeLabel = { "" }, eventLog = ThrowingLog(),
            preferencesStore = InMemoryPreferencesStore(Preferences(autoReconnect = true))).also { vmToCancel = it; runCurrent() }
        vm.onConnectionChip(); advanceTimeBy(200); runCurrent()
        link.status.value = ConnectionStatus.Disconnected; runCurrent()
        advanceTimeBy(HomeViewModel.AUTO_RECONNECT_DELAYS_MS[0] + 300); runCurrent()
        assertEquals(2, link.connects)
        assertEquals(ConnectionStatus.Connected, link.status.value)
    }

    @Test fun `telemetria nao e registrada`() = vmTest {
        val vm = vm(Factory()); connected(vm)
        val n = log.events.value.size
        advanceTimeBy(20_000); runCurrent()
        assertEquals(n, log.events.value.size)
    }
}

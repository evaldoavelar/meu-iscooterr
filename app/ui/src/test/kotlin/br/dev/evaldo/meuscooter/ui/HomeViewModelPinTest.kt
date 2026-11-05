package br.dev.evaldo.meuscooter.ui

import androidx.lifecycle.viewModelScope
import br.dev.evaldo.meuscooter.data.FakeConfig
import br.dev.evaldo.meuscooter.data.FakeScooterTransport
import br.dev.evaldo.meuscooter.protocol.Ack
import br.dev.evaldo.meuscooter.protocol.Clock
import br.dev.evaldo.meuscooter.protocol.Pin
import br.dev.evaldo.meuscooter.protocol.ScooterCommand
import br.dev.evaldo.meuscooter.protocol.ScooterTransport
import br.dev.evaldo.meuscooter.protocol.UnlockResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** ViewModel + Fake com PIN (sintético 1357), tempo virtual. */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelPinTest {
    private class Spy : (CoroutineScope, Clock) -> ScooterTransport {
        lateinit var fake: FakeScooterTransport
        val sent = mutableListOf<ScooterCommand>()
        val unlocks = mutableListOf<Pin>()
        override fun invoke(scope: CoroutineScope, clock: Clock): ScooterTransport {
            fake = FakeScooterTransport(scope, clock, FakeConfig(), pinLock = Pin.of("1357"))
            return object : ScooterTransport by fake {
                override suspend fun send(command: ScooterCommand): Ack { sent += command; return fake.send(command) }
                override suspend fun unlock(pin: Pin): UnlockResult { unlocks += pin; return fake.unlock(pin) }
            }
        }
    }

    private var vmToCancel: HomeViewModel? = null

    @After fun tearDown() { Dispatchers.resetMain() }

    private fun vmTest(body: suspend TestScope.() -> Unit) = runTest {
        try { body() } finally { vmToCancel?.viewModelScope?.cancel() }
    }

    private fun TestScope.vm(spy: Spy): HomeViewModel {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val clock = Clock { testScheduler.currentTime }
        return HomeViewModel(spy, clock, deviceName = null, timeLabel = { "14:32" }).also { vmToCancel = it; runCurrent() }
    }

    private suspend fun TestScope.connected(vm: HomeViewModel) { vm.onMainButton(); advanceTimeBy(900); runCurrent() }

    @Test fun `PIN certo envia uma vez, aguarda e so mostra Desbloqueado com a telemetria`() = vmTest {
        val spy = Spy(); val vm = vm(spy); connected(vm)
        assertTrue(vm.uiState.value.mainButton.asksPin)
        val pin = Pin.of("1357")
        vm.onUnlockPin(pin); runCurrent()
        assertEquals(MainButtonKind.Pending, vm.uiState.value.mainButton.kind)
        advanceTimeBy(500); runCurrent()
        assertEquals(MainButtonKind.Confirmed, vm.uiState.value.mainButton.kind)
        assertEquals("Desbloqueado às 14:32", vm.uiState.value.mainButton.label)
        assertEquals(1, spy.unlocks.size)
        assertTrue(pin.isWiped)
        assertTrue(spy.sent.isEmpty())
    }

    @Test fun `PIN errado mostra PIN incorreto sem reenviar`() = vmTest {
        val spy = Spy(); val vm = vm(spy); connected(vm)
        vm.onUnlockPin(Pin.of("2468")); advanceTimeBy(5_000); runCurrent()
        assertEquals("PIN incorreto", vm.uiState.value.connection.label)
        assertEquals(1, spy.unlocks.size)
    }

    @Test fun `no modo PIN o botao principal nao envia comando generico`() = vmTest {
        val spy = Spy(); val vm = vm(spy); connected(vm)
        vm.onMainButton(); advanceTimeBy(1_000); runCurrent()   // Desbloquear: quem abre o diálogo é a tela
        vm.onUnlockPin(Pin.of("1357")); advanceTimeBy(3_000); runCurrent()
        vm.onMainButton(); advanceTimeBy(1_000); runCurrent()   // desbloqueado: Bloquear usa lock() (S20), nunca send()
        assertTrue(spy.sent.isEmpty())
        assertEquals(1, spy.unlocks.size)
    }
}

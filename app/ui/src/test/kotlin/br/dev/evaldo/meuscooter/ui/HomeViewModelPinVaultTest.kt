package br.dev.evaldo.meuscooter.ui

import androidx.lifecycle.viewModelScope
import br.dev.evaldo.meuscooter.data.FakeConfig
import br.dev.evaldo.meuscooter.data.FakePinVault
import br.dev.evaldo.meuscooter.data.FakeScooterTransport
import br.dev.evaldo.meuscooter.protocol.Clock
import br.dev.evaldo.meuscooter.protocol.Pin
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** F5.2b (ADR 0006 §5): o PIN só é guardado após desbloqueio CONFIRMADO; "Usar PIN salvo"; "Esquecer PIN". PIN sintético 1357. */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelPinVaultTest {
    private class Spy : (CoroutineScope, Clock) -> ScooterTransport {
        val unlocks = mutableListOf<Pin>()
        override fun invoke(scope: CoroutineScope, clock: Clock): ScooterTransport {
            val fake = FakeScooterTransport(scope, clock, FakeConfig(), pinLock = Pin.of("1357"))
            return object : ScooterTransport by fake {
                override suspend fun unlock(pin: Pin): UnlockResult { unlocks += pin.duplicate(); return fake.unlock(pin) }
            }
        }
    }

    private var vmToCancel: HomeViewModel? = null
    @After fun tearDown() { Dispatchers.resetMain() }

    private fun vmTest(body: suspend TestScope.() -> Unit) = runTest { try { body() } finally { vmToCancel?.viewModelScope?.cancel() } }

    private fun TestScope.vm(spy: Spy, vault: FakePinVault): HomeViewModel {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val clock = Clock { testScheduler.currentTime }
        return HomeViewModel(spy, clock, deviceName = null, timeLabel = { "14:32" }, pinVault = vault).also { vmToCancel = it; runCurrent() }
    }

    private suspend fun TestScope.connected(vm: HomeViewModel) { vm.onMainButton(); advanceTimeBy(900); runCurrent() }

    @Test fun `guarda o PIN so depois do desbloqueio confirmado`() = vmTest {
        val vault = FakePinVault(); val vm = vm(Spy(), vault); connected(vm)
        val pin = Pin.of("1357")
        vm.onUnlockPin(pin, saveAfterUnlock = true); runCurrent()
        assertEquals(0, vault.saves)   // ainda aguardando o patinete: nada guardado
        advanceTimeBy(500); runCurrent()
        assertEquals(MainButtonKind.Confirmed, vm.uiState.value.mainButton.kind)
        assertEquals(1, vault.saves)
        assertTrue(vm.pinSaved.value)
        assertTrue(pin.isWiped)
    }

    @Test fun `PIN errado nunca e guardado`() = vmTest {
        val vault = FakePinVault(); val vm = vm(Spy(), vault); connected(vm)
        vm.onUnlockPin(Pin.of("2468"), saveAfterUnlock = true); advanceTimeBy(5_000); runCurrent()
        assertEquals("PIN incorreto", vm.uiState.value.connection.label)
        assertEquals(0, vault.saves)
        assertFalse(vm.pinSaved.value)
    }

    @Test fun `sem marcar salvar o PIN nao e guardado`() = vmTest {
        val vault = FakePinVault(); val vm = vm(Spy(), vault); connected(vm)
        vm.onUnlockPin(Pin.of("1357")); advanceTimeBy(3_000); runCurrent()
        assertEquals(0, vault.saves)
    }

    @Test fun `pedido recusado nao deixa copia pendente para um desbloqueio posterior`() = vmTest {
        val vault = FakePinVault(); val vm = vm(Spy(), vault)   // desconectado: o desbloqueio é recusado
        vm.onUnlockPin(Pin.of("1357"), saveAfterUnlock = true); runCurrent()
        connected(vm)
        vm.onUnlockPin(Pin.of("1357")); advanceTimeBy(3_000); runCurrent()   // desbloqueio confirmado SEM pedir para salvar
        assertEquals(0, vault.saves)
    }

    @Test fun `cofre indisponivel ignora salvar`() = vmTest {
        val vault = FakePinVault(available = false); val vm = vm(Spy(), vault); connected(vm)
        vm.onUnlockPin(Pin.of("1357"), saveAfterUnlock = true); advanceTimeBy(3_000); runCurrent()
        assertEquals(0, vault.saves)
        assertFalse(vm.pinVaultAvailable)
    }

    @Test fun `Usar PIN salvo desbloqueia com o PIN do cofre`() = vmTest {
        val spy = Spy(); val vault = FakePinVault(); vault.save(Pin.of("1357")); val vm = vm(spy, vault); connected(vm)
        vm.onUseSavedPin(); runCurrent(); advanceTimeBy(500); runCurrent()
        assertEquals(1, vault.loads)
        assertEquals(1, spy.unlocks.size)
        assertEquals(MainButtonKind.Confirmed, vm.uiState.value.mainButton.kind)
    }

    @Test fun `biometria cancelada nao envia nada`() = vmTest {
        val spy = Spy(); val vault = FakePinVault(); vault.save(Pin.of("1357")); vault.authenticates = false
        val vm = vm(spy, vault); connected(vm)
        vm.onUseSavedPin(); advanceTimeBy(1_000); runCurrent()
        assertTrue(spy.unlocks.isEmpty())
        assertTrue(vm.pinSaved.value)   // cancelar não apaga
    }

    @Test fun `PIN salvo rejeitado pelo patinete e esquecido`() = vmTest {
        val spy = Spy(); val vault = FakePinVault(); vault.save(Pin.of("2468")); val vm = vm(spy, vault); connected(vm)
        vm.onUseSavedPin(); advanceTimeBy(5_000); runCurrent()
        assertEquals("PIN incorreto", vm.uiState.value.connection.label)
        assertFalse(vm.pinSaved.value)
        assertEquals(1, spy.unlocks.size)   // uma tentativa, sem repetir
    }

    @Test fun `Esquecer PIN apaga o cofre`() = vmTest {
        val vault = FakePinVault(); vault.save(Pin.of("1357")); val vm = vm(Spy(), vault)
        assertTrue(vm.pinSaved.value)
        vm.onForgetPin()
        assertFalse(vm.pinSaved.value)
    }
}

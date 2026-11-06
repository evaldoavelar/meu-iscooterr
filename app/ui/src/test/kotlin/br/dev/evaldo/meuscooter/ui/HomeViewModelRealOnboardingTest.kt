package br.dev.evaldo.meuscooter.ui

import androidx.lifecycle.viewModelScope
import br.dev.evaldo.meuscooter.data.FakeScooterTransport
import br.dev.evaldo.meuscooter.data.SimulatedBluetoothEnvironment
import br.dev.evaldo.meuscooter.domain.FoundScooter
import br.dev.evaldo.meuscooter.domain.InMemoryPreferencesStore
import br.dev.evaldo.meuscooter.domain.OnboardingStep
import br.dev.evaldo.meuscooter.domain.Preferences
import br.dev.evaldo.meuscooter.domain.ScooterFinder
import br.dev.evaldo.meuscooter.protocol.Ack
import br.dev.evaldo.meuscooter.protocol.Clock
import br.dev.evaldo.meuscooter.protocol.ScooterCommand
import br.dev.evaldo.meuscooter.protocol.ScooterTransport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** F5.2c: primeiro uso com varredura real (finder injetado). A busca nunca conecta nem escreve; cadastrar = gravar o MAC e reiniciar a tela. */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelRealOnboardingTest {
    private val mac = "AA:BB:CC:DD:EE:FF"

    private class Finder : ScooterFinder {
        val result = CompletableDeferred<FoundScooter>()
        var calls = 0
        var cancelled = false
        override suspend fun find(): FoundScooter {
            calls++
            try { return result.await() } catch (e: kotlinx.coroutines.CancellationException) { cancelled = true; throw e }
        }
    }

    private class Spy : (CoroutineScope, Clock) -> ScooterTransport {
        val sent = mutableListOf<ScooterCommand>()
        var connectCalls = 0
        override fun invoke(scope: CoroutineScope, clock: Clock): ScooterTransport {
            val fake = FakeScooterTransport(scope, clock)
            return object : ScooterTransport by fake {
                override suspend fun send(command: ScooterCommand): Ack { sent += command; return fake.send(command) }
                override suspend fun connect() { connectCalls++; fake.connect() }
            }
        }
    }

    private var toCancel: HomeViewModel? = null
    @After fun tearDown() { Dispatchers.resetMain() }
    private fun test(body: suspend TestScope.() -> Unit) = runTest { try { body() } finally { toCancel?.viewModelScope?.cancel() } }

    private class Rig(val vm: HomeViewModel, val spy: Spy, val finder: Finder, val store: InMemoryPreferencesStore, val registered: MutableList<Unit>)

    private fun TestScope.rig(): Rig {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val spy = Spy(); val finder = Finder(); val store = InMemoryPreferencesStore(Preferences(onboardingDone = false)); val registered = mutableListOf<Unit>()
        val vm = HomeViewModel(spy, Clock { testScheduler.currentTime }, deviceName = null, preferencesStore = store, scooterFinder = finder,
            onScooterRegistered = { registered += Unit }, timeLabel = { "14:32" })
        toCancel = vm; runCurrent()
        return Rig(vm, spy, finder, store, registered)
    }

    @Test fun `nao acha de mentira o patinete simulado em 2 s`() = test {
        val r = rig(); r.vm.startOnboarding(); runCurrent(); testScheduler.advanceTimeBy(60_000); runCurrent()
        assertEquals(OnboardingStep.Searching, r.vm.onboarding.value.step)
        assertNull(r.vm.foundScooter.value)
        assertEquals(1, r.finder.calls)
    }

    @Test fun `achar mostra o nome e nao conecta nem escreve`() = test {
        val r = rig(); r.vm.startOnboarding(); runCurrent()
        r.finder.result.complete(FoundScooter("ix7Pro", mac)); runCurrent()
        assertEquals(OnboardingStep.Found, r.vm.onboarding.value.step)
        assertEquals(FoundScooter("ix7Pro", mac), r.vm.foundScooter.value)
        assertEquals(0, r.spy.connectCalls); assertTrue(r.spy.sent.isEmpty())
        assertNull(r.store.current.scooterAddress); assertFalse(r.store.current.onboardingDone)
    }

    @Test fun `confirmar cadastra o MAC conclui o primeiro uso e pede o reinicio sem tocar o patinete`() = test {
        val r = rig(); r.vm.startOnboarding(); runCurrent()
        r.finder.result.complete(FoundScooter("ix7Pro", mac)); runCurrent()
        r.vm.onboardingConfirm(); runCurrent()
        assertEquals(mac, r.store.current.scooterAddress); assertTrue(r.store.current.onboardingDone)
        assertEquals(1, r.registered.size)
        assertEquals(OnboardingStep.Ready, r.vm.onboarding.value.step)
        assertEquals(0, r.spy.connectCalls); assertTrue(r.spy.sent.isEmpty())
        r.vm.onboardingConfirm(); runCurrent()   // toque duplo
        assertEquals(1, r.registered.size)
    }

    @Test fun `cancelar interrompe a varredura sem cadastrar`() = test {
        val r = rig(); r.vm.startOnboarding(); runCurrent()
        r.vm.onboardingCancel(); runCurrent()
        assertEquals(OnboardingStep.Cancelled, r.vm.onboarding.value.step)
        assertTrue(r.finder.cancelled); assertNull(r.store.current.scooterAddress); assertTrue(r.registered.isEmpty())
    }

    @Test fun `reiniciar o primeiro uso limpa o patinete achado`() = test {
        val r = rig(); r.vm.startOnboarding(); runCurrent()
        r.finder.result.complete(FoundScooter("ix7Pro", mac)); runCurrent()
        r.vm.startOnboarding()   // sem runCurrent: o finder de teste já está completo e acharia de novo
        assertNull(r.vm.foundScooter.value)
        assertEquals(OnboardingStep.Searching, r.vm.onboarding.value.step)
    }

    @Test fun `sem permissao a varredura nao comeca e so segue depois de permitir`() = test {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val finder = Finder(); val env = SimulatedBluetoothEnvironment(permissionGranted = false)
        val vm = HomeViewModel(Spy(), Clock { testScheduler.currentTime }, deviceName = null, preferencesStore = InMemoryPreferencesStore(Preferences(onboardingDone = false)),
            scooterFinder = finder, bluetoothEnvironment = env, timeLabel = { "14:32" })
        toCancel = vm; runCurrent()
        vm.startOnboarding(); runCurrent()
        assertEquals(0, finder.calls)
        assertFalse(vm.bluetooth.value.ready)
        env.setPermissionGranted(true); runCurrent()
        assertEquals(1, finder.calls)
    }
}

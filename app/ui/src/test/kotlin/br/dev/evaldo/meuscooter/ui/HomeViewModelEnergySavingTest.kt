package br.dev.evaldo.meuscooter.ui

import androidx.lifecycle.viewModelScope
import br.dev.evaldo.meuscooter.data.FakeScooterTransport
import br.dev.evaldo.meuscooter.domain.InMemoryPreferencesStore
import br.dev.evaldo.meuscooter.domain.InMemoryTripRepository
import br.dev.evaldo.meuscooter.protocol.Clock
import br.dev.evaldo.meuscooter.protocol.ScooterTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** S52: o aviso só aparece com viagem em gravação (saveRoute + permissão + Running) E economia que para o GPS. */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelEnergySavingTest {
    private var vmToCancel: HomeViewModel? = null
    @After fun tearDown() { Dispatchers.resetMain() }

    private class Env(val vm: HomeViewModel, val power: FakePowerSaveState, val perm: MutableStateFlow<Boolean>)

    private fun scenario(powerInitial: Boolean = false, body: suspend TestScope.(Env) -> Unit) = runTest {
        try {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val power = FakePowerSaveState(powerInitial)
            val perm = MutableStateFlow(true)
            val factory = { scope: CoroutineScope, clock: Clock -> FakeScooterTransport(scope, clock) as ScooterTransport }
            val vm = HomeViewModel(
                factory, Clock { testScheduler.currentTime }, null, InMemoryTripRepository(), { 1_000_000L },
                preferencesStore = InMemoryPreferencesStore(), locationGranted = perm, ioDispatcher = UnconfinedTestDispatcher(testScheduler),
                timeLabel = { "14:32" }, powerSaveState = power,
            ).also { vmToCancel = it; runCurrent() }
            body(Env(vm, power, perm))
        } finally { vmToCancel?.viewModelScope?.cancel() }
    }

    private suspend fun TestScope.startTrip(e: Env) {
        e.vm.onMainButton(); advanceTimeBy(900); runCurrent()
        e.vm.onTelemetryOpened(); runCurrent()
    }

    private val TestScope.shown get() = vmToCancel!!.telemetryState.value.showEnergySavingWarning

    @Test fun `gravando com economia que para o GPS mostra o aviso e some ao desligar`() = scenario(powerInitial = true) { e ->
        startTrip(e)
        assertTrue(shown)
        e.power.set(false); runCurrent()
        assertFalse(shown)
        e.power.set(true); runCurrent()
        assertTrue(shown)
    }

    @Test fun `gravando sem economia nao mostra o aviso`() = scenario { e ->
        startTrip(e)
        assertFalse(shown)
    }

    @Test fun `economia ligada sem viagem em gravacao nao mostra o aviso`() = scenario(powerInitial = true) { e ->
        assertFalse(shown)
        startTrip(e)
        assertTrue(shown)
        e.vm.onTripPlayPause(); runCurrent()   // pausa: sai de Running
        assertFalse(shown)
        e.vm.onTripPlayPause(); runCurrent()
        assertTrue(shown)
        e.vm.onTripStop(); runCurrent()
        assertFalse(shown)
    }

    @Test fun `sem permissao de localizacao ou com salvar trajeto desligado nao mostra o aviso`() = scenario(powerInitial = true) { e ->
        startTrip(e)
        assertTrue(shown)
        e.perm.value = false; runCurrent()
        assertFalse(shown)
        e.perm.value = true; runCurrent()
        assertTrue(shown)
        e.vm.onSaveRoute(false); runCurrent()
        assertFalse(shown)
    }
}

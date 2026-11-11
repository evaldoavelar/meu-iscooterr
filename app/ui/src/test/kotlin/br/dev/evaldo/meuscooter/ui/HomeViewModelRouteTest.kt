package br.dev.evaldo.meuscooter.ui

import androidx.lifecycle.viewModelScope
import br.dev.evaldo.meuscooter.data.FakeScooterTransport
import br.dev.evaldo.meuscooter.domain.GeoPoint
import br.dev.evaldo.meuscooter.domain.GpsFix
import br.dev.evaldo.meuscooter.domain.GpsFixSource
import br.dev.evaldo.meuscooter.domain.InMemoryPreferencesStore
import br.dev.evaldo.meuscooter.domain.InMemoryRouteStore
import br.dev.evaldo.meuscooter.domain.InMemoryTripRepository
import br.dev.evaldo.meuscooter.domain.RouteStore
import br.dev.evaldo.meuscooter.protocol.Clock
import br.dev.evaldo.meuscooter.protocol.ScooterTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Trajeto GPS no HomeViewModel (ADR 0012/0013): grava só com saveRoute + permissão + viagem Running; salva em Parar; apaga com o histórico. */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelRouteTest {
    private var vmToCancel: HomeViewModel? = null
    @After fun tearDown() { Dispatchers.resetMain() }

    private class Source : GpsFixSource {
        val flow = MutableSharedFlow<GpsFix?>(extraBufferCapacity = 64)
        override val fixes: Flow<GpsFix?> = flow
    }

    /** Store que sempre falha (disco cheio): a viagem não pode cair por causa do trajeto (opcional). */
    private class ThrowingStore : RouteStore {
        override fun write(tripId: Long, points: List<GeoPoint>) = throw java.io.IOException("disco cheio")
        override fun read(tripId: Long): List<GeoPoint> = emptyList()
        override fun delete(tripId: Long) = throw java.io.IOException("x")
        override fun clear() = throw java.io.IOException("x")
    }

    private class Env(val vm: HomeViewModel, val fake: () -> FakeScooterTransport, val src: Source, val store: RouteStore, val repo: InMemoryTripRepository, val perm: MutableStateFlow<Boolean>, val prefs: InMemoryPreferencesStore)

    private fun route(storeOf: () -> RouteStore = { InMemoryRouteStore() }, runTest: suspend TestScope.(Env) -> Unit) = runTest {
        try {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            lateinit var fake: FakeScooterTransport
            val src = Source(); val store = storeOf(); val repo = InMemoryTripRepository()
            val perm = MutableStateFlow(true); val prefs = InMemoryPreferencesStore()
            val factory = { scope: CoroutineScope, clock: Clock -> FakeScooterTransport(scope, clock).also { fake = it } as ScooterTransport }
            val vm = HomeViewModel(
                factory, Clock { testScheduler.currentTime }, null, repo, { 1_000_000L }, preferencesStore = prefs,
                gpsFixSource = src, routeStore = store, locationGranted = perm, ioDispatcher = UnconfinedTestDispatcher(testScheduler), timeLabel = { "14:32" },
            ).also { vmToCancel = it; runCurrent() }
            runTest(Env(vm, { fake }, src, store, repo, perm, prefs))
        } finally { vmToCancel?.viewModelScope?.cancel() }
    }

    private fun fix(i: Int) = GpsFix(-23.0 + i * 0.0001, -46.0, 5f, i * 1_000L)   // ~11 m entre fixes (referência geodésica)
    private fun pt(i: Int) = GeoPoint(-23.0 + i * 0.0001, -46.0)
    private suspend fun TestScope.connectAndStart(e: Env) {
        e.vm.onMainButton(); advanceTimeBy(900); runCurrent()
        e.vm.onTelemetryOpened(); runCurrent()
        e.fake().setSpeed(18f)
    }
    private suspend fun TestScope.feed(e: Env, vararg i: Int) { i.forEach { e.src.flow.tryEmit(fix(it)); runCurrent() } }
    private suspend fun TestScope.tick() { advanceTimeBy(2_000); runCurrent() }

    @Test fun `Parar grava o trajeto da viagem e o detalhe le do store`() = route { e ->
        connectAndStart(e)
        feed(e, 0, 1, 2); tick()
        e.vm.onTripStop(); runCurrent()
        val id = e.repo.trips.first().single().id
        assertEquals(listOf(pt(0), pt(1), pt(2)), e.store.read(id))
        assertEquals(listOf(pt(0), pt(1), pt(2)), e.vm.trip(id)!!.route)
    }

    @Test fun `saveRoute desligado nao grava nada`() = route { e ->
        e.vm.onSaveRoute(false); runCurrent()
        connectAndStart(e)
        feed(e, 0, 1, 2); tick()
        e.vm.onTripStop(); runCurrent()
        val id = e.repo.trips.first().single().id
        assertTrue(e.store.read(id).isEmpty())
        assertTrue(e.vm.trip(id)!!.route.isEmpty())
    }

    @Test fun `saveRoute desligado antes de Parar descarta o que ja foi coletado`() = route { e ->
        connectAndStart(e)
        feed(e, 0, 1); tick()
        e.vm.onSaveRoute(false); runCurrent()
        e.vm.onTripStop(); runCurrent()
        assertTrue(e.store.read(e.repo.trips.first().single().id).isEmpty())
    }

    @Test fun `sem permissao de localizacao nao grava`() = route { e ->
        e.perm.value = false; runCurrent()
        connectAndStart(e)
        feed(e, 0, 1, 2); tick()
        e.vm.onTripStop(); runCurrent()
        assertTrue(e.store.read(e.repo.trips.first().single().id).isEmpty())
    }

    @Test fun `Pausa nao grava pontos e retomar volta a gravar`() = route { e ->
        connectAndStart(e)
        feed(e, 0); tick()
        e.vm.onTripPlayPause(); runCurrent()
        feed(e, 1, 2)
        e.vm.onTripPlayPause(); runCurrent()
        feed(e, 3); tick()
        e.vm.onTripStop(); runCurrent()
        assertEquals(listOf(pt(0), pt(3)), e.store.read(e.repo.trips.first().single().id))
    }

    @Test fun `fixes com a viagem parada ou antes de iniciar sao ignorados`() = route { e ->
        feed(e, 0, 1)   // Stopped
        connectAndStart(e)
        feed(e, 2); tick()
        e.vm.onTripStop(); runCurrent()
        assertEquals(listOf(pt(2)), e.store.read(e.repo.trips.first().single().id))
    }

    @Test fun `Reset descarta o trajeto e a viagem seguinte comeca sem pontos antigos`() = route { e ->
        connectAndStart(e)
        feed(e, 0, 1); tick()
        e.vm.onTripReset(); runCurrent()
        e.vm.onTripPlayPause(); runCurrent()
        feed(e, 5); tick()
        e.vm.onTripStop(); runCurrent()
        assertEquals(listOf(pt(5)), e.store.read(e.repo.trips.first().single().id))
    }

    @Test fun `Apagar historico limpa os trajetos`() = route { e ->
        connectAndStart(e)
        feed(e, 0, 1); tick()
        e.vm.onTripStop(); runCurrent()
        val id = e.repo.trips.first().single().id
        assertEquals(2, e.store.read(id).size)   // havia pontos antes de apagar
        e.vm.onClearHistory(); runCurrent()
        assertTrue(e.store.read(id).isEmpty())
        assertTrue(e.repo.trips.first().isEmpty())
    }

    @Test fun `store que lanca na gravacao nao derruba a viagem`() = route({ ThrowingStore() }) { e ->
        connectAndStart(e)
        feed(e, 0, 1); tick()
        e.vm.onTripStop(); runCurrent()
        assertEquals(1, e.repo.trips.first().size)   // a viagem foi salva mesmo sem trajeto
        assertTrue(e.vm.trip(e.repo.trips.first().single().id)!!.route.isEmpty())
    }

    @Test fun `store que lanca ao apagar nao derruba Apagar historico`() = route({ ThrowingStore() }) { e ->
        connectAndStart(e)
        feed(e, 0, 1); tick()
        e.vm.onTripStop(); runCurrent()
        e.vm.onClearHistory(); runCurrent()
        assertTrue(e.repo.trips.first().isEmpty())
    }

    @Test fun `GPS so e assinado com saveRoute permissao e viagem Running`() = route { e ->
        assertEquals(0, e.src.flow.subscriptionCount.value)           // Parada
        e.vm.onMainButton(); advanceTimeBy(900); runCurrent()
        e.vm.onTelemetryOpened(); runCurrent()
        assertEquals(1, e.src.flow.subscriptionCount.value)           // Running
        e.vm.onTripPlayPause(); runCurrent()
        assertEquals(0, e.src.flow.subscriptionCount.value)           // Pausada
        e.vm.onTripPlayPause(); runCurrent()
        assertEquals(1, e.src.flow.subscriptionCount.value)
        e.vm.onSaveRoute(false); runCurrent()
        assertEquals(0, e.src.flow.subscriptionCount.value)           // saveRoute desligado
        e.vm.onSaveRoute(true); runCurrent()
        e.perm.value = false; runCurrent()
        assertEquals(0, e.src.flow.subscriptionCount.value)           // sem permissão
        e.perm.value = true; runCurrent()
        assertEquals(1, e.src.flow.subscriptionCount.value)
        e.vm.onTripStop(); runCurrent()
        assertEquals(0, e.src.flow.subscriptionCount.value)           // Parada
    }
}

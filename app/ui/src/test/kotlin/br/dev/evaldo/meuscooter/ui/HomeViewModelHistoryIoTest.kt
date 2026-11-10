package br.dev.evaldo.meuscooter.ui

import androidx.lifecycle.viewModelScope
import br.dev.evaldo.meuscooter.data.FakeScooterTransport
import br.dev.evaldo.meuscooter.domain.GeoPoint
import br.dev.evaldo.meuscooter.domain.InMemoryPreferencesStore
import br.dev.evaldo.meuscooter.domain.InMemoryRouteStore
import br.dev.evaldo.meuscooter.domain.InMemoryTripRepository
import br.dev.evaldo.meuscooter.domain.RouteStore
import br.dev.evaldo.meuscooter.domain.Trip
import br.dev.evaldo.meuscooter.domain.TripRepository
import br.dev.evaldo.meuscooter.protocol.Clock
import br.dev.evaldo.meuscooter.protocol.ScooterTransport
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Histórico persistente faz E/S: `save`/`clear` do repositório rodam no [ioDispatcher], e o trajeto é apagado ANTES do histórico (S37). */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelHistoryIoTest {
    @After fun tearDown() { Dispatchers.resetMain() }

    /** Executa inline, mas marca que está "no dispatcher de E/S". */
    private class MarkingDispatcher : CoroutineDispatcher() {
        @Volatile var inside = false
        override fun dispatch(context: CoroutineContext, block: Runnable) { inside = true; try { block.run() } finally { inside = false } }
    }

    private class Spy(val io: MarkingDispatcher, val log: MutableList<String>) : TripRepository by InMemoryTripRepository() {
        private val inner = InMemoryTripRepository()
        override val trips = inner.trips
        override suspend fun save(trip: Trip): Long { log += "repo.save io=${io.inside}"; return inner.save(trip) }
        override suspend fun get(id: Long) = inner.get(id)
        override suspend fun clear() { log += "repo.clear io=${io.inside}"; inner.clear() }
    }

    private class LoggingStore(val log: MutableList<String>) : RouteStore {
        private val inner = InMemoryRouteStore()
        override fun write(tripId: Long, points: List<GeoPoint>) = inner.write(tripId, points)
        override fun read(tripId: Long) = inner.read(tripId)
        override fun delete(tripId: Long) = inner.delete(tripId)
        override fun clear() { log += "route.clear"; inner.clear() }
    }

    private fun vmTest(body: suspend kotlinx.coroutines.test.TestScope.(HomeViewModel, FakeHolder, List<String>) -> Unit) = runTest {
        val log = mutableListOf<String>()
        var vm: HomeViewModel? = null
        try {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val io = MarkingDispatcher()
            lateinit var fake: FakeScooterTransport
            val factory = { scope: CoroutineScope, clock: Clock -> FakeScooterTransport(scope, clock).also { fake = it } as ScooterTransport }
            vm = HomeViewModel(
                factory, Clock { testScheduler.currentTime }, null, Spy(io, log), { 1_000_000L }, preferencesStore = InMemoryPreferencesStore(),
                routeStore = LoggingStore(log), ioDispatcher = io, timeLabel = { "14:32" },
            ).also { runCurrent() }
            body(vm, FakeHolder { fake }, log)
        } finally { vm?.viewModelScope?.cancel() }
    }
    private class FakeHolder(val get: () -> FakeScooterTransport)

    @Test fun `apagar historico roda no dispatcher de IO e limpa o trajeto antes`() = vmTest { vm, _, log ->
        vm.onClearHistory(); runCurrent()
        assertEquals(listOf("route.clear", "repo.clear io=true"), log.filter { it.startsWith("route") || it.startsWith("repo.clear") })
    }

    @Test fun `salvar viagem roda no dispatcher de IO`() = vmTest { vm, fh, log ->
        vm.onMainButton(); testScheduler.advanceTimeBy(900); runCurrent()
        vm.onTelemetryOpened(); runCurrent()
        fh.get().setSpeed(18f); testScheduler.advanceTimeBy(2_000); runCurrent()
        vm.onTripStop(); runCurrent()
        assertTrue(log.toString(), log.contains("repo.save io=true"))
    }
}

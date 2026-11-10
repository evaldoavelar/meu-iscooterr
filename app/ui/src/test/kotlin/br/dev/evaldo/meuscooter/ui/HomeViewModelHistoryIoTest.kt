package br.dev.evaldo.meuscooter.ui

import androidx.lifecycle.viewModelScope
import br.dev.evaldo.meuscooter.data.FakeScooterTransport
import br.dev.evaldo.meuscooter.domain.GeoPoint
import br.dev.evaldo.meuscooter.domain.InMemoryPreferencesStore
import br.dev.evaldo.meuscooter.domain.InMemoryRouteStore
import br.dev.evaldo.meuscooter.domain.InMemoryTripRepository
import br.dev.evaldo.meuscooter.domain.GpsFix
import br.dev.evaldo.meuscooter.domain.GpsFixSource
import br.dev.evaldo.meuscooter.domain.RouteStore
import br.dev.evaldo.meuscooter.domain.Trip
import br.dev.evaldo.meuscooter.domain.TripRepository
import br.dev.evaldo.meuscooter.protocol.Clock
import br.dev.evaldo.meuscooter.protocol.ScooterTransport
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
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
        override suspend fun delete(id: Long) { log += "repo.delete id=$id io=${io.inside}"; inner.delete(id) }
        override suspend fun clear() { log += "repo.clear io=${io.inside}"; inner.clear() }
    }

    private class LoggingStore(val log: MutableList<String>) : RouteStore {
        private val inner = InMemoryRouteStore()
        override fun write(tripId: Long, points: List<GeoPoint>) = inner.write(tripId, points)
        override fun read(tripId: Long) = inner.read(tripId)
        override fun delete(tripId: Long) { log += "route.delete id=$tripId"; inner.delete(tripId) }
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

    @Test fun `excluir viagem roda no dispatcher de IO e apaga a rota antes`() = vmTest { vm, _, log ->
        vm.onDeleteTrip(1L); runCurrent()
        assertEquals(listOf("route.delete id=1", "repo.delete id=1 io=true"), log.filter { it.startsWith("route.delete") || it.startsWith("repo.delete") })
    }

    @Test fun `confirmar exclusao no ViewModel apaga viagem e rota e cancelar nao apaga`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = InMemoryTripRepository()
        val kept = repo.save(Trip(0, 1_000, 1f, 60_000, 10f, 12f, null, null, null))
        val toDelete = repo.save(Trip(0, 2_000, 2f, 120_000, 12f, 15f, null, null, null))
        val store = InMemoryRouteStore()
        val ptKept = listOf(GeoPoint(-23.5, -46.6))
        val ptDelete = listOf(GeoPoint(-23.6, -46.7))
        store.write(kept, ptKept)
        store.write(toDelete, ptDelete)

        val vm = HomeViewModel(
            { scope, clock -> FakeScooterTransport(scope, clock) },
            Clock { testScheduler.currentTime },
            null,
            repo,
            { 1_000_000L },
            preferencesStore = InMemoryPreferencesStore(),
            routeStore = store,
            timeLabel = { "14:32" },
        )
        try {
            runCurrent()
            // 1. Pedir para excluir e cancelar: nada é apagado
            vm.onRequestDeleteTrip(toDelete)
            assertEquals(toDelete, vm.pendingDeleteTripId.value)
            vm.onCancelDeleteTrip()
            org.junit.Assert.assertNull(vm.pendingDeleteTripId.value)
            runCurrent()
            assertEquals(2, vm.historyState.value.rows.size)
            assertEquals(ptDelete, store.read(toDelete))

            // 2. Pedir para excluir e confirmar: apaga viagem e rota
            vm.onRequestDeleteTrip(toDelete)
            vm.onConfirmDeleteTrip()
            org.junit.Assert.assertNull(vm.pendingDeleteTripId.value)
            runCurrent()
            assertEquals(listOf(kept), vm.historyState.value.rows.map { it.id })
            assertTrue("Rota deve ter sido apagada", store.read(toDelete).isEmpty())
            assertEquals("Outra rota mantida intacta", ptKept, store.read(kept))

            // 3. Excluir id inexistente não quebra
            vm.onDeleteTrip(999L)
            runCurrent()
            assertEquals(listOf(kept), vm.historyState.value.rows.map { it.id })
        } finally { vm.viewModelScope.cancel() }
    }

    @Test fun `viagem aberta no detalhe que e excluida limpa o detalhe`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = InMemoryTripRepository()
        val id = repo.save(Trip(0, 1_000, 1f, 60_000, 10f, 12f, null, null, null))
        val store = InMemoryRouteStore()
        val vm = HomeViewModel(
            { scope, clock -> FakeScooterTransport(scope, clock) },
            Clock { testScheduler.currentTime },
            null,
            repo,
            { 1_000_000L },
            preferencesStore = InMemoryPreferencesStore(),
            routeStore = store,
            timeLabel = { "14:32" },
        )
        try {
            runCurrent()
            org.junit.Assert.assertNotNull(vm.trip(id))
            org.junit.Assert.assertNotNull(vm.tripDetail(id))

            vm.onDeleteTrip(id)
            runCurrent()

            org.junit.Assert.assertNull(vm.trip(id))
            org.junit.Assert.assertNull(vm.tripDetail(id))
        } finally { vm.viewModelScope.cancel() }
    }

    @Test fun `salvar viagem roda no dispatcher de IO`() = vmTest { vm, fh, log ->
        vm.onMainButton(); testScheduler.advanceTimeBy(900); runCurrent()
        vm.onTelemetryOpened(); runCurrent()
        fh.get().setSpeed(18f); testScheduler.advanceTimeBy(2_000); runCurrent()
        vm.onTripStop(); runCurrent()
        assertTrue(log.toString(), log.contains("repo.save io=true"))
    }

    @Test fun `ao abrir varre trajeto orfao e mantem o de viagem existente`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = InMemoryTripRepository()
        val kept = repo.save(Trip(0, 1_000, 1f, 60_000, 10f, 12f, null, null, null))
        val store = InMemoryRouteStore()
        val pt = listOf(GeoPoint(-23.5, -46.6))
        store.write(kept, pt); store.write(99, pt)
        val vm = HomeViewModel(
            { scope, clock -> FakeScooterTransport(scope, clock) }, Clock { testScheduler.currentTime }, null, repo, { 1_000_000L },
            preferencesStore = InMemoryPreferencesStore(), routeStore = store, timeLabel = { "14:32" },
        )
        try {
            runCurrent()
            assertEquals(pt, store.read(kept))
            assertTrue(store.read(99).isEmpty())
        } finally { vm.viewModelScope.cancel() }
    }

    private class FixSource : GpsFixSource {
        val flow = MutableSharedFlow<GpsFix?>(extraBufferCapacity = 16)
        override val fixes: Flow<GpsFix?> = flow
    }

    private class DelayedTripRepository(
        private val deferred: CompletableDeferred<List<Trip>>,
        val delegate: InMemoryTripRepository = InMemoryTripRepository(),
    ) : TripRepository {
        override val trips: Flow<List<Trip>> = flow {
            emit(deferred.await())
            emitAll(delegate.trips)
        }
        override suspend fun save(trip: Trip): Long = delegate.save(trip)
        override suspend fun get(id: Long): Trip? = delegate.get(id)
        override suspend fun delete(id: Long) = delegate.delete(id)
        override suspend fun clear() = delegate.clear()
    }

    @Test fun `trips atrasado no init nao trava gravacao de trajeto nem perde rota recem-salva na varredura posterior`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val deferred = CompletableDeferred<List<Trip>>()
        val delegate = InMemoryTripRepository()
        val kept = delegate.save(Trip(0, 1_000, 1f, 60_000, 10f, 12f, null, null, null))
        val repo = DelayedTripRepository(deferred, delegate)
        val store = InMemoryRouteStore()
        val oldPt = listOf(GeoPoint(-23.5, -46.6))
        val orphanPt = listOf(GeoPoint(-23.6, -46.7))
        store.write(kept, oldPt)
        store.write(99, orphanPt)

        val src = FixSource()
        val perm = MutableStateFlow(true)
        lateinit var fake: FakeScooterTransport
        val vm = HomeViewModel(
            { scope, clock -> FakeScooterTransport(scope, clock).also { fake = it } },
            Clock { testScheduler.currentTime },
            null,
            repo,
            { 1_000_000L },
            preferencesStore = InMemoryPreferencesStore(),
            routeStore = store,
            gpsFixSource = src,
            locationGranted = perm,
            timeLabel = { "14:32" },
        )
        try {
            runCurrent()
            // Enquanto trips não emitiu:
            // 1. Inicia viagem e envia amostras de telemetria e GPS
            vm.onMainButton(); testScheduler.advanceTimeBy(900); runCurrent()
            vm.onTelemetryOpened(); runCurrent()
            fake.setSpeed(18f)
            src.flow.tryEmit(GpsFix(-23.55, -46.65, 5f, 1_000L))
            testScheduler.advanceTimeBy(2_000); runCurrent()

            // 2. Parar viagem: deve salvar a viagem e o trajeto SEM travar
            vm.onTripStop(); runCurrent()

            val savedTrips = delegate.trips.first()
            val newTrip = savedTrips.first { it.id != kept }
            // A gravação do trajeto NÃO pode ter ficado bloqueada pelo mutex
            val newRoute = store.read(newTrip.id)
            assertEquals("Trajeto da nova viagem deve estar gravado no store mesmo com trips atrasado", listOf(GeoPoint(-23.55, -46.65)), newRoute)

            // 3. Agora o trips finalmente emite o snapshot inicial
            deferred.complete(listOf(delegate.get(kept)!!))
            runCurrent()

            // A varredura de órfãos deve ter removido o órfão (99), mas preservado o kept e a viagem recém-salva!
            assertTrue("Rota órfã 99 deve ter sido removida", store.read(99).isEmpty())
            assertEquals("Rota da viagem existente deve ser mantida", oldPt, store.read(kept))
            assertEquals("Rota da viagem recém-salva NUNCA pode ser removida pela varredura tardia", listOf(GeoPoint(-23.55, -46.65)), store.read(newTrip.id))
        } finally { vm.viewModelScope.cancel() }
    }

    @Test fun `trips atrasado no init nao trava apagar historico`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val deferred = CompletableDeferred<List<Trip>>()
        val delegate = InMemoryTripRepository()
        val kept = delegate.save(Trip(0, 1_000, 1f, 60_000, 10f, 12f, null, null, null))
        val repo = DelayedTripRepository(deferred, delegate)
        val store = InMemoryRouteStore()
        store.write(kept, listOf(GeoPoint(-23.5, -46.6)))

        val vm = HomeViewModel(
            { scope, clock -> FakeScooterTransport(scope, clock) },
            Clock { testScheduler.currentTime },
            null,
            repo,
            { 1_000_000L },
            preferencesStore = InMemoryPreferencesStore(),
            routeStore = store,
            timeLabel = { "14:32" },
        )
        try {
            runCurrent()
            // Apagar histórico enquanto trips não emitiu não pode travar
            vm.onClearHistory()
            runCurrent()

            assertTrue("Trajetos devem ter sido limpos", store.read(kept).isEmpty())
            assertTrue("Repositório deve ter sido limpo", delegate.trips.first().isEmpty())

            // Ao emitir depois, nada quebra
            deferred.complete(emptyList())
            runCurrent()
        } finally { vm.viewModelScope.cancel() }
    }
}

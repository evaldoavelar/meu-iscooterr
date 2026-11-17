package br.dev.evaldo.meuscooter.ui

import androidx.lifecycle.viewModelScope
import br.dev.evaldo.meuscooter.data.FakeScooterTransport
import br.dev.evaldo.meuscooter.domain.InMemoryPreferencesStore
import br.dev.evaldo.meuscooter.domain.InMemoryTripRepository
import br.dev.evaldo.meuscooter.domain.Preferences
import br.dev.evaldo.meuscooter.domain.SessionKeepAlive
import br.dev.evaldo.meuscooter.domain.Trip
import br.dev.evaldo.meuscooter.domain.TripRepository
import br.dev.evaldo.meuscooter.protocol.Clock
import br.dev.evaldo.meuscooter.protocol.ConnectionStatus
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

/** S52: o [HomeViewModel] como [BackupHost] (bloqueio por viagem, preparar/recarregar) e a preferência `restorePromptDone`. */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelBackupHostTest {
    private class CountingRepo(val inner: InMemoryTripRepository = InMemoryTripRepository()) : TripRepository by inner {
        var reloads = 0
        override suspend fun reload() { reloads++ }
    }

    private class Recorder : SessionKeepAlive {
        val calls = mutableListOf<String>()
        override fun setTripRecording(recording: Boolean) = Unit
        override fun start(onUserDisconnect: () -> Unit) { calls += "start" }
        override fun stop() { calls += "stop" }
    }

    private var vmToCancel: HomeViewModel? = null
    @After fun tearDown() { Dispatchers.resetMain() }
    private fun hostTest(body: suspend TestScope.() -> Unit) = runTest { try { body() } finally { vmToCancel?.viewModelScope?.cancel() } }
    private fun TestScope.vm(repo: TripRepository = CountingRepo(), ka: SessionKeepAlive = SessionKeepAlive.None, connectOnStart: Boolean = false, store: InMemoryPreferencesStore = InMemoryPreferencesStore()): HomeViewModel {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        return HomeViewModel({ s, c -> FakeScooterTransport(s, c) }, Clock { testScheduler.currentTime }, null, tripRepository = repo, keepAlive = ka,
            connectOnStart = connectOnStart, preferencesStore = store, timeLabel = { "00:00" }).also { vmToCancel = it; runCurrent() }
    }

    @Test fun `tripRecording acompanha Play, Pausa e Parar`() = hostTest {
        val vm = vm()
        assertFalse(vm.tripRecording.value)
        vm.onTripPlayPause(); runCurrent(); assertTrue(vm.tripRecording.value)
        vm.onTripPlayPause(); runCurrent(); assertTrue(vm.tripRecording.value) // pausada ainda é viagem em curso
        vm.onTripStop(); runCurrent(); assertFalse(vm.tripRecording.value)
    }

    @Test fun `prepareForRestore descarta a viagem, desconecta e derruba o servico`() = hostTest {
        val ka = Recorder()
        val vm = vm(ka = ka, connectOnStart = true)
        advanceTimeBy(2_000); runCurrent()
        assertEquals(ConnectionStatus.Connected, vm.controller.state.value.connection)
        assertEquals(listOf("start"), ka.calls)
        vm.onTripPlayPause(); runCurrent()
        vm.prepareForRestore(); runCurrent()
        assertEquals(ConnectionStatus.Disconnected, vm.controller.state.value.connection)
        assertFalse(vm.tripRecording.value)
        assertEquals("stop", ka.calls.last())
        advanceTimeBy(60_000); runCurrent()
        assertEquals(ConnectionStatus.Disconnected, vm.controller.state.value.connection) // não reconecta sozinho
    }

    @Test fun `reloadAfterRestore relê o repositorio e o historico mostra o novo conteudo`() = hostTest {
        val repo = CountingRepo()
        val vm = vm(repo)
        assertEquals(0, repo.reloads)
        repo.inner.save(Trip(0, 1_700_000_000_000, 2f, 600_000, 12f, 20f))
        vm.reloadAfterRestore(); runCurrent()
        assertEquals(1, repo.reloads)
        assertEquals(2f, vm.trip(1)!!.distanceKm)
    }

    @Test fun `onRestorePromptDone grava a preferencia sem mexer nas outras`() = hostTest {
        val store = InMemoryPreferencesStore(Preferences(autoReconnect = false))
        val vm = vm(store = store)
        assertFalse(vm.preferences.value.restorePromptDone)
        vm.onRestorePromptDone(); runCurrent()
        assertTrue(vm.preferences.value.restorePromptDone); assertFalse(vm.preferences.value.autoReconnect)
        assertEquals(Preferences(autoReconnect = false, restorePromptDone = true), store.current)
    }
}

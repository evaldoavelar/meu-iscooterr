package br.dev.evaldo.meuscooter.ui

import androidx.lifecycle.viewModelScope
import br.dev.evaldo.meuscooter.data.FakeConfig
import br.dev.evaldo.meuscooter.data.FakeScooterTransport
import br.dev.evaldo.meuscooter.domain.InMemoryPreferencesStore
import br.dev.evaldo.meuscooter.protocol.Clock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Os acks dos avisos de 1º uso (S34) são independentes entre si. */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelWarningAckTest {
    private var vm: HomeViewModel? = null

    @After fun tearDown() { vm?.viewModelScope?.cancel(); Dispatchers.resetMain() }

    private fun TestScopeVm(testBody: (HomeViewModel) -> Unit, check: (HomeViewModel) -> Unit) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val clock = Clock { testScheduler.currentTime }
        val v = HomeViewModel({ scope, c -> FakeScooterTransport(scope, c, FakeConfig()) }, clock, deviceName = null, timeLabel = { "14:32" },
            preferencesStore = InMemoryPreferencesStore())
        vm = v
        try { runCurrent(); testBody(v); runCurrent(); check(v) } finally { v.viewModelScope.cancel() }
    }

    @Test fun `ack do cruise nao afeta a partida do zero`() = TestScopeVm({ it.onAckCruiseWarning() }) { v ->
        assertTrue(v.preferences.value.cruiseWarningAck); assertFalse(v.preferences.value.zeroStartWarningAck)
    }

    @Test fun `ack da partida do zero nao afeta o cruise`() = TestScopeVm({ it.onAckZeroStartWarning() }) { v ->
        assertTrue(v.preferences.value.zeroStartWarningAck); assertFalse(v.preferences.value.cruiseWarningAck)
    }
}

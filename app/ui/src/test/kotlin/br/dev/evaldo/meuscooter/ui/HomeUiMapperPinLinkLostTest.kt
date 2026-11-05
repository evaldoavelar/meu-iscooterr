package br.dev.evaldo.meuscooter.ui

import br.dev.evaldo.meuscooter.data.FakeConfig
import br.dev.evaldo.meuscooter.data.FakeScooterTransport
import br.dev.evaldo.meuscooter.domain.HomeController
import br.dev.evaldo.meuscooter.domain.UnlockStatus
import br.dev.evaldo.meuscooter.protocol.Clock
import br.dev.evaldo.meuscooter.protocol.ConnectionStatus
import br.dev.evaldo.meuscooter.protocol.Pin
import br.dev.evaldo.meuscooter.protocol.UnconfirmedCause
import br.dev.evaldo.meuscooter.protocol.UnlockResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Revisão r1 P2-4: regra 5 e plano Fase 4 §7.4; queda efetiva do Fake, nenhum BLE real. PIN sintético 1357. */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeUiMapperPinLinkLostTest {
    @Test fun linkLostDuringUnlockKeepsTheUnknownLockWarningAfterDisconnection() = runTest {
        val clock = Clock { currentTime }
        val fake = FakeScooterTransport(backgroundScope, clock, FakeConfig(), pinLock = Pin.of("1357"))
        val controller = HomeController(fake, backgroundScope, clock)
        val mapper = HomeUiMapper(emptyList(), null) { "14:32" }
        runCurrent()
        controller.connect()
        advanceTimeBy(900)
        runCurrent()
        assertEquals(ConnectionStatus.Connected, controller.state.value.connection)
        controller.unlock(Pin.of("1357"))
        runCurrent()
        assertTrue(controller.state.value.unlock is UnlockStatus.Waiting)
        fake.disconnect() // estado recebido pelo controller, sem fabricar HomeState.copy(Disconnected)
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(ConnectionStatus.Disconnected, fake.connection.value)
        assertEquals(ConnectionStatus.Disconnected, controller.state.value.connection)
        assertEquals(
            UnlockResult.Unconfirmed(UnconfirmedCause.LinkLost),
            (controller.state.value.unlock as UnlockStatus.Finished).result,
        )
        val ui = mapper.map(controller.state.value)
        assertEquals(ConnectionKind.Error, ui.connection.kind)
        assertEquals("Sem confirmação — estado de bloqueio desconhecido", ui.connection.label)
        assertEquals(MainButtonKind.Search, ui.mainButton.kind)
        assertEquals("Procurar patinete", ui.mainButton.label)
    }
}

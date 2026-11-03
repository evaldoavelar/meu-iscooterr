package br.dev.evaldo.meuscooter.ui

import androidx.lifecycle.viewModelScope
import br.dev.evaldo.meuscooter.data.FakeScooterTransport
import br.dev.evaldo.meuscooter.protocol.Ack
import br.dev.evaldo.meuscooter.protocol.Clock
import br.dev.evaldo.meuscooter.protocol.ScooterCommand
import br.dev.evaldo.meuscooter.protocol.ScooterTransport
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
import org.junit.Test

/** Seam S3/S4-lógica: ViewModel + Fake em tempo virtual. Tempos: ACK 50 ms, estado 420 ms (S2 [CONFIRMADO]). */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {
    private class Spy : (kotlinx.coroutines.CoroutineScope, Clock) -> ScooterTransport {
        lateinit var fake: FakeScooterTransport
        val sent = mutableListOf<ScooterCommand>()
        override fun invoke(scope: kotlinx.coroutines.CoroutineScope, clock: Clock): ScooterTransport {
            fake = FakeScooterTransport(scope, clock)
            return object : ScooterTransport by fake {
                override suspend fun send(command: ScooterCommand): Ack { sent += command; return fake.send(command) }
            }
        }
    }

    private var vmToCancel: HomeViewModel? = null

    @After fun tearDown() { Dispatchers.resetMain() }

    /** O loop de tick do controlador nunca fica ocioso: cancela o escopo dentro do corpo do teste. */
    private fun vmTest(body: suspend TestScope.() -> Unit) = runTest {
        try { body() } finally { vmToCancel?.viewModelScope?.cancel() }
    }

    private fun TestScope.vm(spy: Spy = Spy()): HomeViewModel {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val clock = Clock { testScheduler.currentTime }
        return HomeViewModel(spy, clock, deviceName = null, timeLabel = { "14:32" }).also { vmToCancel = it; runCurrent() }
    }

    private suspend fun TestScope.connected(vm: HomeViewModel) { vm.onMainButton(); advanceTimeBy(900); runCurrent() }

    @Test fun `inicia desconectado oferecendo procurar patinete`() = vmTest {
        val vm = vm()
        assertEquals(MainButtonKind.Search, vm.uiState.value.mainButton.kind)
    }

    @Test fun `botao principal conecta e mostra Desbloquear com o patinete bloqueado`() = vmTest {
        val vm = vm(); connected(vm)
        assertEquals(MainButtonKind.Unlock, vm.uiState.value.mainButton.kind)
        assertEquals("82%", vm.uiState.value.battery)
        assertEquals(listOf("Pedestre", "Eco", "Race", "Sport"), vm.uiState.value.mode.options.map { it.label })
    }

    @Test fun `desbloquear fica pendente e so confirma apos a telemetria`() = vmTest {
        val vm = vm(); connected(vm)
        vm.onMainButton(); runCurrent()
        assertEquals(MainButtonKind.Pending, vm.uiState.value.mainButton.kind)
        advanceTimeBy(100); runCurrent()   // ACK (50 ms) já chegou, estado (420 ms) ainda não
        assertEquals(MainButtonKind.Pending, vm.uiState.value.mainButton.kind)
        advanceTimeBy(400); runCurrent()
        assertEquals(MainButtonKind.Confirmed, vm.uiState.value.mainButton.kind)
        assertEquals("Desbloqueado às 14:32", vm.uiState.value.mainButton.label)
    }

    @Test fun `toques repetidos nao duplicam o comando`() = vmTest {
        val spy = Spy(); val vm = vm(spy); connected(vm)
        vm.onMainButton(); vm.onMainButton(); vm.onMainButton(); runCurrent()
        assertEquals(listOf<ScooterCommand>(ScooterCommand.SetLocked(false)), spy.sent)
    }

    @Test fun `farol pendente mostra alvo e depois confirma`() = vmTest {
        val vm = vm(); connected(vm)
        vm.onHeadlight(true); runCurrent()
        assertEquals(true, vm.uiState.value.headlight.checked)
        assertEquals(ControlStatus.Pending, vm.uiState.value.headlight.control.status)
        advanceTimeBy(500); runCurrent()
        assertEquals(ControlStatus.Confirmed, vm.uiState.value.headlight.control.status)
    }

    @Test fun `controles de modo motores cruise partida e limite enviam o comando correto`() = vmTest {
        val spy = Spy(); val vm = vm(spy); connected(vm)
        vm.onMode(2); vm.onMotors(0); vm.onCruise(true); vm.onZeroStart(true); vm.onLimit(30); runCurrent()
        assertEquals(
            setOf(ScooterCommand.SetSpeedMode(2), ScooterCommand.SetDualMotor(false), ScooterCommand.SetCruise(true),
                ScooterCommand.SetZeroStart(true), ScooterCommand.SetSpeedLimit(30)),
            spy.sent.toSet(),
        )
    }

    @Test fun `em movimento a UI desabilita e o pedido e ignorado`() = vmTest {
        val spy = Spy(); val vm = vm(spy); connected(vm)
        spy.fake.setSpeed(18f); advanceTimeBy(400); runCurrent()
        assertEquals(false, vm.uiState.value.mode.control.enabled)
        vm.onMode(3); vm.onMotors(0); vm.onCruise(true); vm.onZeroStart(true); vm.onLimit(30); vm.onMainButton(); runCurrent()
        assertEquals(emptyList<ScooterCommand>(), spy.sent)
    }

    @Test fun `falha injetada mostra Tentar de novo e o toque reenvia`() = vmTest {
        val spy = Spy(); val vm = vm(spy); connected(vm)
        spy.fake.failNextCommand("recusado")
        vm.onMainButton(); advanceTimeBy(100); runCurrent()
        assertEquals(MainButtonKind.Retry, vm.uiState.value.mainButton.kind)
        vm.onMainButton(); runCurrent()
        assertEquals(2, spy.sent.size)
        assertEquals(MainButtonKind.Pending, vm.uiState.value.mainButton.kind)
    }

    @Test fun `comando ignorado pelo patinete termina em timeout`() = vmTest {
        val spy = Spy(); val vm = vm(spy); connected(vm)
        spy.fake.ignoreNextCommand()
        vm.onMainButton(); advanceTimeBy(3_500); runCurrent()
        assertEquals(MainButtonKind.Retry, vm.uiState.value.mainButton.kind)
        assertEquals("Sem resposta do patinete", vm.uiState.value.connection.label)
    }
}

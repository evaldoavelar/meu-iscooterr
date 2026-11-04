package br.dev.evaldo.meuscooter.ui

import androidx.lifecycle.viewModelScope
import br.dev.evaldo.meuscooter.data.FakeScooterTransport
import br.dev.evaldo.meuscooter.data.SimulatedBluetoothEnvironment
import br.dev.evaldo.meuscooter.domain.OnboardingStep
import br.dev.evaldo.meuscooter.domain.RecoveryReason
import br.dev.evaldo.meuscooter.protocol.Ack
import br.dev.evaldo.meuscooter.protocol.Clock
import br.dev.evaldo.meuscooter.protocol.ScooterCommand
import br.dev.evaldo.meuscooter.protocol.ScooterTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import br.dev.evaldo.meuscooter.protocol.ConnectionStatus
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S3: primeiro uso e recuperação sobre o Fake, em tempo virtual. Valores esperados vêm do protótipo
 * (design/screens.py: "Tentativa N de 5"; design/build.py: afterDelay 2000 ms na busca do primeiro uso).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingRecoveryViewModelTest {
    private class Spy(private val neverConnects: Boolean = false) : (CoroutineScope, Clock) -> ScooterTransport {
        val sent = mutableListOf<ScooterCommand>()
        var connectCalls = 0
        override fun invoke(scope: CoroutineScope, clock: Clock): ScooterTransport {
            val fake = FakeScooterTransport(scope, clock)
            return object : ScooterTransport by fake {
                override suspend fun send(command: ScooterCommand): Ack { sent += command; return fake.send(command) }
                override suspend fun connect() { connectCalls++; if (neverConnects) delay(100) else fake.connect() }
            }
        }
    }

    /** Transporte que realmente passa por Connecting e nunca conecta; só volta a Disconnected em disconnect(). */
    private class ConnectingSpy : (CoroutineScope, Clock) -> ScooterTransport {
        var connectCalls = 0
        var disconnectCalls = 0
        private val status = MutableStateFlow(ConnectionStatus.Disconnected)
        override fun invoke(scope: CoroutineScope, clock: Clock): ScooterTransport {
            val fake = FakeScooterTransport(scope, clock)
            return object : ScooterTransport by fake {
                override val connection: StateFlow<ConnectionStatus> = status
                override suspend fun connect() { connectCalls++; status.value = ConnectionStatus.Connecting; delay(60_000) }
                override suspend fun disconnect() { disconnectCalls++; status.value = ConnectionStatus.Disconnected }
            }
        }
    }

    private var toCancel: HomeViewModel? = null
    @After fun tearDown() { Dispatchers.resetMain() }

    private fun test(body: suspend TestScope.() -> Unit) = runTest { try { body() } finally { toCancel?.viewModelScope?.cancel() } }

    private fun TestScope.vm(spy: (CoroutineScope, Clock) -> ScooterTransport = Spy(), env: SimulatedBluetoothEnvironment = SimulatedBluetoothEnvironment()): HomeViewModel {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val clock = Clock { testScheduler.currentTime }
        return HomeViewModel(spy, clock, deviceName = null, bluetoothEnvironment = env, timeLabel = { "14:32" }).also { toCancel = it; runCurrent() }
    }

    private suspend fun TestScope.after(ms: Long) { advanceTimeBy(ms); runCurrent() }

    // ---- primeiro uso ----
    @Test fun `primeiro uso procura por 2 s e encontra o patinete`() = test {
        val vm = vm(); vm.startOnboarding(); runCurrent()
        after(1_999); assertEquals(OnboardingStep.Searching, vm.onboarding.value.step)
        after(1); assertEquals(OnboardingStep.Found, vm.onboarding.value.step)
        assertEquals(ConnectionKind.Disconnected, vm.uiState.value.connection.kind)
    }

    @Test fun `so conecta ao confirmar e nao envia nenhum comando`() = test {
        val spy = Spy(); val vm = vm(spy); vm.startOnboarding(); after(2_000)
        after(5_000); assertEquals(OnboardingStep.Found, vm.onboarding.value.step)
        vm.onboardingConfirm(); runCurrent()
        after(900); assertEquals(OnboardingStep.Connected, vm.onboarding.value.step)
        assertEquals(ConnectionKind.Connected, vm.uiState.value.connection.kind)
        assertTrue(spy.sent.isEmpty())
    }

    @Test fun `comecar conclui o primeiro uso e grava a preferencia`() = test {
        val vm = vm(); vm.startOnboarding(); after(2_000); vm.onboardingConfirm(); after(900)
        vm.onboardingFinish(); runCurrent()
        assertEquals(OnboardingStep.Ready, vm.onboarding.value.step)
        assertTrue(vm.preferences.value.onboardingDone)
    }

    @Test fun `cancelar a busca encerra e nao encontra depois`() = test {
        val vm = vm(); vm.startOnboarding(); after(1_000)
        vm.onboardingCancel(); after(10_000)
        assertEquals(OnboardingStep.Cancelled, vm.onboarding.value.step)
        assertEquals(false, vm.preferences.value.onboardingDone)
    }

    @Test fun `sem permissao ou bluetooth a busca espera o ambiente ficar pronto`() = test {
        val env = SimulatedBluetoothEnvironment(bluetoothEnabled = false)
        val vm = vm(env = env); vm.startOnboarding(); after(10_000)
        assertEquals(OnboardingStep.Searching, vm.onboarding.value.step)
        env.requestEnableBluetooth(); runCurrent(); after(2_000)
        assertEquals(OnboardingStep.Found, vm.onboarding.value.step)
    }

    @Test fun `esquecer o patinete volta ao primeiro uso`() = test {
        val vm = vm(); vm.startOnboarding(); after(2_000); vm.onboardingConfirm(); after(900); vm.onboardingFinish(); runCurrent()
        vm.onForgetScooter(); runCurrent()
        assertEquals(false, vm.preferences.value.onboardingDone)
        assertEquals(OnboardingStep.Searching, vm.onboarding.value.step)
        assertEquals(ConnectionKind.Disconnected, vm.uiState.value.connection.kind)
    }

    // ---- recuperação ----
    @Test fun `reconectar com sucesso limpa o motivo e conecta`() = test {
        val vm = vm(); vm.startRecovery(); runCurrent()
        assertEquals(RecoveryReason.Reconnecting(1), vm.recovery.value)
        after(900)
        assertNull(vm.recovery.value)
        assertEquals(ConnectionKind.Connected, vm.uiState.value.connection.kind)
    }

    @Test fun `sem resposta tenta 5 vezes e termina fora de alcance`() = test {
        val spy = Spy(neverConnects = true); val vm = vm(spy); vm.startRecovery(); runCurrent()
        for (n in 1..5) {
            assertEquals("tentativa $n", RecoveryReason.Reconnecting(n), vm.recovery.value)
            assertEquals("Tentativa $n de 5", (vm.recovery.value as RecoveryReason.Reconnecting).attemptLabel)
            after(if (n < 5) 5_000 else 24_999 - 20_000)
        }
        assertEquals(RecoveryReason.Reconnecting(5), vm.recovery.value)
        after(1)
        assertEquals(RecoveryReason.OutOfRange, vm.recovery.value)
        assertEquals(5, spy.connectCalls)
    }

    @Test fun `tentar agora depois de fora de alcance recomeca na tentativa 1`() = test {
        val vm = vm(Spy(neverConnects = true)); vm.startRecovery(); after(25_000)
        assertEquals(RecoveryReason.OutOfRange, vm.recovery.value)
        vm.startRecovery(); runCurrent()
        assertEquals(RecoveryReason.Reconnecting(1), vm.recovery.value)
    }

    @Test fun `cancelar interrompe as tentativas e limpa o motivo`() = test {
        val spy = Spy(neverConnects = true); val vm = vm(spy); vm.startRecovery(); after(6_000)
        vm.cancelRecovery(); val calls = spy.connectCalls; after(60_000)
        assertNull(vm.recovery.value)
        assertEquals(calls, spy.connectCalls)
    }

    @Test fun `cancelar durante Connecting desconecta o transporte`() = test {
        val spy = ConnectingSpy(); val vm = vm(spy); vm.startRecovery(); after(1_000)
        assertEquals(ConnectionKind.Connecting, vm.uiState.value.connection.kind)
        vm.cancelRecovery(); after(1_000)
        assertEquals(1, spy.disconnectCalls)
        assertEquals(ConnectionKind.Disconnected, vm.uiState.value.connection.kind)
    }

    @Test fun `as tentativas 2 a 5 chamam connect mesmo com o transporte em Connecting`() = test {
        val spy = ConnectingSpy(); val vm = vm(spy); vm.startRecovery(); after(25_000)
        assertEquals(5, spy.connectCalls)
        assertEquals(RecoveryReason.OutOfRange, vm.recovery.value)
    }

    @Test fun `bluetooth desligado aparece primeiro e Ativar reconecta`() = test {
        val env = SimulatedBluetoothEnvironment(bluetoothEnabled = false); val vm = vm(env = env)
        vm.startRecovery(); runCurrent()
        assertEquals(RecoveryReason.BluetoothOff, vm.recovery.value)
        vm.onEnableBluetooth(); vm.startRecovery(); runCurrent()
        assertEquals(RecoveryReason.Reconnecting(1), vm.recovery.value)
        after(900); assertEquals(ConnectionKind.Connected, vm.uiState.value.connection.kind)
    }

    @Test fun `Ativar e Permitir so mexem no ambiente (nao conectam sozinhos)`() = test {
        val spy = Spy(); val env = SimulatedBluetoothEnvironment(false, false); val vm = vm(spy, env)
        vm.onGrantPermission(); vm.onEnableBluetooth(); after(10_000)
        assertEquals(0, spy.connectCalls)
        assertEquals(ConnectionKind.Disconnected, vm.uiState.value.connection.kind)
    }

    @Test fun `permissao negada tem prioridade e Permitir revela o proximo motivo`() = test {
        val env = SimulatedBluetoothEnvironment(bluetoothEnabled = false, permissionGranted = false); val vm = vm(env = env)
        vm.startRecovery(); runCurrent()
        assertEquals(RecoveryReason.PermissionDenied, vm.recovery.value)
        vm.onGrantPermission(); runCurrent()
        assertEquals(RecoveryReason.BluetoothOff, vm.recovery.value)
    }

    @Test fun `abrir configuracoes do Android e simulado`() = test {
        val env = SimulatedBluetoothEnvironment(permissionGranted = false); val vm = vm(env = env)
        vm.onOpenSystemSettings()
        assertEquals(1, env.settingsOpenedCount)
    }

    @Test fun `desligar o bluetooth no meio das tentativas interrompe e mostra o motivo`() = test {
        val env = SimulatedBluetoothEnvironment(); val vm = vm(Spy(neverConnects = true), env); vm.startRecovery(); after(6_000)
        env.setBluetoothEnabled(false); runCurrent(); after(5_000)
        assertEquals(RecoveryReason.BluetoothOff, vm.recovery.value)
    }
}

package br.dev.evaldo.meuscooter.ui

import androidx.lifecycle.viewModelScope
import br.dev.evaldo.meuscooter.data.FakeScooterTransport
import br.dev.evaldo.meuscooter.domain.HomeRules
import br.dev.evaldo.meuscooter.protocol.Ack
import br.dev.evaldo.meuscooter.protocol.Clock
import br.dev.evaldo.meuscooter.protocol.ScooterCommand
import br.dev.evaldo.meuscooter.protocol.ScooterTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
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

    @Test fun `telemetria da tela acompanha a velocidade e acumula a viagem`() = vmTest {
        val spy = Spy(); val vm = vm(spy); connected(vm)
        spy.fake.setSpeed(18f)
        advanceTimeBy(1_000); runCurrent()
        assertEquals("18", vm.telemetryState.value.speedText)
        assertEquals("18 km/h", vm.telemetryState.value.maxText)
        assertEquals("54,0", vm.telemetryState.value.voltageText)
    }

    @Test fun `telemetria da tela vira traco e avisa quando a telemetria envelhece ou a conexao cai`() = vmTest {
        val spy = Spy(); val vm = vm(spy); connected(vm)
        spy.fake.setSpeed(18f)
        advanceTimeBy(1_000); runCurrent()
        assertEquals("18", vm.telemetryState.value.speedText)
        assertEquals(null, vm.telemetryState.value.staleNote)
        spy.fake.freezeTelemetry(true)
        advanceTimeBy(HomeRules.STALE_AFTER_MS + 1_000); runCurrent()
        assertEquals("--", vm.telemetryState.value.speedText)
        assertEquals("Dados desatualizados", vm.telemetryState.value.staleNote)
    }

    @Test fun `zerar viagem recomeca a viagem e nao envia comando ao patinete`() = vmTest {
        val spy = Spy(); val vm = vm(spy); connected(vm)
        spy.fake.setSpeed(18f)
        advanceTimeBy(1_000); runCurrent()
        vm.onZeroTrip(); runCurrent()
        assertEquals("0 km/h", vm.telemetryState.value.maxText)
        assertEquals("00:00", vm.telemetryState.value.durationText)
        assertEquals(emptyList<ScooterCommand>(), spy.sent)
    }

    @Test fun `zerar viagem com dados salva no historico com bateria e sem enviar comando`() = vmTest {
        val spy = Spy(); val repo = br.dev.evaldo.meuscooter.domain.InMemoryTripRepository()
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val vm = HomeViewModel(spy, Clock { testScheduler.currentTime }, null, repo, { 1_000_000L }) { "14:32" }.also { vmToCancel = it; runCurrent() }
        connected(vm)
        spy.fake.setSpeed(18f)
        advanceTimeBy(1_000); runCurrent()
        vm.onZeroTrip(); runCurrent()
        val saved = repo.trips.first().single()
        assertEquals(1_000_000L - saved.durationMs, saved.startedAtMs)
        assertEquals(82, saved.batteryStartPercent)
        assertEquals(18f, saved.maxKmh, 0.001f)
        assertEquals(1, vm.historyState.value.rows.size)
        assertEquals(emptyList<ScooterCommand>(), spy.sent)
    }

    @Test fun `zerar sem nenhuma amostra nao salva viagem`() = vmTest {
        val repo = br.dev.evaldo.meuscooter.domain.InMemoryTripRepository()
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val vm = HomeViewModel(Spy(), Clock { testScheduler.currentTime }, null, repo, { 0L }) { "14:32" }.also { vmToCancel = it; runCurrent() }
        vm.onZeroTrip(); runCurrent()
        assertEquals(true, vm.historyState.value.empty)
    }

    @Test fun `zerar com uma unica amostra e sem distancia nao salva viagem vazia`() = vmTest {
        val repo = br.dev.evaldo.meuscooter.domain.InMemoryTripRepository()
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val vm = HomeViewModel(Spy(), Clock { testScheduler.currentTime }, null, repo, { 0L }) { "14:32" }.also { vmToCancel = it; runCurrent() }
        connected(vm)   // conectar emite uma única amostra de telemetria
        vm.onZeroTrip(); runCurrent()
        assertEquals(true, vm.historyState.value.empty)
    }

    // ---- 5.4 Preferências ----
    private fun TestScope.vmWith(spy: Spy = Spy(), repo: br.dev.evaldo.meuscooter.domain.TripRepository = br.dev.evaldo.meuscooter.domain.InMemoryTripRepository()): HomeViewModel {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        return HomeViewModel(spy, Clock { testScheduler.currentTime }, null, repo, { 0L }) { "14:32" }.also { vmToCancel = it; runCurrent() }
    }

    @Test fun `unidades imperiais propagam para Home e Telemetria`() = vmTest {
        val spy = Spy(); val vm = vmWith(spy); connected(vm)
        spy.fake.setSpeed(60f)
        vm.onUnits(br.dev.evaldo.meuscooter.domain.Units.Imperial)
        advanceTimeBy(1_000); runCurrent()
        assertEquals("37 mph", vm.uiState.value.speed)
        assertEquals("mph", vm.telemetryState.value.speedUnit)
    }

    @Test fun `preferencias padrao e alteracoes de tema, reconexao e trajeto`() = vmTest {
        val vm = vmWith()
        assertEquals(br.dev.evaldo.meuscooter.domain.Preferences(), vm.preferences.value)
        vm.onTheme(br.dev.evaldo.meuscooter.domain.ThemeMode.Dark); vm.onAutoReconnect(false); vm.onSaveRoute(false); runCurrent()
        assertEquals(br.dev.evaldo.meuscooter.domain.Preferences(theme = br.dev.evaldo.meuscooter.domain.ThemeMode.Dark, autoReconnect = false, saveRoute = false), vm.preferences.value)
    }

    @Test fun `apagar historico limpa a lista e nao envia comando`() = vmTest {
        val spy = Spy()
        val repo = br.dev.evaldo.meuscooter.domain.InMemoryTripRepository(listOf(br.dev.evaldo.meuscooter.domain.Trip(1, 0, 1f, 60_000, 10f, 20f)))
        val vm = vmWith(spy, repo)
        assertEquals(1, vm.historyState.value.rows.size)
        vm.onClearHistory(); runCurrent()
        assertEquals(true, vm.historyState.value.empty)
        assertEquals(emptyList<ScooterCommand>(), spy.sent)
    }

    @Test fun `esquecer patinete desconecta e volta a oferecer procurar`() = vmTest {
        val spy = Spy(); val vm = vmWith(spy); connected(vm)
        vm.onForgetScooter(); runCurrent()
        assertEquals(MainButtonKind.Search, vm.uiState.value.mainButton.kind)
        assertEquals(emptyList<ScooterCommand>(), spy.sent)
    }

    @Test fun `historico atualiza Hoje para Ontem na virada do dia sem nova emissao de dados`() = vmTest {
        val zone = java.time.ZoneOffset.UTC
        val t0 = java.time.ZonedDateTime.of(2026, 11, 3, 23, 0, 0, 0, zone).toInstant().toEpochMilli()
        val started = java.time.ZonedDateTime.of(2026, 11, 3, 22, 0, 0, 0, zone).toInstant().toEpochMilli()
        val repo = br.dev.evaldo.meuscooter.domain.InMemoryTripRepository()
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        repo.save(br.dev.evaldo.meuscooter.domain.Trip(1, started, 4.8f, 21 * 60_000L, 14f, 27f))
        val vm = HomeViewModel(Spy(), Clock { testScheduler.currentTime }, null, repo, { t0 + testScheduler.currentTime }, zone = zone) { "14:32" }
            .also { vmToCancel = it; runCurrent() }
        assertEquals("Hoje, 22:00", vm.historyState.value.rows.single().title)
        advanceTimeBy(59 * 60_000L); runCurrent()
        assertEquals("Hoje, 22:00", vm.historyState.value.rows.single().title)   // 23:59: ainda é o mesmo dia
        advanceTimeBy(2 * 60_000L); runCurrent()                                    // 00:01 do dia 4
        assertEquals("Ontem, 22:00", vm.historyState.value.rows.single().title)
    }
}

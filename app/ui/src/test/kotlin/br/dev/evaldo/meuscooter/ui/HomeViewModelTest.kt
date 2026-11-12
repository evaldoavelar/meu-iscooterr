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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
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
    private class Spy(private val relay: Boolean = false, private val meter1FrozenAtMs: Long? = null, var meter2FrozenAtMs: Long? = null) : (kotlinx.coroutines.CoroutineScope, Clock) -> ScooterTransport {
        lateinit var fake: FakeScooterTransport
        val sent = mutableListOf<ScooterCommand>()
        var connects = 0
        /** Telemetria injetável (só com `relay = true`): permite entregar uma amostra "tardia" com carimbo arbitrário. */
        val injected = kotlinx.coroutines.flow.MutableSharedFlow<br.dev.evaldo.meuscooter.protocol.Telemetry>(replay = 1, extraBufferCapacity = 64)
        override fun invoke(scope: kotlinx.coroutines.CoroutineScope, clock: Clock): ScooterTransport {
            fake = FakeScooterTransport(scope, clock)
            if (relay) scope.launch { fake.telemetry.collect { injected.emit(it) } }
            return object : ScooterTransport by fake {
                override val telemetry: kotlinx.coroutines.flow.SharedFlow<br.dev.evaldo.meuscooter.protocol.Telemetry> =
                    if (relay) injected else fake.telemetry
                override val agedTelemetry: kotlinx.coroutines.flow.Flow<br.dev.evaldo.meuscooter.protocol.AgedTelemetry> =
                    telemetry.map { br.dev.evaldo.meuscooter.protocol.AgedTelemetry(it, meter1FrozenAtMs ?: it.receivedAtMs, meter2FrozenAtMs ?: it.receivedAtMs) }
                override suspend fun connect() { connects++; fake.connect() }
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

    private fun TestScope.vm(spy: Spy = Spy(), gps: br.dev.evaldo.meuscooter.domain.GpsSpeedSource? = null): HomeViewModel {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val clock = Clock { testScheduler.currentTime }
        return HomeViewModel(spy, clock, deviceName = null, timeLabel = { "14:32" }, gpsSpeedSource = gps ?: br.dev.evaldo.meuscooter.domain.GpsSpeedSource.None).also { vmToCancel = it; runCurrent() }
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

    // ---- revisão Codex r1, achado 1: Tentar de novo repete o comando original ----
    @Test fun `Tentar de novo apos timeout repete o desbloqueio mesmo com confirmacao tardia na telemetria`() = vmTest {
        val spy = Spy(relay = true); val vm = vm(spy); connected(vm)
        spy.fake.ignoreNextCommand(); spy.fake.freezeTelemetry(true)
        vm.onMainButton(); advanceTimeBy(3_500); runCurrent()                 // Desbloquear -> sem resposta
        assertEquals(MainButtonKind.Retry, vm.uiState.value.mainButton.kind)
        // Amostra tardia, carimbada ANTES do timeout: mostra locked=false mas não limpa o erro.
        spy.injected.emit(br.dev.evaldo.meuscooter.protocol.Telemetry(receivedAtMs = 2_900, locked = false, speedKmh = 0f))
        runCurrent()
        assertEquals(MainButtonKind.Retry, vm.uiState.value.mainButton.kind)
        vm.onMainButton(); runCurrent()
        assertEquals(listOf(ScooterCommand.SetLocked(false), ScooterCommand.SetLocked(false)), spy.sent)
    }

    // ---- revisão Codex r1, achado 4: toques repetidos em "Procurar patinete" ----
    @Test fun `varios toques em Procurar antes do estado atualizar agendam um unico connect`() = vmTest {
        val spy = Spy(); val vm = vm(spy)
        vm.onMainButton(); vm.onMainButton(); vm.onMainButton(); runCurrent()
        advanceTimeBy(900); runCurrent()
        assertEquals(1, spy.connects)
    }

    @Test fun `comando ignorado pelo patinete termina em timeout`() = vmTest {
        val spy = Spy(); val vm = vm(spy); connected(vm)
        spy.fake.ignoreNextCommand()
        vm.onMainButton(); advanceTimeBy(3_500); runCurrent()
        assertEquals(MainButtonKind.Retry, vm.uiState.value.mainButton.kind)
        assertEquals("Sem resposta do patinete", vm.uiState.value.connection.label)
    }

    @Test fun `telemetria da tela acompanha a velocidade e acumula a viagem`() = vmTest {
        val spy = Spy(); val vm = vm(spy); connected(vm); vm.onTelemetryOpened()
        spy.fake.setSpeed(18f)
        advanceTimeBy(1_000); runCurrent()
        assertEquals("18", vm.telemetryState.value.speedText)
        assertEquals("18 km/h", vm.telemetryState.value.maxText)
        assertEquals("54,0", vm.telemetryState.value.voltageText)
    }

    /**
     * Fake a 18 km/h: I = 18 x 0,4 = 7,2 A; P = 54 V x 7,2 A = 388,8 W. Conectou em t=800 (0 W), setSpeed em t=900,
     * amostras a cada 300 ms: 1100, 1400, 1700 com 388,8 W. Fatias absolutas (S45-E): fatia 0 (0..1249 ms) = 800 (0 W) + 1100 (388,8 W): média 194,4 W -> 0,2592;
     * a última (fatia do "agora", 1250..2499 ms) = 1400 e 1700 com 388,8 W -> 0,5184. A potência ATUAL é 388,8/750 = 0,5184 (linha tracejada), distinta da barra.
     */
    private fun assertPower18kmh(vm: HomeViewModel) {
        val ui = vm.telemetryState.value
        assertEquals(24, ui.powerBars.size)
        assertEquals(0.5184f, ui.powerBars.last(), 0.0005f)
        assertEquals(0.2592f, ui.powerBars[22], 0.0005f)
        assertEquals(0.5184f, ui.powerNowFraction!!, 0.0005f)
        assertEquals("389 W", ui.powerText)
    }

    @Test fun `telemetria da tela alimenta o historico de potencia e expoe powerBars`() = vmTest {
        val spy = Spy(); val vm = vm(spy); connected(vm)
        spy.fake.setSpeed(18f)
        advanceTimeBy(1_000); runCurrent()
        assertEquals(24, vm.telemetryState.value.powerBars.size)
        assertPower18kmh(vm)
    }

    @Test fun `onTripPlayPause devolve true so quando o toque foi Play`() = vmTest {
        val spy = Spy(); val vm = vm(spy); connected(vm); vm.onTelemetryOpened(); runCurrent()
        assertEquals(br.dev.evaldo.meuscooter.domain.TripRecState.Running, vm.tripRecState.value)
        assertEquals(false, vm.onTripPlayPause())   // Running -> pausa
        assertEquals(true, vm.onTripPlayPause())    // Paused -> Play
        vm.onTripStop(); runCurrent()
        assertEquals(true, vm.onTripPlayPause())    // Stopped -> Play
        assertEquals(false, vm.onTripPlayPause())   // 2o toque seguido: pausa
        vm.onTripStop(); runCurrent()
        assertEquals(true, vm.onTripPlayPause())    // Parar depois Play
    }

    @Test fun `historico de potencia segue alimentado com a viagem Pausada`() = vmTest {
        val spy = Spy(); val vm = vm(spy); connected(vm); vm.onTelemetryOpened(); runCurrent()
        vm.onTripPlayPause(); runCurrent()
        assertEquals(br.dev.evaldo.meuscooter.domain.TripRecState.Paused, vm.tripRecState.value)
        spy.fake.setSpeed(18f)
        advanceTimeBy(1_000); runCurrent()
        assertEquals(24, vm.telemetryState.value.powerBars.size)
        assertPower18kmh(vm)
    }

    @Test fun `historico de potencia segue alimentado com a viagem Parada`() = vmTest {
        val spy = Spy(); val vm = vm(spy); connected(vm); vm.onTelemetryOpened(); vm.onTripStop(); runCurrent()
        assertEquals(br.dev.evaldo.meuscooter.domain.TripRecState.Stopped, vm.tripRecState.value)
        spy.fake.setSpeed(18f)
        advanceTimeBy(1_000); runCurrent()
        assertEquals(24, vm.telemetryState.value.powerBars.size)
        assertPower18kmh(vm)
    }

    /** Meter2 parado em t=0: amostras com mais de 2 s de idade do grupo não entram; só as 1as (frescas) estão no histórico. */
    @Test fun `potencia com Meter2 vencido nao entra no historico`() = vmTest {
        val spy = Spy(meter2FrozenAtMs = 0); val vm = vm(spy); connected(vm)
        spy.fake.setSpeed(18f)
        advanceTimeBy(19_000); runCurrent()   // agora ~19,9 s: janela [-10,1 s .. 19,9 s]; amostras frescas só até ~2 s (fatias 0..9)
        // Meter2 vencido = sem dado: sem barras, sem linha de referência e texto "-- W" (nada de histórico antigo como se fosse atual).
        val ui = vm.telemetryState.value
        assertEquals(emptyList<Float>(), ui.powerBars)
        assertEquals(null, ui.powerNowFraction)
        assertEquals("-- W", ui.powerText)
    }

    /**
     * Recuperação do Meter2: congelado em t=0 até ~19,9 s (potência vencida, nada entra no histórico) e depois volta a chegar fresco.
     * Amostras do Fake a cada 300 ms (800 + 300k): frescas só em 20000, 20300, 20600 (e 20900), todas com 388,8 W (18 km/h).
     * Barras esperadas EXATAS (janela de 30 s, fatias de 1,25 s): as amostras frescas do início (800 com 0 W, 1100/1400/1700 e 2000, idade <= 2 s)
     * caem nas fatias absolutas 0 (0 W + 388,8 W: 0,2592, índice 7) e 1 (3 x 388,8 W: 0,5184, índice 8); as demais ficam em 0 (qualquer amostra vencida entre ~2 s e 19,9 s vazaria aqui); a última = 388,8/750 = 0,5184.
     */
    @Test fun `Meter2 que volta a chegar fresco nao traz amostras vencidas para o historico`() = vmTest {
        val spy = Spy(meter2FrozenAtMs = 0); val vm = vm(spy); connected(vm)
        spy.fake.setSpeed(18f)
        advanceTimeBy(19_000); runCurrent()
        assertEquals(emptyList<Float>(), vm.telemetryState.value.powerBars)
        spy.meter2FrozenAtMs = null   // Meter2 volta: idade do grupo = idade do quadro
        advanceTimeBy(1_000); runCurrent()
        val ui = vm.telemetryState.value
        assertEquals("389 W", ui.powerText)
        assertEquals(0.5184f, ui.powerNowFraction!!, 0.0005f)
        assertEquals(24, ui.powerBars.size)
        val expected = List(24) { i -> when (i) { 7 -> 0.2592f; 8, 23 -> 0.5184f; else -> 0f } }
        expected.forEachIndexed { i, e -> assertEquals("fatia $i", e, ui.powerBars[i], 0.0005f) }
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

    @Test fun relayedReadingReachesHomeAndTelemetryWithTheSameGroupAges() = vmTest {
        val spy = Spy(relay = true); val vm = vm(spy); connected(vm)
        spy.fake.freezeTelemetry(true)
        spy.injected.emit(br.dev.evaldo.meuscooter.protocol.Telemetry(
            receivedAtMs = testScheduler.currentTime, locked = false, speedKmh = 18f, scooterSpeedKmh = 18f, batteryPercent = 73,
        ))
        runCurrent()
        assertEquals("73%", vm.uiState.value.battery)
        assertEquals("73%", vm.telemetryState.value.batteryText)
        assertEquals("18 km/h", vm.uiState.value.speed)
        assertEquals("18", vm.telemetryState.value.speedText)
    }

    // Meter1 (bateria/bloqueio) parado em t=0 enquanto o Meter2 segue vivo: com 3000 ms > STALE_AFTER_MS (2000) a bateria não pode parecer atual.
    @Test fun `Meter1 velho com Meter2 vivo mostra bateria sem leitura na Home e na Telemetria`() = vmTest {
        val spy = Spy(meter1FrozenAtMs = 0); val vm = vm(spy); connected(vm)
        spy.fake.setSpeed(18f)
        advanceTimeBy(3_000); runCurrent()
        assertEquals("--", vm.telemetryState.value.speedText) // S42: velocidade do patinete (Meter1) velha = desconhecida
        assertEquals("--", vm.uiState.value.battery)
        assertEquals("--", vm.telemetryState.value.batteryText)
    }

    @Test fun `Fake em uso normal nao fica com Meter1 velho`() = vmTest {
        val spy = Spy(); val vm = vm(spy); connected(vm)
        advanceTimeBy(5_000); runCurrent()
        assertEquals(false, vm.uiState.value.battery == "--")
        assertEquals(false, vm.telemetryState.value.batteryText == "--")
    }

    @Test fun `parar viagem recomeca a viagem e nao envia comando ao patinete`() = vmTest {
        val spy = Spy(); val vm = vm(spy); connected(vm)
        spy.fake.setSpeed(18f)
        advanceTimeBy(1_000); runCurrent()
        vm.onTripStop(); runCurrent()
        assertEquals("0 km/h", vm.telemetryState.value.maxText)
        assertEquals("00:00", vm.telemetryState.value.durationText)
        assertEquals(emptyList<ScooterCommand>(), spy.sent)
    }

    @Test fun `parar viagem com dados salva no historico com bateria e sem enviar comando`() = vmTest {
        val spy = Spy(); val repo = br.dev.evaldo.meuscooter.domain.InMemoryTripRepository()
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val vm = HomeViewModel(spy, Clock { testScheduler.currentTime }, null, repo, { 1_000_000L }) { "14:32" }.also { vmToCancel = it; runCurrent() }
        connected(vm); vm.onTelemetryOpened()
        spy.fake.setSpeed(18f)
        advanceTimeBy(1_000); runCurrent()
        vm.onTripStop(); runCurrent()
        val saved = repo.trips.first().single()
        assertEquals(1_000_000L, saved.startedAtMs)   // relógio de parede constante: o início é o instante da 1ª amostra
        assertEquals(82, saved.batteryStartPercent)
        assertEquals(18f, saved.maxKmh, 0.001f)
        assertEquals(1, vm.historyState.value.rows.size)
        assertEquals(emptyList<ScooterCommand>(), spy.sent)
    }

    /** Codex r1 #2. Telemetria congelada por 60 s e só então "Parar": o início salvo continua sendo o da 1ª amostra (dentro dos 900 ms da conexão), não "agora − duração". */
    @Test fun `parar depois de perder a telemetria preserva o inicio da viagem`() = vmTest {
        val spy = Spy(); val repo = br.dev.evaldo.meuscooter.domain.InMemoryTripRepository()
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val vm = HomeViewModel(spy, Clock { testScheduler.currentTime }, null, repo, { 5_000_000L + testScheduler.currentTime }) { "14:32" }.also { vmToCancel = it; runCurrent() }
        vm.onTelemetryOpened(); connected(vm)
        spy.fake.setSpeed(18f)
        advanceTimeBy(1_000); runCurrent()
        spy.fake.freezeTelemetry(true)
        advanceTimeBy(60_000); runCurrent()
        vm.onTripStop(); runCurrent()
        val saved = repo.trips.first().single()
        assertEquals(true, saved.startedAtMs in 5_000_000L..5_000_900L)
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

    @Test fun `Air Lock desconectado so guarda a preferencia e envia uma vez ao conectar depois de tocar`() = vmTest {
        val spy = Spy(); val vm = vmWith(spy)
        assertEquals(false, vm.preferences.value.lockWithPin)
        vm.onLockWithPin(true); runCurrent()
        assertEquals(true, vm.preferences.value.lockWithPin)
        assertEquals(true, vm.preferences.value.airLockTouched)
        assertEquals(emptyList<Boolean>(), spy.fake.airLockSent)
        connected(vm); advanceTimeBy(5_000); runCurrent()
        assertEquals(listOf(true), spy.fake.airLockSent)
        assertEquals("Comando aceito pelo patinete. Ele deve bloquear poucos segundos depois que o app desconectar.", vm.airLockNotice.value)
        vm.controller.disconnect(); advanceTimeBy(1_000); runCurrent()
        assertEquals(null, vm.airLockNotice.value)
    }

    @Test fun `Air Lock desligado aceito mostra aviso de que nao vai bloquear`() = vmTest {
        val spy = Spy(); val vm = vmWith(spy)
        vm.onLockWithPin(true); runCurrent()
        connected(vm); advanceTimeBy(5_000); runCurrent()
        vm.onLockWithPin(false); advanceTimeBy(5_000); runCurrent()
        assertEquals(listOf(true, false), spy.fake.airLockSent)
        assertEquals("Comando aceito pelo patinete. Ele não deve bloquear sozinho quando o app desconectar.", vm.airLockNotice.value)
    }

    @Test fun `Air Lock ao conectar sem nunca ter tocado nao escreve nada`() = vmTest {
        val spy = Spy(); val vm = vmWith(spy)
        assertEquals(false, vm.preferences.value.airLockTouched)
        connected(vm); advanceTimeBy(30_000); runCurrent()
        assertEquals(emptyList<Boolean>(), spy.fake.airLockSent)
    }

    @Test fun `Air Lock alternancia rapida durante a espera descarta o valor velho`() = vmTest {
        val spy = Spy(); val vm = vmWith(spy); connected(vm); advanceTimeBy(1_000); runCurrent()
        vm.onLockWithPin(true); vm.onLockWithPin(false); advanceTimeBy(10_000); runCurrent()
        assertEquals(false, spy.fake.airLockSent.last())
        assertEquals(false, vm.preferences.value.lockWithPin)
    }

    @Test fun `Air Lock conectado envia ao alternar e nao repete`() = vmTest {
        val spy = Spy(); val vm = vmWith(spy); connected(vm); advanceTimeBy(1_000); runCurrent()
        vm.onLockWithPin(true); advanceTimeBy(1_000); runCurrent()
        vm.onLockWithPin(false); advanceTimeBy(30_000); runCurrent()
        assertEquals(listOf(true, false), spy.fake.airLockSent)
    }

    @Test fun `Air Lock recusado ou sem ACK mostra aviso e nunca conclui`() = vmTest {
        val spy = Spy(); val vm = vmWith(spy); connected(vm); advanceTimeBy(1_000); runCurrent()
        spy.fake.failNextCommand("x"); vm.onLockWithPin(true); advanceTimeBy(1_000); runCurrent()
        assertEquals("O patinete recusou o ajuste de bloqueio ao desconectar.", vm.airLockNotice.value)
        spy.fake.ignoreNextCommand(); vm.onLockWithPin(false); advanceTimeBy(1_000); runCurrent()
        assertEquals("O patinete não confirmou o ajuste de bloqueio ao desconectar; o estado dele é desconhecido.", vm.airLockNotice.value)
    }

    @Test fun `Air Lock com o patinete em movimento nao e enviado e avisa`() = vmTest {
        val spy = Spy(); val vm = vmWith(spy); connected(vm); advanceTimeBy(1_000); runCurrent()
        spy.fake.setSpeed(20f)
        vm.onLockWithPin(true); advanceTimeBy(30_000); runCurrent()
        assertEquals(emptyList<Boolean>(), spy.fake.airLockSent)
        assertEquals("Ajuste de bloqueio ao desconectar não enviado: patinete em movimento. Pare e alterne de novo.", vm.airLockNotice.value)
    }

    @Test fun `Air Lock reenvia o valor a cada nova sessao depois de tocar`() = vmTest {
        val spy = Spy(); val vm = vmWith(spy); connected(vm); advanceTimeBy(1_000); runCurrent()
        vm.onLockWithPin(true); advanceTimeBy(1_000); runCurrent()
        vm.controller.disconnect(); advanceTimeBy(1_000); runCurrent()
        connected(vm); advanceTimeBy(5_000); runCurrent()
        assertEquals(listOf(true, true), spy.fake.airLockSent)
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

    @Test fun `esquecer patinete apaga o cadastro do MAC`() = vmTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = br.dev.evaldo.meuscooter.domain.InMemoryPreferencesStore(br.dev.evaldo.meuscooter.domain.Preferences(scooterAddress = "AA:BB:CC:DD:EE:FF", onboardingDone = true))
        val vm = HomeViewModel(Spy(), Clock { testScheduler.currentTime }, null, preferencesStore = store, timeLabel = { "14:32" }).also { vmToCancel = it; runCurrent() }
        vm.onForgetScooter(); runCurrent()
        assertEquals(null, store.current.scooterAddress)
        assertEquals(false, store.current.onboardingDone)
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

    // ---- GPS: velocidade só para exibição/viagem (ADR 0005) ----
    private fun TestScope.moving(spy: Spy) = spy.fake.freezeTelemetry(true).also {
        spy.injected.tryEmit(br.dev.evaldo.meuscooter.protocol.Telemetry(testScheduler.currentTime, locked = false, batteryPercent = 70, wheelMoving = true))
    }

    @Test fun `roda em movimento mostra a velocidade do GPS na Telemetria e a Home segue sem km por h`() = vmTest {
        val gps = kotlinx.coroutines.flow.MutableStateFlow<br.dev.evaldo.meuscooter.domain.GpsSpeedSample?>(null)
        val spy = Spy(relay = true); val vm = vm(spy, br.dev.evaldo.meuscooter.domain.GpsSpeedSource.of(gps)); connected(vm); vm.onTelemetryOpened(); vm.onSpeedSource(br.dev.evaldo.meuscooter.domain.SpeedSource.Gps)
        gps.value = br.dev.evaldo.meuscooter.domain.GpsSpeedSample(21.4f, testScheduler.currentTime)
        runCurrent(); moving(spy); runCurrent()
        assertEquals("21", vm.telemetryState.value.speedText)
        assertEquals("21 km/h", vm.telemetryState.value.maxText)
        assertEquals("--", vm.uiState.value.speed)
    }

    @Test fun `fonte da velocidade comeca no patinete, troca e fica lembrada nas preferencias`() = vmTest {
        val store = br.dev.evaldo.meuscooter.domain.InMemoryPreferencesStore()
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val vm = HomeViewModel(Spy(), Clock { testScheduler.currentTime }, null, preferencesStore = store, timeLabel = { "14:32" }).also { vmToCancel = it; runCurrent() }
        connected(vm)
        assertEquals(br.dev.evaldo.meuscooter.domain.SpeedSource.Scooter, vm.telemetryState.value.speedSource)
        vm.onSpeedSource(br.dev.evaldo.meuscooter.domain.SpeedSource.Gps); runCurrent()
        assertEquals(br.dev.evaldo.meuscooter.domain.SpeedSource.Gps, store.current.speedSource)
        assertEquals(br.dev.evaldo.meuscooter.domain.SpeedSource.Gps, vm.telemetryState.value.speedSource)
    }

    @Test fun `roda em movimento sem GPS mostra traco e nao inventa velocidade`() = vmTest {
        val spy = Spy(relay = true); val vm = vm(spy); connected(vm)
        moving(spy); runCurrent()
        assertEquals("--", vm.telemetryState.value.speedText)
    }

    @Test fun `roda parada mostra zero mesmo com GPS ruidoso`() = vmTest {
        val gps = kotlinx.coroutines.flow.MutableStateFlow<br.dev.evaldo.meuscooter.domain.GpsSpeedSample?>(null)
        val spy = Spy(relay = true); val vm = vm(spy, br.dev.evaldo.meuscooter.domain.GpsSpeedSource.of(gps)); connected(vm); vm.onSpeedSource(br.dev.evaldo.meuscooter.domain.SpeedSource.Gps)
        gps.value = br.dev.evaldo.meuscooter.domain.GpsSpeedSample(5f, testScheduler.currentTime)
        runCurrent(); spy.fake.freezeTelemetry(true)
        spy.injected.emit(br.dev.evaldo.meuscooter.protocol.Telemetry(testScheduler.currentTime, locked = false, batteryPercent = 70, wheelMoving = false))
        runCurrent()
        assertEquals("0", vm.telemetryState.value.speedText)
    }

    // ---- GPS nunca entra em decisão de segurança ----
    private fun gpsFlow() = kotlinx.coroutines.flow.MutableStateFlow<br.dev.evaldo.meuscooter.domain.GpsSpeedSample?>(null)

    @Test fun `GPS fresco com zero km por h e roda em movimento mantem Desbloquear desabilitado por Moving e nao envia`() = vmTest {
        val gps = gpsFlow()
        val spy = Spy(relay = true); val vm = vm(spy, br.dev.evaldo.meuscooter.domain.GpsSpeedSource.of(gps)); connected(vm)
        spy.fake.freezeTelemetry(true)
        gps.value = br.dev.evaldo.meuscooter.domain.GpsSpeedSample(0f, testScheduler.currentTime)
        spy.injected.emit(br.dev.evaldo.meuscooter.protocol.Telemetry(testScheduler.currentTime, locked = true, batteryPercent = 70, wheelMoving = true))
        runCurrent()
        val button = vm.uiState.value.mainButton
        assertEquals(MainButtonKind.Unlock, button.kind)
        assertEquals(false, button.enabled)
        assertEquals("Pare o patinete para alterar", button.disabledReason)
        vm.onMainButton(); runCurrent()
        assertEquals(emptyList<ScooterCommand>(), spy.sent)
    }

    @Test fun `roda parada com GPS de 50 km por h fresco mostra zero e nao marca Moving nem SpeedUnknown`() = vmTest {
        val gps = gpsFlow()
        val spy = Spy(relay = true); val vm = vm(spy, br.dev.evaldo.meuscooter.domain.GpsSpeedSource.of(gps)); connected(vm); vm.onSpeedSource(br.dev.evaldo.meuscooter.domain.SpeedSource.Gps)
        spy.fake.freezeTelemetry(true)
        gps.value = br.dev.evaldo.meuscooter.domain.GpsSpeedSample(50f, testScheduler.currentTime)
        spy.injected.emit(br.dev.evaldo.meuscooter.protocol.Telemetry(testScheduler.currentTime, locked = true, batteryPercent = 70, wheelMoving = false))
        runCurrent()
        assertEquals("0", vm.telemetryState.value.speedText)
        val button = vm.uiState.value.mainButton
        assertEquals(MainButtonKind.Unlock, button.kind)
        assertEquals(true, button.enabled)
        assertEquals(null, button.disabledReason)
    }

    @Test fun `o controlador recebe a velocidade crua e nunca a do GPS`() = vmTest {
        val gps = gpsFlow()
        val spy = Spy(relay = true); val vm = vm(spy, br.dev.evaldo.meuscooter.domain.GpsSpeedSource.of(gps)); connected(vm); vm.onSpeedSource(br.dev.evaldo.meuscooter.domain.SpeedSource.Gps)
        spy.fake.freezeTelemetry(true)
        gps.value = br.dev.evaldo.meuscooter.domain.GpsSpeedSample(21f, testScheduler.currentTime)
        spy.injected.emit(br.dev.evaldo.meuscooter.protocol.Telemetry(testScheduler.currentTime, locked = false, batteryPercent = 70, speedKmh = 7f, wheelMoving = true))
        runCurrent()
        assertEquals("21", vm.telemetryState.value.speedText)               // exibição usa o GPS
        assertEquals(7f, vm.controller.state.value.telemetry?.speedKmh)     // o controlador segue com o valor cru
    }

    @Test fun `GPS velho com roda em movimento mostra traco na Telemetria`() = vmTest {
        val gps = gpsFlow()
        val spy = Spy(relay = true); val vm = vm(spy, br.dev.evaldo.meuscooter.domain.GpsSpeedSource.of(gps)); connected(vm); vm.onSpeedSource(br.dev.evaldo.meuscooter.domain.SpeedSource.Gps)
        spy.fake.freezeTelemetry(true)
        gps.value = br.dev.evaldo.meuscooter.domain.GpsSpeedSample(21f, testScheduler.currentTime)
        runCurrent()
        advanceTimeBy(br.dev.evaldo.meuscooter.domain.GPS_MAX_AGE_MS + 500)
        spy.injected.emit(br.dev.evaldo.meuscooter.protocol.Telemetry(testScheduler.currentTime, locked = false, batteryPercent = 70, wheelMoving = true))
        runCurrent()
        assertEquals("--", vm.telemetryState.value.speedText)
    }

    // ---- S35: Play/Pause/Parar/Reset da viagem (local, sem comandos) ----
    private fun TestScope.vmTrip(
        spy: Spy,
        repo: br.dev.evaldo.meuscooter.domain.TripRepository,
        prefs: br.dev.evaldo.meuscooter.domain.PreferencesStore = br.dev.evaldo.meuscooter.domain.InMemoryPreferencesStore(),
        gps: br.dev.evaldo.meuscooter.domain.GpsSpeedSource = br.dev.evaldo.meuscooter.domain.GpsSpeedSource.None,
    ): HomeViewModel {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        return HomeViewModel(spy, Clock { testScheduler.currentTime }, null, repo, { 1_000_000L }, preferencesStore = prefs, gpsSpeedSource = gps) { "14:32" }.also { vmToCancel = it; runCurrent() }
    }

    @Test fun `onTripStop salva no repositorio mesmo com GPS 0 (patinete parado no cavalete)`() = vmTest {
        val gps = gpsFlow()
        val spy = Spy(relay = true)
        val repo = br.dev.evaldo.meuscooter.domain.InMemoryTripRepository()
        val vm = vmTrip(spy, repo, gps = br.dev.evaldo.meuscooter.domain.GpsSpeedSource.of(gps))
        connected(vm)
        vm.onTelemetryOpened(); runCurrent()
        spy.fake.freezeTelemetry(true)
        gps.value = br.dev.evaldo.meuscooter.domain.GpsSpeedSample(0f, testScheduler.currentTime)
        spy.injected.emit(br.dev.evaldo.meuscooter.protocol.Telemetry(testScheduler.currentTime, locked = false, batteryPercent = 80, wheelMoving = true, scooterSpeedKmh = 18f))
        runCurrent()
        advanceTimeBy(2_000)
        gps.value = br.dev.evaldo.meuscooter.domain.GpsSpeedSample(0f, testScheduler.currentTime)
        spy.injected.emit(br.dev.evaldo.meuscooter.protocol.Telemetry(testScheduler.currentTime, locked = false, batteryPercent = 79, wheelMoving = true, scooterSpeedKmh = 18f))
        runCurrent()

        vm.onTripStop(); runCurrent()

        assertEquals(1, repo.trips.first().size)
        val saved = repo.trips.first().first()
        assertEquals(0f, saved.distanceKm, 0.0001f)
        assertEquals(2_000L, saved.durationMs)
        assertEquals(0f, saved.avgKmh, 0.0001f)
        assertEquals(0f, saved.maxKmh, 0.0001f)
        assertEquals("Parada", vm.telemetryState.value.recStateText)
        assertEquals("00:00", vm.telemetryState.value.durationText)
    }

    @Test fun `viagem acumula distancia com a velocidade do patinete sem GPS e o velocimetro da face GPS segue traco`() = vmTest {
        val spy = Spy(relay = true)
        val repo = br.dev.evaldo.meuscooter.domain.InMemoryTripRepository()
        val vm = vmTrip(spy, repo)   // sem GPS
        connected(vm)
        vm.onTelemetryOpened(); runCurrent()
        vm.onSpeedSource(br.dev.evaldo.meuscooter.domain.SpeedSource.Gps); runCurrent()
        spy.fake.freezeTelemetry(true)
        for (i in 0..3) {
            spy.injected.emit(br.dev.evaldo.meuscooter.protocol.Telemetry(testScheduler.currentTime, locked = false, batteryPercent = 80, wheelMoving = true, scooterSpeedKmh = 18f))
            runCurrent()
            advanceTimeBy(1_000)
        }
        assertEquals("--", vm.telemetryState.value.speedText)
        vm.onTripStop(); runCurrent()
        val saved = repo.trips.first().single()
        // Quatro amostras a cada 1 s: 18 km/h × 3 s = 0,015 km, média 18 km/h.
        assertEquals(0.015f, saved.distanceKm, 0.000001f)
        assertEquals(3_000L, saved.durationMs)
        assertEquals(18f, saved.avgKmh, 0.0001f)
        assertEquals(18f, saved.maxKmh, 0.01f)
    }

    @Test fun `viagem nao renova velocidade do Meter1 com carimbos novos apenas do Meter2`() = vmTest {
        // connected() termina em 900 ms; só esse Meter1 é válido, os quadros seguintes renovam Meter2.
        val spy = Spy(relay = true, meter1FrozenAtMs = 900L)
        val repo = br.dev.evaldo.meuscooter.domain.InMemoryTripRepository()
        val vm = vmTrip(spy, repo)
        connected(vm)
        assertEquals(900L, testScheduler.currentTime)
        vm.onTelemetryOpened(); runCurrent()
        spy.fake.freezeTelemetry(true)
        for (i in 0..5) {
            spy.injected.emit(br.dev.evaldo.meuscooter.protocol.Telemetry(testScheduler.currentTime, locked = false, wheelMoving = true, scooterSpeedKmh = 18f))
            runCurrent()
            advanceTimeBy(1_000)
        }
        vm.onTripStop(); runCurrent()
        val saved = repo.trips.first().single()
        // Só 900→1900→2900 ms: 18 km/h × 2 s = 0,010 km; depois não há amostra de velocidade.
        assertEquals(0.010f, saved.distanceKm, 0.000001f)
        assertEquals(2_000L, saved.durationMs)
        assertEquals(18f, saved.avgKmh, 0.0001f)
        assertEquals(18f, saved.maxKmh, 0.0001f)
    }

    @Test fun `onTripStop com duracao zero ou sem amostras nao grava no repositorio`() = vmTest {
        val spy = Spy()
        val repo = br.dev.evaldo.meuscooter.domain.InMemoryTripRepository()
        val vm = vmTrip(spy, repo)
        connected(vm)
        // Sem abrir Telemetria:
        vm.onTripStop(); runCurrent()
        assertEquals(0, repo.trips.first().size)

        // Telemetria aberta, mas parada logo com 1 amostra só (duração 0):
        vm.onTelemetryOpened(); runCurrent()
        spy.fake.freezeTelemetry(true)
        spy.injected.emit(br.dev.evaldo.meuscooter.protocol.Telemetry(testScheduler.currentTime, locked = false, batteryPercent = 80, wheelMoving = true))
        runCurrent()
        vm.onTripStop(); runCurrent()
        assertEquals(0, repo.trips.first().size)
    }

    @Test fun `viagem abre contando por padrao e Play Pause alterna e congela a duracao`() = vmTest {
        val spy = Spy(); val vm = vmTrip(spy, br.dev.evaldo.meuscooter.domain.InMemoryTripRepository()); connected(vm)
        assertEquals(br.dev.evaldo.meuscooter.domain.TripRecState.Stopped, vm.tripRecState.value)   // antes de abrir a Telemetria nada conta
        vm.onTelemetryOpened(); runCurrent()
        assertEquals(br.dev.evaldo.meuscooter.domain.TripRecState.Running, vm.tripRecState.value)
        spy.fake.setSpeed(18f)
        advanceTimeBy(3_000); runCurrent()
        vm.onTripPlayPause(); runCurrent()
        assertEquals(br.dev.evaldo.meuscooter.domain.TripRecState.Paused, vm.tripRecState.value)
        assertEquals("Pausada", vm.telemetryState.value.recStateText)
        val frozen = vm.telemetryState.value.durationText
        advanceTimeBy(5_000); runCurrent()
        assertEquals(frozen, vm.telemetryState.value.durationText)
        vm.onTripPlayPause(); runCurrent()
        assertEquals("Contando", vm.telemetryState.value.recStateText)
        assertEquals(emptyList<ScooterCommand>(), spy.sent)
    }

    @Test fun `Parar salva no historico e fica parada em zero, Play comeca viagem nova`() = vmTest {
        val spy = Spy(); val repo = br.dev.evaldo.meuscooter.domain.InMemoryTripRepository()
        val vm = vmTrip(spy, repo); connected(vm)
        vm.onTelemetryOpened(); runCurrent()
        spy.fake.setSpeed(18f)
        advanceTimeBy(2_000); runCurrent()
        vm.onTripStop(); runCurrent()
        assertEquals(1, repo.trips.first().size)
        assertEquals("Parada", vm.telemetryState.value.recStateText)
        assertEquals("00:00", vm.telemetryState.value.durationText)
        advanceTimeBy(3_000); runCurrent()
        assertEquals("00:00", vm.telemetryState.value.durationText)   // parada: nada acumula
        vm.onTripPlayPause(); runCurrent()
        advanceTimeBy(2_000); runCurrent()
        assertEquals(false, vm.telemetryState.value.durationText == "00:00")
        assertEquals(1, repo.trips.first().size)
        assertEquals(emptyList<ScooterCommand>(), spy.sent)
    }

    @Test fun `Reset descarta sem salvar e fica parada`() = vmTest {
        val spy = Spy(); val repo = br.dev.evaldo.meuscooter.domain.InMemoryTripRepository()
        val vm = vmTrip(spy, repo); connected(vm)
        vm.onTelemetryOpened(); runCurrent()
        spy.fake.setSpeed(18f)
        advanceTimeBy(2_000); runCurrent()
        vm.onTripReset(); runCurrent()
        assertEquals(true, repo.trips.first().isEmpty())
        assertEquals(br.dev.evaldo.meuscooter.domain.TripRecState.Stopped, vm.tripRecState.value)
        assertEquals("00:00", vm.telemetryState.value.durationText)
        assertEquals(emptyList<ScooterCommand>(), spy.sent)
    }

    @Test fun `com a preferencia desligada a Telemetria abre parada ate o Play`() = vmTest {
        val spy = Spy()
        val prefs = br.dev.evaldo.meuscooter.domain.InMemoryPreferencesStore(br.dev.evaldo.meuscooter.domain.Preferences(startTripOnOpen = false))
        val vm = vmTrip(spy, br.dev.evaldo.meuscooter.domain.InMemoryTripRepository(), prefs); connected(vm)
        vm.onTelemetryOpened(); runCurrent()
        spy.fake.setSpeed(18f)
        advanceTimeBy(3_000); runCurrent()
        assertEquals(br.dev.evaldo.meuscooter.domain.TripRecState.Stopped, vm.tripRecState.value)
        assertEquals("00:00", vm.telemetryState.value.durationText)
        vm.onTripPlayPause(); runCurrent()
        advanceTimeBy(2_000); runCurrent()
        assertEquals(false, vm.telemetryState.value.durationText == "00:00")
    }

    @Test fun `mudar a preferencia vale na proxima abertura da Telemetria`() = vmTest {
        val vm = vmTrip(Spy(), br.dev.evaldo.meuscooter.domain.InMemoryTripRepository(), br.dev.evaldo.meuscooter.domain.InMemoryPreferencesStore(br.dev.evaldo.meuscooter.domain.Preferences(startTripOnOpen = false)))
        vm.onTelemetryOpened(); runCurrent()
        assertEquals(br.dev.evaldo.meuscooter.domain.TripRecState.Stopped, vm.tripRecState.value)
        vm.onStartTripOnOpen(true); runCurrent()
        assertEquals(br.dev.evaldo.meuscooter.domain.TripRecState.Stopped, vm.tripRecState.value)   // só a próxima abertura
        vm.onTelemetryOpened(); runCurrent()
        assertEquals(br.dev.evaldo.meuscooter.domain.TripRecState.Running, vm.tripRecState.value)
        vm.onTripStop(); runCurrent()
        vm.onStartTripOnOpen(false); runCurrent()
        vm.onTelemetryOpened(); runCurrent()
        assertEquals(br.dev.evaldo.meuscooter.domain.TripRecState.Stopped, vm.tripRecState.value)
    }

    @Test fun `reabrir a Telemetria depois de Parar reinicia a viagem`() = vmTest {
        val vm = vmTrip(Spy(), br.dev.evaldo.meuscooter.domain.InMemoryTripRepository())
        vm.onTelemetryOpened(); vm.onTripStop(); runCurrent()
        assertEquals(br.dev.evaldo.meuscooter.domain.TripRecState.Stopped, vm.tripRecState.value)
        vm.onTelemetryOpened(); runCurrent()
        assertEquals(br.dev.evaldo.meuscooter.domain.TripRecState.Running, vm.tripRecState.value)
    }

    @Test fun `abrir com a viagem ja contando ou pausada nao altera o estado`() = vmTest {
        val vm = vmTrip(Spy(), br.dev.evaldo.meuscooter.domain.InMemoryTripRepository())
        vm.onTelemetryOpened(); runCurrent()
        vm.onTelemetryOpened(); runCurrent()
        assertEquals(br.dev.evaldo.meuscooter.domain.TripRecState.Running, vm.tripRecState.value)
        vm.onTripPlayPause(); runCurrent()
        vm.onTelemetryOpened(); runCurrent()
        assertEquals(br.dev.evaldo.meuscooter.domain.TripRecState.Paused, vm.tripRecState.value)
    }

    @Test fun `preferencia startTripOnOpen alterna pelo ViewModel`() = vmTest {
        val vm = vmWith()
        assertEquals(true, vm.preferences.value.startTripOnOpen)
        vm.onStartTripOnOpen(false); runCurrent()
        assertEquals(false, vm.preferences.value.startTripOnOpen)
    }

}

/** Chip de conexão da Home: tocar reconecta sem sair da Home; Conectando preso é derrubado por um vigia. */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelConnectionChipTest {
    /** Primeira `connect()` fica presa em Connecting (como um GATT que não responde); as seguintes conectam. */
    private class Stuck(private val stuckFirst: Boolean = true) : (kotlinx.coroutines.CoroutineScope, Clock) -> ScooterTransport {
        var connects = 0
        var disconnects = 0
        private val status = kotlinx.coroutines.flow.MutableStateFlow(br.dev.evaldo.meuscooter.protocol.ConnectionStatus.Disconnected)
        override fun invoke(scope: kotlinx.coroutines.CoroutineScope, clock: Clock): ScooterTransport {
            val fake = FakeScooterTransport(scope, clock)
            return object : ScooterTransport by fake {
                override val connection: kotlinx.coroutines.flow.StateFlow<br.dev.evaldo.meuscooter.protocol.ConnectionStatus> = status
                override suspend fun connect() {
                    if (status.value != br.dev.evaldo.meuscooter.protocol.ConnectionStatus.Disconnected) return
                    connects++
                    status.value = br.dev.evaldo.meuscooter.protocol.ConnectionStatus.Connecting
                    if (stuckFirst && connects == 1) kotlinx.coroutines.awaitCancellation()
                    status.value = br.dev.evaldo.meuscooter.protocol.ConnectionStatus.Connected
                }
                override suspend fun disconnect() { disconnects++; status.value = br.dev.evaldo.meuscooter.protocol.ConnectionStatus.Disconnected }
            }
        }
    }

    private var vmToCancel: HomeViewModel? = null
    @After fun tearDown() { Dispatchers.resetMain() }
    private fun chipTest(body: suspend TestScope.() -> Unit) = runTest { try { body() } finally { vmToCancel?.viewModelScope?.cancel() } }
    private fun TestScope.vm(t: Stuck): HomeViewModel {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        return HomeViewModel(t, Clock { testScheduler.currentTime }, deviceName = null, timeLabel = { "14:32" }).also { vmToCancel = it; runCurrent() }
    }
    private fun HomeViewModel.status() = controller.state.value.connection

    @Test fun `tocar no chip desconectado reconecta sem sair da Home`() = chipTest {
        val t = Stuck(stuckFirst = false); val vm = vm(t)
        vm.onConnectionChip(); runCurrent()
        assertEquals(br.dev.evaldo.meuscooter.protocol.ConnectionStatus.Connected, vm.status())
    }

    @Test fun `tocar no chip com Conectando preso cancela e tenta de novo`() = chipTest {
        val t = Stuck(); val vm = vm(t)
        vm.onConnectionChip(); runCurrent()
        assertEquals(br.dev.evaldo.meuscooter.protocol.ConnectionStatus.Connecting, vm.status())
        vm.onConnectionChip(); runCurrent()
        assertEquals(br.dev.evaldo.meuscooter.protocol.ConnectionStatus.Connected, vm.status())
        assertEquals(2, t.connects)
    }

    @Test fun `Conectando que passa do prazo volta a Desconectado sozinho`() = chipTest {
        val t = Stuck(); val vm = vm(t)
        vm.onConnectionChip(); runCurrent()
        advanceTimeBy(HomeViewModel.CONNECTING_WATCHDOG_MS - 1); runCurrent()
        assertEquals(br.dev.evaldo.meuscooter.protocol.ConnectionStatus.Connecting, vm.status())
        advanceTimeBy(2); runCurrent()
        assertEquals(br.dev.evaldo.meuscooter.protocol.ConnectionStatus.Disconnected, vm.status())
        assertEquals(MainButtonKind.Search, vm.uiState.value.mainButton.kind)
    }

    @Test fun `tocar no chip ja conectado nao faz nada e o vigia nao derruba conexao legitima`() = chipTest {
        val t = Stuck(stuckFirst = false); val vm = vm(t)
        vm.onConnectionChip(); runCurrent()
        vm.onConnectionChip(); runCurrent()
        assertEquals(1, t.connects)
        advanceTimeBy(HomeViewModel.CONNECTING_WATCHDOG_MS * 2); runCurrent()
        assertEquals(br.dev.evaldo.meuscooter.protocol.ConnectionStatus.Connected, vm.status())
        assertEquals(0, t.disconnects)
    }

    @Test fun `toques repetidos no chip durante Conectando nao reiniciam a tentativa`() = chipTest {
        val t = Stuck(); val vm = vm(t)
        vm.onConnectionChip(); runCurrent()
        vm.onConnectionChip(); vm.onConnectionChip(); runCurrent()
        assertEquals(2, t.connects)
        assertEquals(1, t.disconnects)
    }

    @Test fun `sem Bluetooth o chip nao tenta conectar`() = chipTest {
        val t = Stuck(stuckFirst = false)
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val vm = HomeViewModel(t, Clock { testScheduler.currentTime }, deviceName = null, timeLabel = { "14:32" },
            bluetoothEnvironment = br.dev.evaldo.meuscooter.data.SimulatedBluetoothEnvironment(bluetoothEnabled = false)).also { vmToCancel = it; runCurrent() }
        vm.onConnectionChip(); runCurrent()
        assertEquals(0, t.connects)
    }
}

/** F5.3: reconexão automática ao cair o enlace (tempo virtual). */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelAutoReconnectTest {
    private class Link(var failConnects: Int = 0, var connectMs: Long = 100) : (kotlinx.coroutines.CoroutineScope, Clock) -> ScooterTransport {
        var connects = 0
        val status = kotlinx.coroutines.flow.MutableStateFlow(br.dev.evaldo.meuscooter.protocol.ConnectionStatus.Disconnected)
        override fun invoke(scope: kotlinx.coroutines.CoroutineScope, clock: Clock): ScooterTransport {
            val fake = FakeScooterTransport(scope, clock)
            return object : ScooterTransport by fake {
                override val connection: kotlinx.coroutines.flow.StateFlow<br.dev.evaldo.meuscooter.protocol.ConnectionStatus> = status
                override suspend fun connect() {
                    if (status.value != br.dev.evaldo.meuscooter.protocol.ConnectionStatus.Disconnected) return
                    connects++
                    status.value = br.dev.evaldo.meuscooter.protocol.ConnectionStatus.Connecting
                    kotlinx.coroutines.delay(connectMs)
                    status.value = if (failConnects > 0) { failConnects--; br.dev.evaldo.meuscooter.protocol.ConnectionStatus.Disconnected } else br.dev.evaldo.meuscooter.protocol.ConnectionStatus.Connected
                }
                override suspend fun disconnect() { status.value = br.dev.evaldo.meuscooter.protocol.ConnectionStatus.Disconnected }
            }
        }
    }

    private var vmToCancel: HomeViewModel? = null
    @After fun tearDown() { Dispatchers.resetMain() }
    private fun arTest(body: suspend TestScope.() -> Unit) = runTest { try { body() } finally { vmToCancel?.viewModelScope?.cancel() } }
    private fun TestScope.vm(t: Link, auto: Boolean = true, connectOnStart: Boolean = false, env: br.dev.evaldo.meuscooter.domain.BluetoothEnvironment? = null): HomeViewModel {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = br.dev.evaldo.meuscooter.domain.InMemoryPreferencesStore(br.dev.evaldo.meuscooter.domain.Preferences(autoReconnect = auto))
        return if (env == null) HomeViewModel(t, Clock { testScheduler.currentTime }, deviceName = null, preferencesStore = store, connectOnStart = connectOnStart, timeLabel = { "14:32" }).also { vmToCancel = it; runCurrent() }
        else HomeViewModel(t, Clock { testScheduler.currentTime }, deviceName = null, preferencesStore = store, bluetoothEnvironment = env, connectOnStart = connectOnStart, timeLabel = { "14:32" }).also { vmToCancel = it; runCurrent() }
    }
    private fun HomeViewModel.status() = controller.state.value.connection
    private suspend fun TestScope.connectFirst(vm: HomeViewModel) { vm.onConnectionChip(); advanceTimeBy(200); runCurrent() }
    private val D = HomeViewModel.AUTO_RECONNECT_DELAYS_MS

    @Test fun `queda do enlace reconecta depois do primeiro atraso`() = arTest {
        val t = Link(); val vm = vm(t); connectFirst(vm)
        t.status.value = br.dev.evaldo.meuscooter.protocol.ConnectionStatus.Disconnected; runCurrent()
        advanceTimeBy(D[0] - 1); runCurrent()
        assertEquals(1, t.connects)
        advanceTimeBy(300); runCurrent()
        assertEquals(2, t.connects)
        assertEquals(br.dev.evaldo.meuscooter.protocol.ConnectionStatus.Connected, vm.status())
    }

    /** S29 (defeito): Home com patinete cadastrado abria "Desconectado" e nada conectava sozinho (não havia connect inicial). */
    @Test fun `com patinete cadastrado conecta sozinho ao abrir`() = arTest {
        val t = Link(); val vm = vm(t, connectOnStart = true)
        advanceTimeBy(200); runCurrent()
        assertEquals(1, t.connects)
        assertEquals(br.dev.evaldo.meuscooter.protocol.ConnectionStatus.Connected, vm.status())
    }

    @Test fun `sem connectOnStart nada conecta sozinho`() = arTest {
        val t = Link(); val vm = vm(t)
        advanceTimeBy(D.sum()); runCurrent()
        assertEquals(0, t.connects)
        assertEquals(br.dev.evaldo.meuscooter.protocol.ConnectionStatus.Disconnected, vm.status())
    }

    @Test fun `connect inicial respeita reconexao automatica desligada`() = arTest {
        val t = Link(); vm(t, auto = false, connectOnStart = true)
        advanceTimeBy(D.sum()); runCurrent()
        assertEquals(0, t.connects)
    }

    @Test fun `connect inicial que falha tenta de novo com backoff limitado`() = arTest {
        val t = Link(failConnects = 100); val vm = vm(t, connectOnStart = true)
        advanceTimeBy(D.sum() + 10_000); runCurrent()
        assertEquals(1 + D.size, t.connects)
        advanceTimeBy(D.sum() * 3); runCurrent()
        assertEquals(1 + D.size, t.connects)
        assertEquals(br.dev.evaldo.meuscooter.protocol.ConnectionStatus.Disconnected, vm.status())
    }

    @Test fun `connect inicial espera Bluetooth e permissao prontos`() = arTest {
        val enabled = kotlinx.coroutines.flow.MutableStateFlow(false)
        val env = object : br.dev.evaldo.meuscooter.domain.BluetoothEnvironment {
            override val bluetoothEnabled = enabled
            override val permissionGranted = kotlinx.coroutines.flow.MutableStateFlow(true)
            override fun requestEnableBluetooth() = Unit
            override fun requestPermission() = Unit
            override fun openSystemSettings() = Unit
        }
        val t = Link(); vm(t, connectOnStart = true, env = env)
        advanceTimeBy(5_000); runCurrent()
        assertEquals(0, t.connects)
        enabled.value = true; advanceTimeBy(300); runCurrent()
        assertEquals(1, t.connects)
    }

    @Test fun `esquecer o patinete interrompe as tentativas do connect inicial`() = arTest {
        val t = Link(failConnects = 100); val vm = vm(t, connectOnStart = true)
        advanceTimeBy(200); runCurrent()
        vm.onForgetScooter(); runCurrent()
        advanceTimeBy(D.sum() * 2); runCurrent()
        assertEquals(1, t.connects)
    }

    // ---- Menu do chip (pressionar e segurar, S29) ----

    @Test fun `opcao do menu por estado de conexao`() {
        assertEquals(ConnectionMenuAction.Disconnect, ConnectionMenuAction.forState(ConnectionKind.Connected))
        assertEquals(ConnectionMenuAction.Connect, ConnectionMenuAction.forState(ConnectionKind.Disconnected))
        assertEquals(ConnectionMenuAction.Connect, ConnectionMenuAction.forState(ConnectionKind.Error))
        assertEquals(ConnectionMenuAction.Cancel, ConnectionMenuAction.forState(ConnectionKind.Connecting))
        assertEquals("Desconectar patinete", ConnectionMenuAction.Disconnect.label)
        assertEquals("Conectar patinete", ConnectionMenuAction.Connect.label)
        assertEquals("Cancelar conexão", ConnectionMenuAction.Cancel.label)
        assertEquals("Deseja desconectar o patinete?", ConnectionMenuAction.Disconnect.question)
        assertEquals("Deseja conectar o patinete?", ConnectionMenuAction.Connect.question)
        assertEquals("Deseja cancelar a conexão?", ConnectionMenuAction.Cancel.question)
    }

    @Test fun `desconectar pelo menu desconecta e a vigia de queda NAO reconecta`() = arTest {
        val t = Link(); val vm = vm(t); connectFirst(vm)
        vm.onConnectionMenu(ConnectionMenuAction.Disconnect); runCurrent()
        assertEquals(br.dev.evaldo.meuscooter.protocol.ConnectionStatus.Disconnected, vm.status())
        advanceTimeBy(D.sum() * 2); runCurrent()
        assertEquals(1, t.connects)
        assertEquals(br.dev.evaldo.meuscooter.protocol.ConnectionStatus.Disconnected, vm.status())
    }

    @Test fun `conectar pelo menu conecta e uma queda posterior volta a reconectar`() = arTest {
        val t = Link(); val vm = vm(t); connectFirst(vm)
        vm.onConnectionMenu(ConnectionMenuAction.Disconnect); runCurrent()
        vm.onConnectionMenu(ConnectionMenuAction.Connect); advanceTimeBy(200); runCurrent()
        assertEquals(br.dev.evaldo.meuscooter.protocol.ConnectionStatus.Connected, vm.status())
        t.status.value = br.dev.evaldo.meuscooter.protocol.ConnectionStatus.Disconnected; runCurrent()   // queda real depois do "Conectar"
        advanceTimeBy(D[0] + 300); runCurrent()
        assertEquals(3, t.connects)
    }

    @Test fun `cancelar conexao pelo menu para a tentativa sem reconectar`() = arTest {
        val t = Link(connectMs = 30_000); val vm = vm(t)
        vm.onConnectionChip(); advanceTimeBy(100); runCurrent()
        assertEquals(br.dev.evaldo.meuscooter.protocol.ConnectionStatus.Connecting, vm.status())
        vm.onConnectionMenu(ConnectionMenuAction.Cancel); runCurrent()
        advanceTimeBy(D.sum() * 2); runCurrent()
        assertEquals(br.dev.evaldo.meuscooter.protocol.ConnectionStatus.Disconnected, vm.status())
        assertEquals(1, t.connects)
    }

    @Test fun `cancelar em Conectando e a conexao completar mesmo assim desfaz sem reconectar`() = arTest {
        val t = Link(connectMs = 30_000); val vm = vm(t)
        vm.onConnectionChip(); advanceTimeBy(100); runCurrent()
        vm.onConnectionMenu(ConnectionMenuAction.Cancel)
        // o disconnect() "demora": a conexão completa antes de valer
        t.status.value = br.dev.evaldo.meuscooter.protocol.ConnectionStatus.Connected; runCurrent()
        advanceTimeBy(D.sum() * 2); runCurrent()
        assertEquals(br.dev.evaldo.meuscooter.protocol.ConnectionStatus.Disconnected, vm.status())
        assertEquals(1, t.connects)
    }

    @Test fun `queda real depois de nova conexao pedida pelo usuario volta a reconectar`() = arTest {
        val t = Link(connectMs = 30_000); val vm = vm(t)
        vm.onConnectionChip(); advanceTimeBy(100); runCurrent()
        vm.onConnectionMenu(ConnectionMenuAction.Cancel); runCurrent()
        advanceTimeBy(100); runCurrent()
        t.connectMs = 100
        vm.onConnectionMenu(ConnectionMenuAction.Connect); advanceTimeBy(300); runCurrent()
        assertEquals(br.dev.evaldo.meuscooter.protocol.ConnectionStatus.Connected, vm.status())
        t.status.value = br.dev.evaldo.meuscooter.protocol.ConnectionStatus.Disconnected; runCurrent()
        advanceTimeBy(D[0] + 300); runCurrent()
        assertEquals(3, t.connects)
    }

    @Test fun `cancelar pelo menu tambem para a recuperacao em curso`() = arTest {
        val t = Link(connectMs = 30_000); val vm = vm(t)
        vm.startRecovery(); advanceTimeBy(100); runCurrent()
        assertEquals(br.dev.evaldo.meuscooter.protocol.ConnectionStatus.Connecting, vm.status())
        vm.onConnectionMenu(ConnectionMenuAction.Cancel); runCurrent()
        advanceTimeBy(D.sum() * 2); runCurrent()
        assertEquals(null, vm.recovery.value)
        assertEquals(1, t.connects)
    }

    @Test fun `conectar pelo menu exige Bluetooth e permissao como o chip`() = arTest {
        val env = object : br.dev.evaldo.meuscooter.domain.BluetoothEnvironment {
            override val bluetoothEnabled = kotlinx.coroutines.flow.MutableStateFlow(false)
            override val permissionGranted = kotlinx.coroutines.flow.MutableStateFlow(true)
            override fun requestEnableBluetooth() = Unit
            override fun requestPermission() = Unit
            override fun openSystemSettings() = Unit
        }
        val t = Link(); val vm = vm(t, env = env)
        vm.onConnectionMenu(ConnectionMenuAction.Connect); advanceTimeBy(500); runCurrent()
        assertEquals(0, t.connects)
    }

    @Test fun `desconectar pelo menu sem estar conectado nao faz nada`() = arTest {
        val t = Link(); val vm = vm(t)
        vm.onConnectionMenu(ConnectionMenuAction.Disconnect); runCurrent()
        assertEquals(0, t.connects)
        assertEquals(br.dev.evaldo.meuscooter.protocol.ConnectionStatus.Disconnected, vm.status())
    }

    @Test fun `com reconexao automatica desligada nao reconecta`() = arTest {
        val t = Link(); val vm = vm(t, auto = false); connectFirst(vm)
        t.status.value = br.dev.evaldo.meuscooter.protocol.ConnectionStatus.Disconnected; runCurrent()
        advanceTimeBy(D.sum() * 2); runCurrent()
        assertEquals(1, t.connects)
    }

    @Test fun `esquecer o patinete nao dispara reconexao`() = arTest {
        val t = Link(); val vm = vm(t); connectFirst(vm)
        vm.onForgetScooter(); runCurrent()
        advanceTimeBy(D.sum() * 2); runCurrent()
        assertEquals(1, t.connects)
    }

    @Test fun `tentativas sao limitadas e param ao esgotar`() = arTest {
        val t = Link(); val vm = vm(t); connectFirst(vm)
        t.failConnects = 100
        t.status.value = br.dev.evaldo.meuscooter.protocol.ConnectionStatus.Disconnected; runCurrent()
        advanceTimeBy(D.sum() + 10_000); runCurrent()
        assertEquals(1 + D.size, t.connects)
        advanceTimeBy(D.sum() * 3); runCurrent()
        assertEquals(1 + D.size, t.connects)
        assertEquals(br.dev.evaldo.meuscooter.protocol.ConnectionStatus.Disconnected, vm.status())
    }

    @Test fun `falha seguida de sucesso para de tentar`() = arTest {
        val t = Link(); val vm = vm(t); connectFirst(vm)
        t.failConnects = 1
        t.status.value = br.dev.evaldo.meuscooter.protocol.ConnectionStatus.Disconnected; runCurrent()
        advanceTimeBy(D.sum()); runCurrent()
        assertEquals(3, t.connects)
        assertEquals(br.dev.evaldo.meuscooter.protocol.ConnectionStatus.Connected, vm.status())
    }

    @Test fun `connect lento que falha nao consome as tentativas seguintes`() = arTest {
        val t = Link(); val vm = vm(t); connectFirst(vm)
        t.failConnects = 100; t.connectMs = 15_000
        t.status.value = br.dev.evaldo.meuscooter.protocol.ConnectionStatus.Disconnected; runCurrent()
        advanceTimeBy(D.sum() + D.size * 15_000L + 10_000); runCurrent()
        assertEquals(1 + D.size, t.connects)
    }
}

package br.dev.evaldo.meuscooter.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import br.dev.evaldo.meuscooter.domain.BluetoothEnvironment
import br.dev.evaldo.meuscooter.domain.BluetoothState
import br.dev.evaldo.meuscooter.domain.HomeController
import br.dev.evaldo.meuscooter.domain.OnboardingFlow
import br.dev.evaldo.meuscooter.domain.OnboardingStep
import br.dev.evaldo.meuscooter.domain.RecoveryReason
import br.dev.evaldo.meuscooter.domain.InMemoryPreferencesStore
import br.dev.evaldo.meuscooter.domain.InMemoryTripRepository
import br.dev.evaldo.meuscooter.domain.Preferences
import br.dev.evaldo.meuscooter.domain.PreferencesStore
import br.dev.evaldo.meuscooter.domain.ThemeMode
import br.dev.evaldo.meuscooter.domain.Units
import br.dev.evaldo.meuscooter.domain.Trip
import br.dev.evaldo.meuscooter.domain.TripRepository
import br.dev.evaldo.meuscooter.domain.TripSummary
import br.dev.evaldo.meuscooter.domain.TripTracker
import br.dev.evaldo.meuscooter.protocol.Clock
import br.dev.evaldo.meuscooter.domain.HomeRules
import br.dev.evaldo.meuscooter.domain.GpsSpeedSource
import br.dev.evaldo.meuscooter.domain.withGpsSpeed
import br.dev.evaldo.meuscooter.protocol.ConnectionStatus
import br.dev.evaldo.meuscooter.protocol.Pin
import br.dev.evaldo.meuscooter.protocol.ScooterCommand
import br.dev.evaldo.meuscooter.protocol.ScooterTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.launch

/**
 * Envolve [HomeController] em `viewModelScope`. Nenhuma chamada bloqueia a thread da UI;
 * a decisão de enviar (ou ignorar) é do reducer do domínio.
 */
class HomeViewModel(
    transportFactory: (CoroutineScope, Clock) -> ScooterTransport,
    clock: Clock,
    deviceName: String?,
    private val tripRepository: TripRepository = InMemoryTripRepository(),
    private val wallClockMs: () -> Long = { System.currentTimeMillis() },
    private val preferencesStore: PreferencesStore = InMemoryPreferencesStore(),
    private val bluetoothEnvironment: BluetoothEnvironment = ReadyBluetoothEnvironment,
    private val zone: java.time.ZoneId = java.time.ZoneId.systemDefault(),
    gpsSpeedSource: GpsSpeedSource = GpsSpeedSource.None,
    timeLabel: (Long) -> String,
) : ViewModel() {
    private val transport = transportFactory(viewModelScope, clock)
    /** `internal` só para testes verificarem que o controlador recebe a telemetria CRUA (sem GPS). */
    internal val controller = HomeController(transport, viewModelScope, clock)
    private val mapper = HomeUiMapper(transport.speedModes.options(), deviceName, timeLabel)

    val preferences: StateFlow<Preferences> = preferencesStore.preferences.stateIn(viewModelScope, SharingStarted.Eagerly, preferencesStore.current)

    val uiState: StateFlow<HomeUiState> = combine(controller.state, preferences) { s, p -> mapper.map(s, p.units) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, mapper.map(controller.state.value))

    private val tripTracker = TripTracker()
    private var tripStartWallMs = 0L
    private val trip = MutableStateFlow<TripSummary?>(null)
    private val telemetryMapper = TelemetryUiMapper()

    /** Última velocidade do GPS. SÓ exibição/viagem: reducer, controlador e transporte nunca a veem (ADR 0005). */
    private val gps = gpsSpeedSource.samples.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    init {
        viewModelScope.launch {
            controller.state.mapNotNull { it.telemetry }.distinctUntilChanged { a, b -> a.receivedAtMs == b.receivedAtMs }
                .collect {
                    if (tripTracker.current == null) tripStartWallMs = wallClockMs()   // instante (relógio de parede) da 1ª amostra
                    tripTracker.onTelemetry(it.withGpsSpeed(gps.value, it.receivedAtMs)); trip.value = tripTracker.current
                }
        }
    }

    val telemetryState: StateFlow<TelemetryUiState> =
        combine(controller.state, trip, preferences, gps) { s, tr, p, g ->
            val t = s.telemetry?.withGpsSpeed(g, s.nowMs)
            val stale = t != null && (s.connection != ConnectionStatus.Connected || s.nowMs - t.receivedAtMs > HomeRules.STALE_AFTER_MS)
            telemetryMapper.map(t, tr, p.units, stale, batteryStale = s.meter1Stale)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, telemetryMapper.map(null, null))

    private val historyMapper = HistoryUiMapper(zone)

    /** Emite agora e a cada virada de dia local, para "Hoje"/"Ontem" do histórico não ficarem velhos com o app aberto. */
    private val dayTick = flow {
        while (true) {
            val now = wallClockMs()
            emit(Unit)
            val nextMidnight = java.time.Instant.ofEpochMilli(now).atZone(zone).toLocalDate().plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            delay(nextMidnight - now)
        }
    }
    private val trips: StateFlow<List<Trip>> = tripRepository.trips.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val historyState: StateFlow<HistoryUiState> = combine(trips, preferences, dayTick) { list, p, _ -> historyMapper.map(list, wallClockMs(), p.units) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, historyMapper.map(emptyList(), wallClockMs()))

    fun trip(id: Long): Trip? = trips.value.firstOrNull { it.id == id }
    fun tripDetail(id: Long): TripDetailUi? = trip(id)?.let { historyMapper.detail(it, wallClockMs(), preferences.value.units) }

    fun onTheme(mode: ThemeMode) = updatePrefs { it.copy(theme = mode) }
    fun onUnits(units: Units) = updatePrefs { it.copy(units = units) }
    fun onAutoReconnect(on: Boolean) = updatePrefs { it.copy(autoReconnect = on) }
    fun onSaveRoute(on: Boolean) = updatePrefs { it.copy(saveRoute = on) }
    /** Só guarda a preferência; não altera o fluxo de bloqueio nem envia nada ao patinete. */
    fun onLockWithPin(on: Boolean) = updatePrefs { it.copy(lockWithPin = on) }
    private fun updatePrefs(t: (Preferences) -> Preferences) { viewModelScope.launch { preferencesStore.update(t) } }

    /** Apaga só o histórico local; nada é enviado ao patinete. */
    fun onClearHistory() { viewModelScope.launch { tripRepository.clear() } }

    /** Esquecer o patinete: desconecta localmente (o pareamento real é Fase 4). Nenhum comando enviado. */
    fun onForgetScooter() {
        userDisconnect()
        cancelRecovery()
        onboardingJob?.cancel()
        _onboarding.value = OnboardingFlow()   // a busca recomeça quando o primeiro uso for exibido
        updatePrefs { it.copy(onboardingDone = false, scooterAddress = null) }   // o transporte atual só muda no próximo início (ADR 0006)
    }

    // ---- Ambiente Bluetooth (SIMULADO nesta fase: nenhuma permissão/intent real do Android) ----
    val bluetooth: StateFlow<BluetoothState> =
        combine(bluetoothEnvironment.bluetoothEnabled, bluetoothEnvironment.permissionGranted) { b, p -> BluetoothState(b, p) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, BluetoothState(bluetoothEnvironment.bluetoothEnabled.value, bluetoothEnvironment.permissionGranted.value))

    // ---- Primeiro uso ----
    private val _onboarding = MutableStateFlow(OnboardingFlow())
    val onboarding: StateFlow<OnboardingFlow> = _onboarding
    private var onboardingJob: Job? = null

    /** Procura (simulado: encontra o Fake após [ONBOARDING_SEARCH_MS]) assim que Bluetooth e permissão estiverem prontos. */
    fun startOnboarding() {
        onboardingJob?.cancel()
        _onboarding.value = OnboardingFlow()
        onboardingJob = viewModelScope.launch {
            bluetooth.first { it.ready }
            delay(ONBOARDING_SEARCH_MS)
            _onboarding.update { it.scooterFound() }
        }
    }

    /** "Conectar a este patinete": só aqui o app conecta (ao Fake). Nenhum comando é enviado. */
    fun onboardingConfirm() {
        if (_onboarding.value.step != OnboardingStep.Found || _onboarding.value.confirmed) return
        _onboarding.update { it.confirm() }
        controller.connect()
        onboardingJob = viewModelScope.launch {
            val connected = withTimeoutOrNull(ONBOARDING_CONNECT_TIMEOUT_MS) { controller.state.first { it.connection == ConnectionStatus.Connected } }
            if (connected != null) _onboarding.update { it.connectionEstablished() }
            else {
                // Sem conexão no prazo: não deixa o transporte tentando em segundo plano e devolve o controle ao usuário.
                controller.disconnect()
                // disconnect() é assíncrono: só libera "Tentar novamente" com o transporte de fato Disconnected (senão o novo connect() seria ignorado).
                withTimeoutOrNull(ATTEMPT_TIMEOUT_MS) { controller.state.first { it.connection == ConnectionStatus.Disconnected } }
                _onboarding.update { it.connectionFailed() }
            }
        }
    }

    fun onboardingFinish() {
        _onboarding.update { it.finish() }
        if (_onboarding.value.step == OnboardingStep.Ready) updatePrefs { it.copy(onboardingDone = true) }
    }

    fun onboardingCancel() {
        _onboarding.update { it.cancel() }
        if (_onboarding.value.step == OnboardingStep.Cancelled) onboardingJob?.cancel()
    }

    // ---- Recuperação (reconexão com até 5 tentativas) ----
    private var attemptNumber: Int? = null
    private var isOutOfRange = false
    private var recoveryJob: Job? = null
    private val _recovery = MutableStateFlow<RecoveryReason?>(null)

    /**
     * Motivo atual da tela de recuperação; `null` = nada a recuperar. Prioridade documentada em [RecoveryReason.from].
     * Atualizado de forma síncrona (lê o ambiente na fonte), sem depender da propagação de um `stateIn`:
     * a tela sai quando o motivo é nulo e não pode ver `null` logo após o clique em "Procurar patinete".
     */
    val recovery: StateFlow<RecoveryReason?> = _recovery

    private fun setRecovery(attempt: Int? = attemptNumber, outOfRange: Boolean = isOutOfRange) {
        attemptNumber = attempt
        isOutOfRange = outOfRange
        refreshRecovery()
    }

    private fun refreshRecovery() {
        _recovery.value = RecoveryReason.from(BluetoothState(bluetoothEnvironment.bluetoothEnabled.value, bluetoothEnvironment.permissionGranted.value), isOutOfRange, attemptNumber)
    }

    init { viewModelScope.launch { bluetooth.collect { refreshRecovery() } } }
    init { watchConnecting() }
    init { watchLinkLoss() }

    /** Tenta reconectar (ao Fake) até [RecoveryReason.MAX_ATTEMPTS] vezes; interrompe se Bluetooth/permissão faltarem. */
    fun startRecovery() {
        recoveryJob?.cancel()
        setRecovery(attempt = 1, outOfRange = false)
        recoveryJob = viewModelScope.launch {
            for (n in 1..RecoveryReason.MAX_ATTEMPTS) {
                if (!environmentReady()) { setRecovery(attempt = null); return@launch }
                setRecovery(attempt = n)
                controller.connect()
                // null = estourou o prazo sem conectar (cancelamento do job NÃO vira null: propaga CancellationException).
                val connected = withTimeoutOrNull(ATTEMPT_TIMEOUT_MS) { controller.state.first { it.connection == ConnectionStatus.Connected } }
                if (connected != null) { setRecovery(attempt = null); return@launch }
                controller.disconnect()
                // disconnect() é assíncrono; connect() é ignorado enquanto o estado não voltar a Disconnected.
                withTimeoutOrNull(ATTEMPT_TIMEOUT_MS) { controller.state.first { it.connection == ConnectionStatus.Disconnected } }
            }
            setRecovery(attempt = null, outOfRange = true)
        }
    }

    /** Lê o ambiente na fonte (o StateFlow derivado pode estar uma emissão atrasado logo após Ativar/Permitir). */
    private fun environmentReady() = bluetoothEnvironment.bluetoothEnabled.value && bluetoothEnvironment.permissionGranted.value

    fun cancelRecovery() {
        // Há tentativa em curso (Connecting ou aguardando o timeout entre tentativas): não deixa o transporte tentando
        // em segundo plano. disconnect() é seguro com o transporte já desconectado.
        if (recoveryJob?.isActive == true) controller.disconnect()
        recoveryJob?.cancel()
        setRecovery(attempt = null, outOfRange = false)
    }

    /** Só mexe no ambiente (simulado). Quem decide reconectar em seguida é a tela: no primeiro uso nada conecta sem confirmação. */
    fun onEnableBluetooth() { bluetoothEnvironment.requestEnableBluetooth(); refreshRecovery() }
    fun onGrantPermission() { bluetoothEnvironment.requestPermission(); refreshRecovery() }
    fun onOpenSystemSettings() = bluetoothEnvironment.openSystemSettings()

    /** Zera a viagem atual e a salva no histórico (se houve amostras). Local: NÃO envia nada ao patinete. */
    fun onZeroTrip() {
        val startedAt = tripStartWallMs
        val saved = tripTracker.reset()
        trip.value = null
        // Viagem sem distância ou duração (uma amostra só) não é registro útil.
        if (saved != null && saved.distanceKm > 0f && saved.durationMs > 0) {
            viewModelScope.launch { tripRepository.save(Trip.from(saved, startedAt)) }
        }
    }

    /**
     * Toque no chip de conexão: reconecta sem sair da Home. Desconectado → conecta; Conectando (possivelmente preso) →
     * cancela e tenta de novo; Conectado → nada. Só abre/fecha o enlace (nenhum comando é enviado).
     */
    fun onConnectionChip() {
        if (chipJob?.isActive == true) return   // toques repetidos não reiniciam a tentativa em curso
        when (controller.state.value.connection) {
            ConnectionStatus.Disconnected -> if (environmentReady()) controller.connect()   // sem Bluetooth/permissão: nada a tentar
            ConnectionStatus.Connecting -> chipJob = viewModelScope.launch {
                controller.disconnect()
                withTimeoutOrNull(ATTEMPT_TIMEOUT_MS) { controller.state.first { it.connection == ConnectionStatus.Disconnected } }
                if (environmentReady()) controller.connect()
            }
            ConnectionStatus.Connected -> Unit
        }
    }

    private var chipJob: Job? = null

    /** Desconexão pedida pelo usuário com o enlace Conectado: a vigia de queda não deve reagir a ela. */
    private var expectedDisconnect = false
    private var autoReconnectJob: Job? = null

    /**
     * F5.3: enlace Conectado → Desconectado sem pedido do usuário e com `autoReconnect` ligado: tenta reconectar com
     * backoff limitado ([AUTO_RECONNECT_DELAYS_MS]). Só chama `connect()` (o transporte só escreve CONNECT/heartbeat, se o
     * modo TX estiver ligado); nenhum comando do usuário. Cede se outro fluxo (chip, recuperação, botão) já agiu.
     */
    private fun watchLinkLoss() {
        viewModelScope.launch {
            var previous = controller.state.value.connection
            controller.state.map { it.connection }.distinctUntilChanged().collect { now ->
                val lost = previous == ConnectionStatus.Connected && now == ConnectionStatus.Disconnected
                previous = now
                if (now == ConnectionStatus.Connected) autoReconnectJob?.cancel()
                if (!lost) return@collect
                if (expectedDisconnect) { expectedDisconnect = false; return@collect }
                startAutoReconnect()
            }
        }
    }

    private fun startAutoReconnect() {
        autoReconnectJob?.cancel()
        autoReconnectJob = viewModelScope.launch {
            for (wait in AUTO_RECONNECT_DELAYS_MS) {
                delay(wait)
                if (!preferences.value.autoReconnect) return@launch
                bluetooth.first { it.ready }
                if (controller.state.value.connection != ConnectionStatus.Disconnected || recoveryJob?.isActive == true) return@launch
                controller.connect()
                controller.state.first { it.connection != ConnectionStatus.Connecting }
                if (controller.state.value.connection == ConnectionStatus.Connected) return@launch
            }
        }
    }

    private fun userDisconnect() {
        autoReconnectJob?.cancel()
        expectedDisconnect = controller.state.value.connection == ConnectionStatus.Connected
        controller.disconnect()
    }

    /** Vigia: Conectando por mais de [CONNECTING_WATCHDOG_MS] é derrubado, para a Home nunca ficar presa em "Conectando". */
    private fun watchConnecting() {
        viewModelScope.launch {
            controller.state.map { it.connection }.distinctUntilChanged().collectLatest {
                if (it == ConnectionStatus.Connecting) { delay(CONNECTING_WATCHDOG_MS); controller.disconnect() }
            }
        }
    }

    /** Ação do botão principal conforme o estado atual: procurar, bloquear, desbloquear ou tentar de novo. */
    fun onMainButton() {
        val state = controller.state.value
        val s = mapper.map(state).mainButton   // sem defasagem do uiState
        if (!s.enabled || s.asksPin) return   // modo PIN: a tela abre o diálogo e chama onUnlockPin
        when (s.kind) {
            MainButtonKind.Search -> controller.connect()
            MainButtonKind.Unlock -> controller.request(ScooterCommand.SetLocked(false))
            MainButtonKind.Lock -> if (state.lockMode2) controller.lock() else controller.request(ScooterCommand.SetLocked(true))
            MainButtonKind.Retry -> mapper.retryCommand(state)?.let { controller.request(it) }
            else -> Unit
        }
    }

    /** Desbloqueio com PIN (4.4): uma tentativa; o PIN é apagado pelo controlador após o uso. */
    fun onUnlockPin(pin: Pin) = controller.unlock(pin)

    fun onHeadlight(on: Boolean) = controller.request(ScooterCommand.SetHeadlight(on))
    fun onMotors(raw: Int) = controller.request(ScooterCommand.SetDualMotor(raw == 1))
    fun onMode(raw: Int) = controller.request(ScooterCommand.SetSpeedMode(raw))
    fun onCruise(on: Boolean) = controller.request(ScooterCommand.SetCruise(on))
    fun onZeroStart(on: Boolean) = controller.request(ScooterCommand.SetZeroStart(on))
    fun onLimit(kmh: Int) = controller.request(ScooterCommand.SetSpeedLimit(kmh))

    companion object {
        /** Conectando por mais que isso é tratado como preso `[HIPÓTESE]` (as etapas do transporte somam ≤ ~50 s). */
        const val CONNECTING_WATCHDOG_MS = 60_000L
        /** Espera antes de cada tentativa de reconexão automática (backoff, no máximo 5 tentativas) `[HIPÓTESE]`. */
        val AUTO_RECONNECT_DELAYS_MS = listOf(2_000L, 5_000L, 10_000L, 20_000L, 30_000L)
        /** design/build.py: a busca do primeiro uso dura 2000 ms (afterDelay). */
        private const val ONBOARDING_SEARCH_MS = 2_000L
        /** Prazo da conexão do primeiro uso: 10 s `[HIPÓTESE]` (o dobro de uma tentativa de reconexão; validar na Fase 4). */
        private const val ONBOARDING_CONNECT_TIMEOUT_MS = 10_000L
        /** Cada tentativa de reconexão espera até 5 s `[HIPÓTESE]` (tempo real validado na Fase 4). */
        private const val ATTEMPT_TIMEOUT_MS = 5_000L
    }
}

/** Sem informação de ambiente (testes antigos): tudo pronto. A implementação simulada fica em `data`. */
private object ReadyBluetoothEnvironment : BluetoothEnvironment {
    override val bluetoothEnabled: StateFlow<Boolean> = MutableStateFlow(true)
    override val permissionGranted: StateFlow<Boolean> = MutableStateFlow(true)
    override fun requestEnableBluetooth() = Unit
    override fun requestPermission() = Unit
    override fun openSystemSettings() = Unit
}

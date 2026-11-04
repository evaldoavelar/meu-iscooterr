package br.dev.evaldo.meuscooter.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import br.dev.evaldo.meuscooter.domain.HomeController
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
import br.dev.evaldo.meuscooter.protocol.ScooterCommand
import br.dev.evaldo.meuscooter.protocol.ScooterTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
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
    timeLabel: (Long) -> String,
) : ViewModel() {
    private val transport = transportFactory(viewModelScope, clock)
    private val controller = HomeController(transport, viewModelScope, clock)
    private val mapper = HomeUiMapper(transport.speedModes.options(), deviceName, timeLabel)

    val preferences: StateFlow<Preferences> = preferencesStore.preferences.stateIn(viewModelScope, SharingStarted.Eagerly, Preferences())

    val uiState: StateFlow<HomeUiState> = combine(controller.state, preferences) { s, p -> mapper.map(s, p.units) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, mapper.map(controller.state.value))

    private val tripTracker = TripTracker()
    private val trip = MutableStateFlow<TripSummary?>(null)
    private val telemetryMapper = TelemetryUiMapper()

    init {
        viewModelScope.launch {
            controller.state.mapNotNull { it.telemetry }.distinctUntilChanged { a, b -> a.receivedAtMs == b.receivedAtMs }
                .collect { tripTracker.onTelemetry(it); trip.value = tripTracker.current }
        }
    }

    val telemetryState: StateFlow<TelemetryUiState> =
        combine(controller.state, trip, preferences) { s, tr, p -> telemetryMapper.map(s.telemetry, tr, p.units) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, telemetryMapper.map(null, null))

    private val historyMapper = HistoryUiMapper()
    private val trips: StateFlow<List<Trip>> = tripRepository.trips.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val historyState: StateFlow<HistoryUiState> = combine(trips, preferences) { list, p -> historyMapper.map(list, wallClockMs(), p.units) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, historyMapper.map(emptyList(), wallClockMs()))

    fun trip(id: Long): Trip? = trips.value.firstOrNull { it.id == id }
    fun tripDetail(id: Long): TripDetailUi? = trip(id)?.let { historyMapper.detail(it, wallClockMs(), preferences.value.units) }

    fun onTheme(mode: ThemeMode) = updatePrefs { it.copy(theme = mode) }
    fun onUnits(units: Units) = updatePrefs { it.copy(units = units) }
    fun onAutoReconnect(on: Boolean) = updatePrefs { it.copy(autoReconnect = on) }
    fun onSaveRoute(on: Boolean) = updatePrefs { it.copy(saveRoute = on) }
    private fun updatePrefs(t: (Preferences) -> Preferences) { viewModelScope.launch { preferencesStore.update(t) } }

    /** Apaga só o histórico local; nada é enviado ao patinete. */
    fun onClearHistory() { viewModelScope.launch { tripRepository.clear() } }

    /** Esquecer o patinete: desconecta localmente (o pareamento real é Fase 4). Nenhum comando enviado. */
    fun onForgetScooter() = controller.disconnect()

    /** Zera a viagem atual e a salva no histórico (se houve amostras). Local: NÃO envia nada ao patinete. */
    fun onZeroTrip() {
        val now = wallClockMs()
        val saved = tripTracker.reset()
        trip.value = null
        // Viagem sem distância ou duração (uma amostra só) não é registro útil.
        if (saved != null && saved.distanceKm > 0f && saved.durationMs > 0) {
            viewModelScope.launch { tripRepository.save(Trip.from(saved, now - saved.durationMs)) }
        }
    }

    /** Ação do botão principal conforme o estado atual: procurar, bloquear, desbloquear ou tentar de novo. */
    fun onMainButton() {
        val s = mapper.map(controller.state.value).mainButton   // sem defasagem do uiState
        if (!s.enabled) return
        val locked = controller.state.value.telemetry?.locked
        when (s.kind) {
            MainButtonKind.Search -> controller.connect()
            MainButtonKind.Unlock -> controller.request(ScooterCommand.SetLocked(false))
            MainButtonKind.Lock -> controller.request(ScooterCommand.SetLocked(true))
            MainButtonKind.Retry -> locked?.let { controller.request(ScooterCommand.SetLocked(!it)) }
            else -> Unit
        }
    }

    fun onHeadlight(on: Boolean) = controller.request(ScooterCommand.SetHeadlight(on))
    fun onMotors(raw: Int) = controller.request(ScooterCommand.SetDualMotor(raw == 1))
    fun onMode(raw: Int) = controller.request(ScooterCommand.SetSpeedMode(raw))
    fun onCruise(on: Boolean) = controller.request(ScooterCommand.SetCruise(on))
    fun onZeroStart(on: Boolean) = controller.request(ScooterCommand.SetZeroStart(on))
    fun onLimit(kmh: Int) = controller.request(ScooterCommand.SetSpeedLimit(kmh))
}

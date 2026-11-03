package br.dev.evaldo.meuscooter.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import br.dev.evaldo.meuscooter.domain.HomeController
import br.dev.evaldo.meuscooter.domain.InMemoryTripRepository
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
    timeLabel: (Long) -> String,
) : ViewModel() {
    private val transport = transportFactory(viewModelScope, clock)
    private val controller = HomeController(transport, viewModelScope, clock)
    private val mapper = HomeUiMapper(transport.speedModes.options(), deviceName, timeLabel)

    val uiState: StateFlow<HomeUiState> = controller.state
        .map(mapper::map)
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
        combine(controller.state, trip) { s, tr -> telemetryMapper.map(s.telemetry, tr) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, telemetryMapper.map(null, null))

    private val historyMapper = HistoryUiMapper()
    private val trips: StateFlow<List<Trip>> = tripRepository.trips.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val historyState: StateFlow<HistoryUiState> = trips.map { historyMapper.map(it, wallClockMs()) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, historyMapper.map(emptyList(), wallClockMs()))

    fun trip(id: Long): Trip? = trips.value.firstOrNull { it.id == id }
    fun tripDetail(id: Long): TripDetailUi? = trip(id)?.let { historyMapper.detail(it, wallClockMs()) }

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

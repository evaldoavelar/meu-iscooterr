package br.dev.evaldo.meuscooter.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import br.dev.evaldo.meuscooter.domain.HomeController
import br.dev.evaldo.meuscooter.protocol.Clock
import br.dev.evaldo.meuscooter.protocol.ScooterCommand
import br.dev.evaldo.meuscooter.protocol.ScooterTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Envolve [HomeController] em `viewModelScope`. Nenhuma chamada bloqueia a thread da UI;
 * a decisão de enviar (ou ignorar) é do reducer do domínio.
 */
class HomeViewModel(
    transportFactory: (CoroutineScope, Clock) -> ScooterTransport,
    clock: Clock,
    deviceName: String?,
    timeLabel: (Long) -> String,
) : ViewModel() {
    private val transport = transportFactory(viewModelScope, clock)
    private val controller = HomeController(transport, viewModelScope, clock)
    private val mapper = HomeUiMapper(transport.speedModes.options(), deviceName, timeLabel)

    val uiState: StateFlow<HomeUiState> = controller.state
        .map(mapper::map)
        .stateIn(viewModelScope, SharingStarted.Eagerly, mapper.map(controller.state.value))

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

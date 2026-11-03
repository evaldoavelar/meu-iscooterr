package br.dev.evaldo.meuscooter.domain

import br.dev.evaldo.meuscooter.protocol.Clock
import br.dev.evaldo.meuscooter.protocol.ScooterCommand
import br.dev.evaldo.meuscooter.protocol.ScooterTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Liga [ScooterTransport] ao [HomeReducer]. Nunca bloqueia a chamada: toda E/S roda em [scope].
 * Confinado a um único dispatcher (ex.: Main) — o estado é atualizado sem lock.
 */
class HomeController(
    private val transport: ScooterTransport,
    private val scope: CoroutineScope,
    private val clock: Clock,
    tickMs: Long = 250,
) {
    private val _state = MutableStateFlow(HomeState(nowMs = clock.nowMs()))
    val state: StateFlow<HomeState> = _state.asStateFlow()

    init {
        scope.launch { transport.connection.collect { dispatch(HomeEvent.ConnectionChanged(it, clock.nowMs())) } }
        scope.launch { transport.telemetry.collect { dispatch(HomeEvent.TelemetryReceived(it)) } }
        scope.launch {
            while (true) {
                delay(tickMs)
                dispatch(HomeEvent.Tick(clock.nowMs()))
            }
        }
    }

    fun connect() { scope.launch { transport.connect() } }
    fun disconnect() { scope.launch { transport.disconnect() } }

    /** Pedido do usuário; ignorado pelo reducer se desabilitado ou já pendente. */
    fun request(command: ScooterCommand) = dispatch(HomeEvent.UserRequested(command, clock.nowMs()))

    private fun dispatch(event: HomeEvent) {
        val reduction = HomeReducer.reduce(_state.value, event)
        _state.value = reduction.state
        reduction.effects.forEach { effect ->
            when (effect) {
                is HomeEffect.Send -> scope.launch {
                    val ack = transport.send(effect.command)
                    dispatch(HomeEvent.AckReceived(effect.command.control, ack, clock.nowMs()))
                }
            }
        }
    }
}

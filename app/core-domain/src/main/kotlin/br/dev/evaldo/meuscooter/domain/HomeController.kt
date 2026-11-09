package br.dev.evaldo.meuscooter.domain

import br.dev.evaldo.meuscooter.protocol.Ack
import br.dev.evaldo.meuscooter.protocol.Clock
import br.dev.evaldo.meuscooter.protocol.ConnectionStatus
import br.dev.evaldo.meuscooter.protocol.Pin
import br.dev.evaldo.meuscooter.protocol.ScooterCommand
import br.dev.evaldo.meuscooter.protocol.ScooterSettingResult
import br.dev.evaldo.meuscooter.protocol.ScooterTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
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
    private val _state = MutableStateFlow(HomeState(nowMs = clock.nowMs(), pinUnlock = transport.pinUnlockAvailable, lockMode2 = transport.lockAvailable, commandsAvailable = transport.genericCommandsAvailable, headlightAvailable = transport.headlightAvailable, unitAvailable = transport.unitAvailable, driveModeAvailable = transport.driveModeAvailable, speedLimitAvailable = transport.speedLimitAvailable, driveAvailable = transport.driveAvailable))
    val state: StateFlow<HomeState> = _state.asStateFlow()

    init {
        scope.launch { transport.connection.collect { dispatch(HomeEvent.ConnectionChanged(it, clock.nowMs())) } }
        scope.launch { transport.agedTelemetry.collect { dispatch(HomeEvent.AgedTelemetryReceived(it)) } }
        scope.launch {
            while (true) {
                delay(tickMs)
                dispatch(HomeEvent.Tick(clock.nowMs()))
            }
        }
    }

    private var connectJob: Job? = null

    /**
     * Ignorado se já conectando/conectado ou se um `transport.connect()` já foi agendado: o estado só muda quando o
     * transporte executa, então toques repetidos antes disso não podem agendar várias conexões (revisão Codex r1, achado 4).
     */
    fun connect() {
        if (_state.value.connection != ConnectionStatus.Disconnected) return
        if (connectJob?.isActive == true) return
        connectJob = scope.launch { transport.connect() }
    }
    fun disconnect() {
        connectJob?.cancel()   // desconectar interrompe uma conexão em andamento; permite nova tentativa depois
        scope.launch { transport.disconnect() }
    }

    /** Pedido do usuário; ignorado pelo reducer se desabilitado ou já pendente. */
    fun request(command: ScooterCommand) = dispatch(HomeEvent.UserRequested(command, clock.nowMs()))

    /**
     * Desbloqueio com PIN: uma tentativa por chamada (sem retry). O PIN vai direto ao transporte, nunca ao estado, e é
     * apagado ([Pin.wipe]) ao terminar — ou na hora, se o pedido for recusado (desabilitado ou outro em curso).
     * Devolve `true` se a tentativa foi iniciada, `false` se foi recusada.
     */
    fun unlock(pin: Pin): Boolean {
        val reduction = HomeReducer.reduce(_state.value, HomeEvent.UnlockRequested(clock.nowMs()))
        _state.value = reduction.state
        if (HomeEffect.StartUnlock !in reduction.effects) {
            pin.wipe()
            return false
        }
        scope.launch {
            val result = try {
                transport.unlock(pin)
            } finally {
                pin.wipe()
            }
            dispatch(HomeEvent.UnlockFinished(result, clock.nowMs()))
        }
        return true
    }

    /** Bloqueio temporário (modo 2, sem PIN): uma tentativa por chamada (sem retry); só `Locked` é "concluído". */
    fun lock() {
        val reduction = HomeReducer.reduce(_state.value, HomeEvent.LockRequested(clock.nowMs()))
        _state.value = reduction.state
        if (HomeEffect.StartLock !in reduction.effects) return
        scope.launch { dispatch(HomeEvent.LockFinished(transport.lock(), clock.nowMs())) }
    }

    /**
     * Unidade, modo, limite e tração reais (S31) chamam a API estreita do transporte (uma única tentativa, sem retry) e
     * só [ScooterSettingResult.Confirmed] vira [Ack.Accepted]; recusa/erro/falta de confirmação viram [Ack.Rejected] com
     * os códigos de [SettingAckCodes]. O resto segue por [ScooterTransport.send].
     */
    private suspend fun sendCommand(command: ScooterCommand, at: HomeState): Ack {
        if (!at.sendsViaTransportApi(command)) return transport.send(command)
        val result = when (command) {
            is ScooterCommand.SetSpeedUnit -> transport.setUnit(command.mph)
            is ScooterCommand.SetSpeedMode -> transport.setDriveMode(command.raw)
            is ScooterCommand.SetSpeedLimit -> transport.setSpeedLimit(command.kmh)
            is ScooterCommand.SetDualMotor -> transport.setDrive(command.dual)
            else -> return transport.send(command)
        }
        return when (result) {
            ScooterSettingResult.Confirmed -> Ack.Accepted
            is ScooterSettingResult.Refused -> Ack.Rejected(SettingAckCodes.refused(result.reason))
            ScooterSettingResult.Rejected -> Ack.Rejected(SettingAckCodes.REJECTED)
            is ScooterSettingResult.Unconfirmed -> Ack.Rejected(SettingAckCodes.unconfirmed(result.cause))
        }
    }

    private fun dispatch(event: HomeEvent) {
        val reduction = HomeReducer.reduce(_state.value, event)
        _state.value = reduction.state
        reduction.effects.forEach { effect ->
            when (effect) {
                is HomeEffect.Send -> scope.launch {
                    val ack = sendCommand(effect.command, reduction.state)
                    dispatch(HomeEvent.AckReceived(effect.command.control, ack, clock.nowMs()))
                }
                HomeEffect.StartUnlock -> Unit // só nasce em [unlock], que tem o PIN
                HomeEffect.StartLock -> Unit // só nasce em [lock]
            }
        }
    }
}

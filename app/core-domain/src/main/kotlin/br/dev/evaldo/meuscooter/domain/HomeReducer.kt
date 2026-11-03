package br.dev.evaldo.meuscooter.domain

import br.dev.evaldo.meuscooter.protocol.Ack
import br.dev.evaldo.meuscooter.protocol.ConnectionStatus
import br.dev.evaldo.meuscooter.protocol.Control
import br.dev.evaldo.meuscooter.protocol.ScooterCommand
import br.dev.evaldo.meuscooter.protocol.Telemetry

data class PendingOp(val command: ScooterCommand, val sentAtMs: Long)

data class HomeState(
    val connection: ConnectionStatus = ConnectionStatus.Disconnected,
    val telemetry: Telemetry? = null,
    val nowMs: Long = 0,
    val pending: Map<Control, PendingOp> = emptyMap(),
    val outcomes: Map<Control, Outcome> = emptyMap(),
)

sealed interface Outcome {
    data class Confirmed(val atMs: Long) : Outcome
    data class Failed(val reason: String, val atMs: Long) : Outcome
    data class TimedOut(val atMs: Long) : Outcome
}

/** Constantes de UX. Timeout e visibilidade são [HIPÓTESE]; latências medidas em S2 [CONFIRMADO]. */
object HomeRules {
    const val PENDING_TIMEOUT_MS = 3_000L
    const val CONFIRMED_VISIBLE_MS = 2_000L
    const val STALE_AFTER_MS = 2_000L
    const val CONNECTION_LOST = "connection-lost"
}

enum class DisabledReason { NotConnected, Pending, Moving, SpeedUnknown, TelemetryStale, StateUnknown }

sealed interface Availability {
    data object Enabled : Availability
    data class Disabled(val reason: DisabledReason) : Availability
}

sealed interface HomeEvent {
    data class ConnectionChanged(val status: ConnectionStatus, val nowMs: Long) : HomeEvent
    data class TelemetryReceived(val telemetry: Telemetry) : HomeEvent
    data class AckReceived(val control: Control, val ack: Ack, val nowMs: Long) : HomeEvent
    data class Tick(val nowMs: Long) : HomeEvent
    data class UserRequested(val command: ScooterCommand, val nowMs: Long) : HomeEvent
}

sealed interface HomeEffect {
    data class Send(val command: ScooterCommand) : HomeEffect
}

data class Reduction(val state: HomeState, val effects: List<HomeEffect> = emptyList())

object HomeReducer {
    fun availability(state: HomeState, command: ScooterCommand): Availability {
        if (state.connection != ConnectionStatus.Connected) return Availability.Disabled(DisabledReason.NotConnected)
        if (command.control in state.pending) return Availability.Disabled(DisabledReason.Pending)
        return when {
            command is ScooterCommand.SetHeadlight -> Availability.Enabled
            command is ScooterCommand.SetLocked && !command.locked -> unlockAvailability(state)
            else -> restrictedAvailability(state)
        }
    }

    /** Bloqueio, motores, modo, limite, cruise, partida do zero: exigem velocidade conhecida, fresca e zero. */
    private fun restrictedAvailability(state: HomeState): Availability {
        val t = state.telemetry ?: return Availability.Disabled(DisabledReason.SpeedUnknown)
        if (state.nowMs - t.receivedAtMs > HomeRules.STALE_AFTER_MS) return Availability.Disabled(DisabledReason.TelemetryStale)
        val speed = t.speedKmh ?: return Availability.Disabled(DisabledReason.SpeedUnknown)
        return if (speed > 0f) Availability.Disabled(DisabledReason.Moving) else Availability.Enabled
    }

    /**
     * Desbloqueio (regra própria, [HIPÓTESE] a validar com o proprietário): exige o último estado
     * conhecido "bloqueado"; aceita telemetria desatualizada e velocidade desconhecida, mas nunca movimento.
     */
    private fun unlockAvailability(state: HomeState): Availability {
        val t = state.telemetry
        return when {
            t?.locked != true -> Availability.Disabled(DisabledReason.StateUnknown)
            (t.speedKmh ?: 0f) > 0f -> Availability.Disabled(DisabledReason.Moving)
            else -> Availability.Enabled
        }
    }

    private fun matches(command: ScooterCommand, t: Telemetry): Boolean = when (command) {
        is ScooterCommand.SetLocked -> t.locked == command.locked
        is ScooterCommand.SetHeadlight -> t.headlightOn == command.on
        is ScooterCommand.SetDualMotor -> t.dualMotor == command.dual
        is ScooterCommand.SetSpeedMode -> t.speedModeRaw == command.raw
        is ScooterCommand.SetCruise -> t.cruiseOn == command.on
        is ScooterCommand.SetZeroStart -> t.zeroStartOn == command.on
        is ScooterCommand.SetSpeedLimit -> t.speedLimitKmh == command.kmh
    }

    fun reduce(state: HomeState, event: HomeEvent): Reduction = when (event) {
        is HomeEvent.UserRequested -> {
            if (availability(state, event.command) != Availability.Enabled) {
                Reduction(state)
            } else {
                val control = event.command.control
                Reduction(
                    state.copy(
                        nowMs = event.nowMs,
                        pending = state.pending + (control to PendingOp(event.command, event.nowMs)),
                        outcomes = state.outcomes - control,
                    ),
                    listOf(HomeEffect.Send(event.command)),
                )
            }
        }
        is HomeEvent.TelemetryReceived -> {
            val t = event.telemetry
            val done = state.pending.filterValues { it.sentAtMs <= t.receivedAtMs && matches(it.command, t) }
            Reduction(
                state.copy(
                    telemetry = t,
                    pending = state.pending - done.keys,
                    outcomes = state.outcomes + done.mapValues { Outcome.Confirmed(t.receivedAtMs) },
                ),
            )
        }
        is HomeEvent.AckReceived -> when (val ack = event.ack) {
            Ack.Accepted -> Reduction(state)
            is Ack.Rejected -> Reduction(
                state.copy(
                    nowMs = event.nowMs,
                    pending = state.pending - event.control,
                    outcomes = state.outcomes + (event.control to Outcome.Failed(ack.reason, event.nowMs)),
                ),
            )
        }
        is HomeEvent.Tick -> {
            val expired = state.pending.filterValues { event.nowMs - it.sentAtMs >= HomeRules.PENDING_TIMEOUT_MS }
            val keptOutcomes = state.outcomes.filterValues {
                !(it is Outcome.Confirmed && event.nowMs - it.atMs >= HomeRules.CONFIRMED_VISIBLE_MS)
            }
            Reduction(
                state.copy(
                    nowMs = event.nowMs,
                    pending = state.pending - expired.keys,
                    outcomes = keptOutcomes + expired.mapValues { Outcome.TimedOut(event.nowMs) },
                ),
            )
        }
        is HomeEvent.ConnectionChanged -> {
            val lost = event.status != ConnectionStatus.Connected
            Reduction(
                state.copy(
                    connection = event.status,
                    nowMs = event.nowMs,
                    pending = if (lost) emptyMap() else state.pending,
                    outcomes = if (lost) {
                        state.outcomes + state.pending.mapValues { Outcome.Failed(HomeRules.CONNECTION_LOST, event.nowMs) }
                    } else {
                        state.outcomes
                    },
                ),
            )
        }
    }
}

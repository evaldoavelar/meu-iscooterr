package br.dev.evaldo.meuscooter.domain

import br.dev.evaldo.meuscooter.protocol.Ack
import br.dev.evaldo.meuscooter.protocol.AgedTelemetry
import br.dev.evaldo.meuscooter.protocol.ConnectionStatus
import br.dev.evaldo.meuscooter.protocol.Control
import br.dev.evaldo.meuscooter.protocol.HeadlightAckCodes
import br.dev.evaldo.meuscooter.protocol.LockResult
import br.dev.evaldo.meuscooter.protocol.ScooterCommand
import br.dev.evaldo.meuscooter.protocol.Telemetry
import br.dev.evaldo.meuscooter.protocol.UnlockResult

data class PendingOp(val command: ScooterCommand, val sentAtMs: Long)

data class HomeState(
    val connection: ConnectionStatus = ConnectionStatus.Disconnected,
    val telemetry: Telemetry? = null,
    val nowMs: Long = 0,
    val pending: Map<Control, PendingOp> = emptyMap(),
    val outcomes: Map<Control, Outcome> = emptyMap(),
    /** Instante do último quadro de cada grupo de campos (Meter1 = bloqueio/bateria/limite). `null` = nunca visto. */
    val meter1AtMs: Long? = null,
    val meter2AtMs: Long? = null,
    /**
     * O transporte desbloqueia com PIN (`ScooterTransport.pinUnlockAvailable`). Nesse modo o bloqueio NÃO usa
     * `SetLocked`: desbloquear passa por [HomeEvent.UnlockRequested] e bloquear está fora da etapa 4.4 (nada é enviado).
     */
    val pinUnlock: Boolean = false,
    /** Desbloqueio com PIN em curso ou último resultado; `null` = nada a mostrar. */
    val unlock: UnlockStatus? = null,
    /** O transporte bloqueia pelo LOCK modo 2, sem PIN (`ScooterTransport.lockAvailable`; S20). */
    val lockMode2: Boolean = false,
    /** Bloqueio (modo 2) em curso ou último resultado; `null` = nada a mostrar. */
    val lock: LockStatus? = null,
    /** O transporte envia comandos genéricos (`ScooterTransport.genericCommandsAvailable`); `false` = BLE real (só Lock/Unlock). */
    val commandsAvailable: Boolean = true,
    /** O transporte atende o farol mesmo sem comandos genéricos (`ScooterTransport.headlightAvailable`; BLE real, S31). */
    val headlightAvailable: Boolean = false,
    /** PINs recusados consecutivos (zera em [UnlockResult.Unlocked], ao iniciar o cooldown; o Tick só limpa o prazo). Só na sessão. */
    val pinRejects: Int = 0,
    /** Fim do cooldown local de PIN (instante de `nowMs`); `null` = sem cooldown. */
    val pinCooldownUntilMs: Long? = null,
) {
    /** Milissegundos que faltam para o cooldown de PIN acabar; 0 = sem cooldown. */
    val pinCooldownRemainingMs: Long
        get() = pinCooldownUntilMs?.let { (it - nowMs).coerceAtLeast(0L) } ?: 0L

    /** Bateria/bloqueio (Meter1) velhos ou nunca vistos, mesmo que o Meter2 mantenha `telemetry.receivedAtMs` fresco. */
    val meter1Stale: Boolean
        get() = connection == ConnectionStatus.Connected && telemetry != null &&
            (meter1AtMs?.let { nowMs - it > HomeRules.STALE_AFTER_MS } ?: true)

    /**
     * Meter2 (odômetro, tensão, corrente, potência, temp. do motor) velho ou nunca visto. Sem nenhuma idade por grupo
     * (estado legado/Fake) não há como distinguir: `false`.
     */
    val meter2Stale: Boolean
        get() = connection == ConnectionStatus.Connected && telemetry != null &&
            (meter1AtMs != null || meter2AtMs != null) &&
            (meter2AtMs?.let { nowMs - it > HomeRules.STALE_AFTER_MS } ?: true)
}

/**
 * Telemetria para o [TripTracker]: potência/tensão/corrente (Meter2) só valem se o grupo Meter2 estava fresco no instante
 * do quadro mais novo ([Telemetry.receivedAtMs]); senão ficam `null`, para o tracker não integrar potência velha em Wh.
 * Sem idade por grupo (legado/Fake) não há como distinguir: passa como está.
 */
fun HomeState.tripTelemetry(): Telemetry? {
    val t = telemetry ?: return null
    if (meter1AtMs == null && meter2AtMs == null) return t
    val fresh = meter2AtMs?.let { t.receivedAtMs - it <= HomeRules.STALE_AFTER_MS } ?: false
    return if (fresh) t else t.copy(powerW = null, voltageV = null, currentA = null)
}

/** Andamento do desbloqueio com PIN (regra 5: só [UnlockResult.Unlocked] é "concluído"). */
sealed interface UnlockStatus {
    data class Waiting(val sinceMs: Long) : UnlockStatus
    data class Finished(val result: UnlockResult, val atMs: Long) : UnlockStatus
}

/** Andamento do bloqueio modo 2 (regra 5: só [LockResult.Locked] é "concluído"). */
sealed interface LockStatus {
    data class Waiting(val sinceMs: Long) : LockStatus
    data class Finished(val result: LockResult, val atMs: Long) : LockStatus
}

sealed interface Outcome {
    /** [command] = o que a telemetria confirmou; o texto "Bloqueado/Desbloqueado" vem dele, nunca da telemetria seguinte. */
    data class Confirmed(val atMs: Long, val command: ScooterCommand? = null) : Outcome
    /** [command] = o que foi tentado; permite repetir o mesmo comando e limpar o aviso se a telemetria já o refletir. */
    data class Failed(val reason: String, val atMs: Long, val command: ScooterCommand? = null) : Outcome
    data class TimedOut(val atMs: Long, val command: ScooterCommand? = null) : Outcome
}

/** Constantes de UX. Timeout e visibilidade são [HIPÓTESE]; latências medidas em S2 [CONFIRMADO]. */
object HomeRules {
    const val PENDING_TIMEOUT_MS = 3_000L
    const val CONFIRMED_VISIBLE_MS = 2_000L
    const val STALE_AFTER_MS = 2_000L
    /** Quanto tempo o aviso de Desbloquear/Bloquear RECUSADO (nada foi escrito) fica na tela antes de sumir sozinho. */
    const val REFUSAL_VISIBLE_MS = 6_000L
    const val CONNECTION_LOST = "connection-lost"

    /**
     * Cooldown local após PIN errado: decisão do proprietário (S29), NÃO valor do protocolo nem do patinete. Proteção só
     * no app, por sessão (não persiste); nada é enviado ao patinete.
     */
    const val PIN_REJECTS_BEFORE_COOLDOWN = 5
    const val PIN_COOLDOWN_MS = 30_000L
}

enum class DisabledReason { NotConnected, Pending, Moving, SpeedUnknown, TelemetryStale, StateUnknown, NotAvailable, CommandsUnavailable, PinCooldown }

sealed interface Availability {
    data object Enabled : Availability
    data class Disabled(val reason: DisabledReason) : Availability
}

sealed interface HomeEvent {
    data class ConnectionChanged(val status: ConnectionStatus, val nowMs: Long) : HomeEvent
    data class TelemetryReceived(val telemetry: Telemetry) : HomeEvent
    /** A reading and its group timestamps must be published in the same state update. */
    data class AgedTelemetryReceived(val reading: AgedTelemetry) : HomeEvent
    /** Idade por grupo vinda do transporte (`ScooterTransport.agedTelemetry`); `telemetry.receivedAtMs` mascara o grupo parado. */
    data class GroupAgesReceived(val meter1AtMs: Long?, val meter2AtMs: Long?) : HomeEvent
    data class AckReceived(val control: Control, val ack: Ack, val nowMs: Long) : HomeEvent
    data class Tick(val nowMs: Long) : HomeEvent
    data class UserRequested(val command: ScooterCommand, val nowMs: Long) : HomeEvent

    /** O usuário confirmou o PIN. O PIN NÃO passa pelo estado/eventos: fica com o controlador até o transporte. */
    data class UnlockRequested(val nowMs: Long) : HomeEvent
    data class UnlockFinished(val result: UnlockResult, val nowMs: Long) : HomeEvent

    /** O usuário pediu o bloqueio temporário (modo 2, sem PIN, sem dado do usuário). */
    data class LockRequested(val nowMs: Long) : HomeEvent
    data class LockFinished(val result: LockResult, val nowMs: Long) : HomeEvent
}

sealed interface HomeEffect {
    data class Send(val command: ScooterCommand) : HomeEffect

    /** Chamar `ScooterTransport.unlock` uma vez com o PIN que o controlador guarda. */
    data object StartUnlock : HomeEffect

    /** Chamar `ScooterTransport.lock` uma vez. */
    data object StartLock : HomeEffect
}

data class Reduction(val state: HomeState, val effects: List<HomeEffect> = emptyList())

object HomeReducer {
    fun availability(state: HomeState, command: ScooterCommand): Availability {
        if (state.connection != ConnectionStatus.Connected) return Availability.Disabled(DisabledReason.NotConnected)
        if (!state.commandsAvailable && !(command is ScooterCommand.SetHeadlight && state.headlightAvailable))
            return Availability.Disabled(DisabledReason.CommandsUnavailable)
        if (state.pinUnlock && command is ScooterCommand.SetLocked) return Availability.Disabled(DisabledReason.NotAvailable)
        if (command.control in state.pending) return Availability.Disabled(DisabledReason.Pending)
        return when {
            command is ScooterCommand.SetHeadlight -> Availability.Enabled
            command is ScooterCommand.SetLocked && !command.locked -> unlockAvailability(state)
            isMoving(state.telemetry) -> Availability.Disabled(DisabledReason.Moving)
            command is ScooterCommand.SetLocked && state.meter1Stale -> Availability.Disabled(DisabledReason.TelemetryStale)
            else -> restrictedAvailability(state)
        }
    }

    /** S19: movimento da roda (bytes do Meter1, sem escala km/h) ou velocidade > 0. */
    private fun isMoving(t: Telemetry?): Boolean = t?.wheelMoving == true || (t?.speedKmh ?: 0f).let { it != 0f }

    /** Bloqueio, motores, modo, limite, cruise, partida do zero: exigem velocidade conhecida, fresca e zero. */
    private fun restrictedAvailability(state: HomeState): Availability {
        val t = state.telemetry ?: return Availability.Disabled(DisabledReason.SpeedUnknown)
        // Com idades por grupo (BLE e Fake via AgedTelemetry: ao menos uma idade existe após o 1º quadro), "parado" vem do
        // Meter1: o Meter2 renova receivedAtMs e não prova que a roda parou. Sem nenhuma idade (estado montado só com
        // TelemetryReceived, legado/testes) não há como distinguir grupos: vale o receivedAtMs geral (SetLocked(true) segue
        // recusado antes, em `availability`, por meter1Stale).
        val grouped = state.meter1AtMs != null || state.meter2AtMs != null
        val stale = if (grouped) state.meter1Stale else state.nowMs - t.receivedAtMs > HomeRules.STALE_AFTER_MS
        if (stale) return Availability.Disabled(DisabledReason.TelemetryStale)
        if (t.wheelMoving == true) return Availability.Disabled(DisabledReason.Moving)
        val speed = t.speedKmh ?: return Availability.Disabled(DisabledReason.SpeedUnknown)
        // `!= 0f` (não `> 0f`): velocidade negativa ou NaN não prova "parado"; falha segura.
        return if (speed != 0f) Availability.Disabled(DisabledReason.Moving) else Availability.Enabled
    }

    /**
     * Desbloqueio (regra própria; decisão do proprietário, sessão 08): permitido mesmo sem conhecer o último
     * estado e com telemetria velha; nunca com o patinete em movimento (velocidade > 0).
     */
    private fun unlockAvailability(state: HomeState): Availability =
        if (isMoving(state.telemetry)) Availability.Disabled(DisabledReason.Moving) else Availability.Enabled

    /**
     * Desbloqueio com PIN (plano Fase 4 §7.4), mais estrito que [unlockAvailability]: conectado, nenhum em curso,
     * Meter1 fresco, velocidade conhecida e 0, e Meter1 dizendo BLOQUEADO. O transporte repete as verificações.
     */
    fun pinUnlockAvailability(state: HomeState): Availability {
        if (state.connection != ConnectionStatus.Connected) return Availability.Disabled(DisabledReason.NotConnected)
        if (!state.pinUnlock) return Availability.Disabled(DisabledReason.NotAvailable)
        if (state.unlock is UnlockStatus.Waiting || state.lock is LockStatus.Waiting) return Availability.Disabled(DisabledReason.Pending)
        if (state.pinCooldownRemainingMs > 0) return Availability.Disabled(DisabledReason.PinCooldown)
        val t = state.telemetry
        if (t == null || state.meter1AtMs == null || state.meter1Stale) return Availability.Disabled(DisabledReason.TelemetryStale)
        if (t.wheelMoving == true) return Availability.Disabled(DisabledReason.Moving)
        val speed = t.speedKmh ?: return Availability.Disabled(DisabledReason.SpeedUnknown)
        if (speed != 0f) return Availability.Disabled(DisabledReason.Moving)
        if (t.locked != true) return Availability.Disabled(DisabledReason.StateUnknown)
        return Availability.Enabled
    }

    /**
     * Bloqueio temporário modo 2 (S20): conectado, nenhum em curso, Meter1 fresco, velocidade conhecida e 0, roda parada
     * e Meter1 dizendo DESBLOQUEADO. Travar em movimento é perigoso: falha segura. O transporte repete as verificações.
     */
    fun lockAvailability(state: HomeState): Availability {
        if (state.connection != ConnectionStatus.Connected) return Availability.Disabled(DisabledReason.NotConnected)
        if (!state.lockMode2) return Availability.Disabled(DisabledReason.NotAvailable)
        if (state.lock is LockStatus.Waiting || state.unlock is UnlockStatus.Waiting) return Availability.Disabled(DisabledReason.Pending)
        val t = state.telemetry
        if (t == null || state.meter1AtMs == null || state.meter1Stale) return Availability.Disabled(DisabledReason.TelemetryStale)
        if (t.wheelMoving == true) return Availability.Disabled(DisabledReason.Moving)
        val speed = t.speedKmh ?: return Availability.Disabled(DisabledReason.SpeedUnknown)
        if (speed != 0f) return Availability.Disabled(DisabledReason.Moving)
        if (t.locked != false) return Availability.Disabled(DisabledReason.StateUnknown)
        return Availability.Enabled
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

    private fun Outcome.failedCommand(): ScooterCommand? = when (this) {
        is Outcome.Failed -> command
        is Outcome.TimedOut -> command
        is Outcome.Confirmed -> null
    }

    private fun Outcome.atMs(): Long = when (this) {
        is Outcome.Failed -> atMs
        is Outcome.TimedOut -> atMs
        is Outcome.Confirmed -> atMs
    }

    /** Só [UnlockResult.PinRejected] conta; [UnlockResult.Unlocked] zera; qualquer outro resultado não mexe no contador. */
    private fun countPinResult(state: HomeState, result: UnlockResult, nowMs: Long): HomeState = when (result) {
        UnlockResult.Unlocked -> state.copy(pinRejects = 0, pinCooldownUntilMs = null)
        UnlockResult.PinRejected -> {
            val rejects = state.pinRejects + 1
            if (rejects >= HomeRules.PIN_REJECTS_BEFORE_COOLDOWN) state.copy(pinRejects = 0, pinCooldownUntilMs = nowMs + HomeRules.PIN_COOLDOWN_MS)
            else state.copy(pinRejects = rejects)
        }
        else -> state
    }

    fun reduce(state: HomeState, event: HomeEvent): Reduction = when (event) {
        is HomeEvent.UnlockRequested ->
            if (pinUnlockAvailability(state) != Availability.Enabled) Reduction(state)
            else Reduction(state.copy(nowMs = event.nowMs, unlock = UnlockStatus.Waiting(event.nowMs), lock = null), listOf(HomeEffect.StartUnlock))
        is HomeEvent.LockRequested ->
            if (lockAvailability(state) != Availability.Enabled) Reduction(state)
            else Reduction(state.copy(nowMs = event.nowMs, lock = LockStatus.Waiting(event.nowMs), unlock = null), listOf(HomeEffect.StartLock))
        is HomeEvent.LockFinished -> Reduction(state.copy(nowMs = event.nowMs, lock = LockStatus.Finished(event.result, event.nowMs)))
        is HomeEvent.UnlockFinished -> Reduction(countPinResult(state.copy(nowMs = event.nowMs, unlock = UnlockStatus.Finished(event.result, event.nowMs)), event.result, event.nowMs))
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
            // Falha/timeout antigo cujo comando a telemetria nova já reflete deixa de valer (aviso enganoso).
            val stale = state.outcomes.filter { (_, o) -> o.failedCommand()?.let { matches(it, t) } == true && t.receivedAtMs >= o.atMs() }
            Reduction(
                state.copy(
                    telemetry = t,
                    pending = state.pending - done.keys,
                    outcomes = (state.outcomes - stale.keys) + done.mapValues { Outcome.Confirmed(t.receivedAtMs, it.value.command) },
                ),
            )
        }
        is HomeEvent.AgedTelemetryReceived -> reduce(
            state.copy(meter1AtMs = event.reading.meter1AtMs, meter2AtMs = event.reading.meter2AtMs),
            HomeEvent.TelemetryReceived(event.reading.telemetry),
        )
        is HomeEvent.GroupAgesReceived -> Reduction(state.copy(meter1AtMs = event.meter1AtMs, meter2AtMs = event.meter2AtMs))
        is HomeEvent.AckReceived -> when (val ack = event.ack) {
            Ack.Accepted -> Reduction(state)
            is Ack.Rejected -> Reduction(
                state.copy(
                    nowMs = event.nowMs,
                    pending = state.pending - event.control,
                    outcomes = state.outcomes + (event.control to Outcome.Failed(ack.reason, event.nowMs, state.pending[event.control]?.command)),
                ),
            )
        }
        is HomeEvent.Tick -> {
            val expired = state.pending.filterValues { event.nowMs - it.sentAtMs >= HomeRules.PENDING_TIMEOUT_MS }
            val keptOutcomes = state.outcomes.filterValues {
                !(it is Outcome.Confirmed && event.nowMs - it.atMs >= HomeRules.CONFIRMED_VISIBLE_MS) &&
                    // Recusa do farol (nada foi enviado) some sozinha, como a do Desbloquear/Bloquear.
                    !(it is Outcome.Failed && HeadlightAckCodes.parseRefusal(it.reason) != null && event.nowMs - it.atMs >= HomeRules.REFUSAL_VISIBLE_MS)
            }
            val unlock = state.unlock
            val unlockShown = !(unlock is UnlockStatus.Finished && unlock.result == UnlockResult.Unlocked &&
                event.nowMs - unlock.atMs >= HomeRules.CONFIRMED_VISIBLE_MS) &&
                !(unlock is UnlockStatus.Finished && unlock.result is UnlockResult.Refused && event.nowMs - unlock.atMs >= HomeRules.REFUSAL_VISIBLE_MS)
            val lock = state.lock
            val lockShown = !(lock is LockStatus.Finished && lock.result == LockResult.Locked &&
                event.nowMs - lock.atMs >= HomeRules.CONFIRMED_VISIBLE_MS) &&
                !(lock is LockStatus.Finished && lock.result is LockResult.Refused && event.nowMs - lock.atMs >= HomeRules.REFUSAL_VISIBLE_MS)
            Reduction(
                state.copy(
                    nowMs = event.nowMs,
                    pinCooldownUntilMs = state.pinCooldownUntilMs?.takeIf { event.nowMs < it },
                    lock = if (lockShown) lock else null,
                    pending = state.pending - expired.keys,
                    outcomes = keptOutcomes + expired.mapValues { Outcome.TimedOut(event.nowMs, it.value.command) },
                    unlock = if (unlockShown) unlock else null,
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
                        state.outcomes + state.pending.mapValues { Outcome.Failed(HomeRules.CONNECTION_LOST, event.nowMs, it.value.command) }
                    } else {
                        state.outcomes.filterValues { !(it is Outcome.Failed && it.reason == HomeRules.CONNECTION_LOST) }
                    },
                ),
            )
        }
    }
}

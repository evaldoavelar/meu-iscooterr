package br.dev.evaldo.meuscooter.ui

import br.dev.evaldo.meuscooter.domain.Availability
import br.dev.evaldo.meuscooter.domain.DisabledReason
import br.dev.evaldo.meuscooter.domain.HomeReducer
import br.dev.evaldo.meuscooter.domain.HomeRules
import br.dev.evaldo.meuscooter.domain.HomeState
import br.dev.evaldo.meuscooter.domain.Units
import br.dev.evaldo.meuscooter.domain.Outcome
import br.dev.evaldo.meuscooter.domain.LockStatus
import br.dev.evaldo.meuscooter.domain.UnlockStatus
import br.dev.evaldo.meuscooter.protocol.LockRefusal
import br.dev.evaldo.meuscooter.protocol.LockResult
import br.dev.evaldo.meuscooter.protocol.ConnectionStatus
import br.dev.evaldo.meuscooter.protocol.Control
import br.dev.evaldo.meuscooter.protocol.ScooterCommand
import br.dev.evaldo.meuscooter.protocol.SpeedModeOption
import br.dev.evaldo.meuscooter.protocol.UnlockRefusal
import br.dev.evaldo.meuscooter.protocol.UnlockResult

/** Converte [HomeState] em texto/habilitação prontos para a tela. Sem Android; "concluído" só com [Outcome.Confirmed]. */
class HomeUiMapper(
    private val modes: List<SpeedModeOption>,
    private val deviceName: String?,
    private val timeLabel: (Long) -> String,
) {
    fun map(state: HomeState, units: Units = Units.Metric): HomeUiState {
        val t = state.telemetry
        val live = state.connection == ConnectionStatus.Connected
        return HomeUiState(
            connection = connection(state),
            battery = t?.batteryPercent?.takeIf { live && !state.meter1Stale }?.let { "$it%" } ?: UNKNOWN,
            speed = t?.speedKmh?.takeIf { live }?.let { "${Math.round(units.speed(it))} ${units.speedLabel}" } ?: UNKNOWN,
            total = t?.totalKm?.takeIf { live }?.let { "${KM_FORMAT.format(units.distance(it).toDouble())} ${units.distanceLabel}" } ?: UNKNOWN,
            mainButton = mainButton(state),
            headlight = ToggleUi(
                pendingTarget(state, Control.Headlight) { (it as? ScooterCommand.SetHeadlight)?.on } ?: (t?.headlightOn ?: false),
                control(state, Control.Headlight, ScooterCommand.SetHeadlight(true)),
            ),
            motors = ChoiceUi(
                MOTOR_OPTIONS,
                (pendingTarget(state, Control.Motors) { (it as? ScooterCommand.SetDualMotor)?.dual } ?: t?.dualMotor)?.let { if (it) 1 else 0 },
                control(state, Control.Motors, ScooterCommand.SetDualMotor(true)),
            ),
            mode = ChoiceUi(
                modes.map { ChoiceOption(it.raw, it.label) },
                pendingTarget(state, Control.Mode) { (it as? ScooterCommand.SetSpeedMode)?.raw } ?: t?.speedModeRaw,
                control(state, Control.Mode, ScooterCommand.SetSpeedMode(0)),
            ),
            cruise = ToggleUi(
                pendingTarget(state, Control.Cruise) { (it as? ScooterCommand.SetCruise)?.on } ?: (t?.cruiseOn ?: false),
                control(state, Control.Cruise, ScooterCommand.SetCruise(true)),
            ),
            zeroStart = ToggleUi(
                pendingTarget(state, Control.ZeroStart) { (it as? ScooterCommand.SetZeroStart)?.on } ?: (t?.zeroStartOn ?: false),
                control(state, Control.ZeroStart, ScooterCommand.SetZeroStart(true)),
            ),
            limit = LimitUi(
                pendingTarget(state, Control.Limit) { (it as? ScooterCommand.SetSpeedLimit)?.kmh } ?: t?.speedLimitKmh ?: LimitUi.RANGE.first,
                control(state, Control.Limit, ScooterCommand.SetSpeedLimit(LimitUi.RANGE.first)),
                units,
            ),
            telemetryStale = state.connection == ConnectionStatus.Connected && t != null &&
                state.nowMs - t.receivedAtMs > HomeRules.STALE_AFTER_MS,
            notice = notice(state),
        )
    }

    /**
     * Comando a repetir em "Tentar de novo": o que falhou/expirou, nunca o oposto do estado atual da telemetria
     * (uma confirmação tardia pode já ter mudado `locked`). Sem comando registrado, inverte o último estado conhecido.
     */
    fun retryCommand(state: HomeState): ScooterCommand? {
        val failed = when (val o = state.outcomes[Control.Lock]) {
            is Outcome.Failed -> o.command
            is Outcome.TimedOut -> o.command
            else -> null
        }
        return failed ?: state.telemetry?.locked?.let { ScooterCommand.SetLocked(!it) }
    }

    /** Mesma regra dos controles restritos, ignorando pendências (que só adicionam o motivo "Pending"). */
    private fun notice(s: HomeState): String? =
        (HomeReducer.availability(s.copy(pending = emptyMap()), ScooterCommand.SetSpeedLimit(LimitUi.RANGE.first)) as? Availability.Disabled)
            ?.reason?.let(::reasonText)

    private fun connection(s: HomeState): ConnectionUi {
        val named = deviceName?.let { " · $it" } ?: ""
        val lockOutcome = s.outcomes[Control.Lock]
        val unlockResult = (s.unlock as? UnlockStatus.Finished)?.result
        val lockResult = (s.lock as? LockStatus.Finished)?.result
        return when {
            s.connection == ConnectionStatus.Connecting ->
                ConnectionUi(ConnectionKind.Connecting, deviceName?.let { "Conectando ao $it…" } ?: "Conectando…")
            unlockResult is UnlockResult.Unconfirmed || lockResult is LockResult.Unconfirmed ->
                ConnectionUi(ConnectionKind.Error, "Sem confirmação — estado de bloqueio desconhecido")
            s.connection == ConnectionStatus.Disconnected -> ConnectionUi(ConnectionKind.Disconnected, "Desconectado")
            unlockResult == UnlockResult.PinRejected -> ConnectionUi(ConnectionKind.Error, "PIN incorreto")
            unlockResult == UnlockResult.Refused(UnlockRefusal.PendingReception) ->
                ConnectionUi(ConnectionKind.Error, "Aguarde a leitura do patinete")
            unlockResult is UnlockResult.Refused -> ConnectionUi(ConnectionKind.Error, "Desbloqueio não enviado")
            lockResult == LockResult.Rejected -> ConnectionUi(ConnectionKind.Error, "O patinete recusou o bloqueio")
            lockResult is LockResult.Refused -> ConnectionUi(ConnectionKind.Error, "Bloqueio não enviado: ${lockRefusalText(lockResult.reason)}")
            lockOutcome is Outcome.Failed -> ConnectionUi(ConnectionKind.Error, "O patinete recusou o comando")
            lockOutcome is Outcome.TimedOut -> ConnectionUi(ConnectionKind.Error, "Sem resposta do patinete")
            else -> ConnectionUi(ConnectionKind.Connected, "Conectado$named")
        }
    }

    private fun <T> pendingTarget(s: HomeState, c: Control, pick: (ScooterCommand) -> T?): T? =
        s.pending[c]?.let { pick(it.command) }

    private fun control(s: HomeState, c: Control, probe: ScooterCommand): ControlUi {
        val availability = HomeReducer.availability(s, probe)
        val enabled = availability == Availability.Enabled
        val reason = (availability as? Availability.Disabled)?.reason?.let(::reasonText)
        val outcome = s.outcomes[c]
        val status = when {
            c in s.pending -> ControlStatus.Pending
            outcome is Outcome.Confirmed -> ControlStatus.Confirmed
            outcome is Outcome.Failed -> ControlStatus.Failed
            outcome is Outcome.TimedOut -> ControlStatus.TimedOut
            else -> ControlStatus.Idle
        }
        val message = when {
            outcome is Outcome.Failed && outcome.reason == HomeRules.CONNECTION_LOST -> "Conexão perdida"
            outcome is Outcome.Failed -> "O patinete recusou o comando"
            outcome is Outcome.TimedOut -> "Sem resposta do patinete"
            else -> null
        }
        return ControlUi(status, enabled, reason, message)
    }

    private fun reasonText(r: DisabledReason): String? = when (r) {
        DisabledReason.NotConnected -> "Conecte ao patinete para usar"
        DisabledReason.Pending -> null
        DisabledReason.Moving -> "Pare o patinete para alterar"
        DisabledReason.SpeedUnknown -> "Velocidade desconhecida — aguarde o patinete"
        DisabledReason.TelemetryStale -> "Dados desatualizados — aguardando o patinete"
        DisabledReason.StateUnknown -> "Estado do bloqueio desconhecido"
        DisabledReason.NotAvailable -> "Bloqueio pelo app ainda não disponível"
    }

    private fun lockRefusalText(r: LockRefusal): String = when (r) {
        LockRefusal.Moving -> "pare o patinete"
        LockRefusal.SpeedUnknown -> "velocidade desconhecida"
        LockRefusal.TelemetryStale -> "dados desatualizados"
        LockRefusal.LinkNotReady -> "conexão não pronta"
        LockRefusal.Busy -> "outro comando em curso"
        LockRefusal.PendingReception -> "aguarde a leitura do patinete"
        LockRefusal.AlreadyLocked -> "já bloqueado"
        LockRefusal.TxDisabled, LockRefusal.NotSupported -> "indisponível neste modo"
    }

    /**
     * Transporte com bloqueio modo 2 mas sem desbloqueio por PIN: o botão de bloqueio (Bloquear/Bloqueando…/Bloqueado às)
     * vem de [pinMainButton]; o ramo de DESBLOQUEIO com PIN continua exclusivo de `pinUnlock` (estado travado/desconhecido
     * segue no botão legado).
     */
    private fun usesLockMode2Button(s: HomeState): Boolean =
        s.lockMode2 && (s.lock is LockStatus.Waiting || lockConfirmationVisible(s) || s.telemetry?.locked == false)

    private fun lockConfirmationVisible(s: HomeState): Boolean {
        val l = s.lock as? LockStatus.Finished ?: return false
        return l.result == LockResult.Locked && s.nowMs - l.atMs in 0L until HomeRules.CONFIRMED_VISIBLE_MS
    }

    private fun mainButton(s: HomeState): MainButtonUi {
        val outcome = s.outcomes[Control.Lock]
        val locked = s.telemetry?.locked
        return when {
            s.connection == ConnectionStatus.Connecting -> MainButtonUi(MainButtonKind.Connecting, "Conectando…", false)
            s.connection == ConnectionStatus.Disconnected -> MainButtonUi(MainButtonKind.Search, "Procurar patinete", true)
            s.pinUnlock || usesLockMode2Button(s) -> pinMainButton(s)
            outcome is Outcome.Failed || outcome is Outcome.TimedOut -> {
                val a = HomeReducer.availability(s, retryCommand(s) ?: ScooterCommand.SetLocked(locked != true))
                MainButtonUi(MainButtonKind.Retry, "Tentar de novo", a == Availability.Enabled, (a as? Availability.Disabled)?.reason?.let(::reasonText))
            }
            Control.Lock in s.pending -> MainButtonUi(MainButtonKind.Pending, "Aguardando o patinete…", false)
            outcome is Outcome.Confirmed -> {
                // O texto vem do alvo confirmado: telemetria posterior pode inverter ou apagar `locked` durante a exibição.
                val what = when ((outcome.command as? ScooterCommand.SetLocked)?.locked) {
                    true -> "Bloqueado"
                    false -> "Desbloqueado"
                    null -> "Concluído"
                }
                MainButtonUi(MainButtonKind.Confirmed, "$what às ${timeLabel(outcome.atMs)}", false)
            }
            // Estado desconhecido também oferece Desbloquear (decisão do proprietário); o reducer bloqueia em movimento.
            locked != false -> {
                val a = HomeReducer.availability(s, ScooterCommand.SetLocked(false))
                MainButtonUi(MainButtonKind.Unlock, "Desbloquear", a == Availability.Enabled, (a as? Availability.Disabled)?.reason?.let(::reasonText))
            }
            else -> {
                val a = HomeReducer.availability(s, ScooterCommand.SetLocked(true))
                val moving = (a as? Availability.Disabled)?.reason == DisabledReason.Moving
                MainButtonUi(
                    if (moving) MainButtonKind.Moving else MainButtonKind.Lock,
                    if (moving) "Pare para bloquear" else "Bloquear",
                    a == Availability.Enabled,
                    if (moving) null else (a as? Availability.Disabled)?.reason?.let(::reasonText),
                )
            }
        }
    }

    /**
     * Modo PIN (4.4): DESBLOQUEAR só com o patinete bloqueado (Meter1), parado e com dados frescos; BLOQUEAR (S20, modo 2,
     * sem PIN) só com o transporte oferecendo-o, desbloqueado, parado e com dados frescos; sem ele, "Bloquear" fica
     * desabilitado. "Desbloqueado"/"Bloqueado" só com o resultado confirmado pelo Meter1 (regra 5).
     */
    private fun pinMainButton(s: HomeState): MainButtonUi {
        val u = s.unlock
        val l = s.lock
        return when {
            u is UnlockStatus.Waiting -> MainButtonUi(MainButtonKind.Pending, "Enviado — aguardando o patinete…", false)
            l is LockStatus.Waiting -> MainButtonUi(MainButtonKind.Pending, "Bloqueando…", false)
            l is LockStatus.Finished && lockConfirmationVisible(s) ->
                MainButtonUi(MainButtonKind.Confirmed, "Bloqueado às ${timeLabel(l.atMs)}", false)
            u is UnlockStatus.Finished && u.result == UnlockResult.Unlocked ->
                MainButtonUi(MainButtonKind.Confirmed, "Desbloqueado às ${timeLabel(u.atMs)}", false)
            s.telemetry?.locked == false && s.lockMode2 -> {
                val a = HomeReducer.lockAvailability(s)
                val moving = (a as? Availability.Disabled)?.reason == DisabledReason.Moving
                MainButtonUi(
                    if (moving) MainButtonKind.Moving else MainButtonKind.Lock,
                    if (moving) "Pare para bloquear" else "Bloquear",
                    a == Availability.Enabled,
                    if (moving) null else (a as? Availability.Disabled)?.reason?.let(::reasonText),
                )
            }
            s.telemetry?.locked == false ->
                MainButtonUi(MainButtonKind.Lock, "Bloquear", false, reasonText(DisabledReason.NotAvailable))
            else -> {
                val a = HomeReducer.pinUnlockAvailability(s)
                MainButtonUi(
                    MainButtonKind.Unlock, "Desbloquear", a == Availability.Enabled,
                    (a as? Availability.Disabled)?.reason?.let(::reasonText), asksPin = true,
                )
            }
        }
    }

    private companion object {
        const val UNKNOWN = "--"
        val KM_FORMAT = java.text.DecimalFormat("#,##0", java.text.DecimalFormatSymbols(java.util.Locale.forLanguageTag("pt-BR")))
        val MOTOR_OPTIONS = listOf(ChoiceOption(0, "1 motor"), ChoiceOption(1, "2 motores"))
    }
}

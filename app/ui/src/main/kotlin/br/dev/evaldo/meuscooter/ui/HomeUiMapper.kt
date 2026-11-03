package br.dev.evaldo.meuscooter.ui

import br.dev.evaldo.meuscooter.domain.Availability
import br.dev.evaldo.meuscooter.domain.DisabledReason
import br.dev.evaldo.meuscooter.domain.HomeReducer
import br.dev.evaldo.meuscooter.domain.HomeRules
import br.dev.evaldo.meuscooter.domain.HomeState
import br.dev.evaldo.meuscooter.domain.Outcome
import br.dev.evaldo.meuscooter.protocol.ConnectionStatus
import br.dev.evaldo.meuscooter.protocol.Control
import br.dev.evaldo.meuscooter.protocol.ScooterCommand
import br.dev.evaldo.meuscooter.protocol.SpeedModeOption

/** Converte [HomeState] em texto/habilitação prontos para a tela. Sem Android; "concluído" só com [Outcome.Confirmed]. */
class HomeUiMapper(
    private val modes: List<SpeedModeOption>,
    private val deviceName: String?,
    private val timeLabel: (Long) -> String,
) {
    fun map(state: HomeState): HomeUiState {
        val t = state.telemetry
        val live = state.connection == ConnectionStatus.Connected
        return HomeUiState(
            connection = connection(state),
            battery = t?.batteryPercent?.takeIf { live }?.let { "$it%" } ?: UNKNOWN,
            speed = t?.speedKmh?.takeIf { live }?.let { "${Math.round(it)} km/h" } ?: UNKNOWN,
            total = t?.totalKm?.takeIf { live }?.let { "${KM_FORMAT.format(it.toDouble())} km" } ?: UNKNOWN,
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
            ),
            telemetryStale = state.connection == ConnectionStatus.Connected && t != null &&
                state.nowMs - t.receivedAtMs > HomeRules.STALE_AFTER_MS,
            notice = notice(state),
        )
    }

    /** Mesma regra dos controles restritos, ignorando pendências (que só adicionam o motivo "Pending"). */
    private fun notice(s: HomeState): String? =
        (HomeReducer.availability(s.copy(pending = emptyMap()), ScooterCommand.SetSpeedLimit(LimitUi.RANGE.first)) as? Availability.Disabled)
            ?.reason?.let(::reasonText)

    private fun connection(s: HomeState): ConnectionUi {
        val named = deviceName?.let { " · $it" } ?: ""
        val lockOutcome = s.outcomes[Control.Lock]
        return when {
            s.connection == ConnectionStatus.Connecting ->
                ConnectionUi(ConnectionKind.Connecting, deviceName?.let { "Conectando ao $it…" } ?: "Conectando…")
            s.connection == ConnectionStatus.Disconnected -> ConnectionUi(ConnectionKind.Disconnected, "Desconectado")
            lockOutcome is Outcome.Failed || lockOutcome is Outcome.TimedOut ->
                ConnectionUi(ConnectionKind.Error, "Sem resposta do patinete")
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
    }

    private fun mainButton(s: HomeState): MainButtonUi {
        val outcome = s.outcomes[Control.Lock]
        val locked = s.telemetry?.locked
        return when {
            s.connection == ConnectionStatus.Connecting -> MainButtonUi(MainButtonKind.Connecting, "Conectando…", false)
            s.connection == ConnectionStatus.Disconnected -> MainButtonUi(MainButtonKind.Search, "Procurar patinete", true)
            outcome is Outcome.Failed || outcome is Outcome.TimedOut -> {
                val a = HomeReducer.availability(s, ScooterCommand.SetLocked(locked != true))
                MainButtonUi(MainButtonKind.Retry, "Tentar de novo", a == Availability.Enabled, (a as? Availability.Disabled)?.reason?.let(::reasonText))
            }
            Control.Lock in s.pending -> MainButtonUi(MainButtonKind.Pending, "Aguardando o patinete…", false)
            outcome is Outcome.Confirmed -> {
                val what = if (locked == true) "Bloqueado" else "Desbloqueado"
                MainButtonUi(MainButtonKind.Confirmed, "$what às ${timeLabel(outcome.atMs)}", false)
            }
            locked == true -> {
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

    private companion object {
        const val UNKNOWN = "--"
        val KM_FORMAT = java.text.DecimalFormat("#,##0", java.text.DecimalFormatSymbols(java.util.Locale.forLanguageTag("pt-BR")))
        val MOTOR_OPTIONS = listOf(ChoiceOption(0, "1 motor"), ChoiceOption(1, "2 motores"))
    }
}

package br.dev.evaldo.meuscooter.ui

import br.dev.evaldo.meuscooter.domain.Availability
import br.dev.evaldo.meuscooter.domain.DisabledReason
import br.dev.evaldo.meuscooter.domain.HomeReducer
import br.dev.evaldo.meuscooter.domain.HomeRules
import br.dev.evaldo.meuscooter.domain.HomeState
import br.dev.evaldo.meuscooter.domain.SettingAckCodes
import br.dev.evaldo.meuscooter.domain.Units
import br.dev.evaldo.meuscooter.protocol.CommandRefusal
import br.dev.evaldo.meuscooter.domain.Outcome
import br.dev.evaldo.meuscooter.domain.LockStatus
import br.dev.evaldo.meuscooter.domain.UnlockStatus
import br.dev.evaldo.meuscooter.protocol.HeadlightAckCodes
import br.dev.evaldo.meuscooter.protocol.HeadlightRefusal
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
        // HIPÓTESE (S31): com o patinete em mph os campos de velocidade/odômetro chegam em mph/mi; mostra-se o valor cru com o
        // rótulo do patinete, sem converter nem aplicar a preferência (ADR 0009). Só b12 (limite) está confirmado em mph.
        val scooterMph = t?.speedUnitMph == true
        return HomeUiState(
            connection = connection(state),
            battery = t?.batteryPercent?.takeIf { live && !state.meter1Stale }?.let { "$it%" } ?: UNKNOWN,
            speed = t?.speedKmh?.takeIf { live }?.let { if (scooterMph) "${Math.round(it)} mph" else "${Math.round(units.speed(it))} ${units.speedLabel}" } ?: UNKNOWN,
            total = t?.totalKm?.takeIf { live && !state.meter2Stale }?.let {
                if (scooterMph) "${KM_FORMAT.format(it.toDouble())} mi" else "${KM_FORMAT.format(units.distance(it).toDouble())} ${units.distanceLabel}"
            } ?: UNKNOWN,
            mainButton = mainButton(state),
            headlight = ToggleUi(
                pendingTarget(state, Control.Headlight) { (it as? ScooterCommand.SetHeadlight)?.on } ?: (t?.headlightOn ?: false),
                control(state, Control.Headlight, ScooterCommand.SetHeadlight(true)),
            ),
            motors = ChoiceUi(
                MOTOR_OPTIONS,
                (pendingTarget(state, Control.Motors) { (it as? ScooterCommand.SetDualMotor)?.dual } ?: t?.dualMotor)?.let { if (it) 1 else 0 },
                control(state, Control.Motors, ScooterCommand.SetDualMotor(true)),
                if (state.sendsViaTransportApi(ScooterCommand.SetDualMotor(true))) DRIVE_CONFIRMATIONS else emptyMap(),
            ),
            unit = if (state.unitAvailable) ChoiceUi(
                UNIT_OPTIONS,
                (pendingTarget(state, Control.Unit) { (it as? ScooterCommand.SetSpeedUnit)?.mph } ?: t?.speedUnitMph)?.let { if (it) 1 else 0 },
                control(state, Control.Unit, ScooterCommand.SetSpeedUnit(true)),
            ) else null,
            mode = mode(state),
            cruise = ToggleUi(
                pendingTarget(state, Control.Cruise) { (it as? ScooterCommand.SetCruise)?.on } ?: (t?.cruiseOn ?: false),
                control(state, Control.Cruise, ScooterCommand.SetCruise(true)),
            ),
            zeroStart = ToggleUi(
                pendingTarget(state, Control.ZeroStart) { (it as? ScooterCommand.SetZeroStart)?.on } ?: (t?.zeroStartOn ?: false),
                control(state, Control.ZeroStart, ScooterCommand.SetZeroStart(true)),
            ),
            limit = limit(state, units),
            telemetryStale = state.connection == ConnectionStatus.Connected && t != null &&
                state.nowMs - t.receivedAtMs > HomeRules.STALE_AFTER_MS,
            notice = notice(state),
        )
    }

    /** Modo: com o transporte real só os modos da máscara do Meter2 (nomes `[ESTÁTICO]`); senão o catálogo do transporte. */
    private fun mode(s: HomeState): ChoiceUi {
        val pending = pendingTarget(s, Control.Mode) { (it as? ScooterCommand.SetSpeedMode)?.raw }
        val selected = pending ?: s.telemetry?.speedModeRaw
        if (!s.sendsViaTransportApi(ScooterCommand.SetSpeedMode(0))) {
            return ChoiceUi(modes.map { ChoiceOption(it.raw, it.label) }, selected, control(s, Control.Mode, ScooterCommand.SetSpeedMode(0)))
        }
        val mask = s.telemetry?.availableModes
        val options = DRIVE_MODE_LABELS.entries.filter { mask != null && mask and (1 shl it.key) != 0 }.map { ChoiceOption(it.key, it.value) }
        return ChoiceUi(options, selected, control(s, Control.Mode, ScooterCommand.SetSpeedMode(options.firstOrNull()?.raw ?: 0)))
    }

    /** Limite: faixa pelo número de motores (15..60 / 20..50), teto 60 na barra; só km/h; em mph mostra o valor cru. */
    private fun limit(s: HomeState, units: Units): LimitUi {
        val t = s.telemetry
        val range = HomeRules.limitRange(t?.dualMotor) ?: LimitUi.RANGE.first..50
        val shown = pendingTarget(s, Control.Limit) { (it as? ScooterCommand.SetSpeedLimit)?.kmh } ?: t?.speedLimitKmh ?: range.first
        return LimitUi(
            shown,
            control(s, Control.Limit, ScooterCommand.SetSpeedLimit(range.first)),
            units,
            minKmh = range.first,
            maxKmh = range.last,
            hint = if (range.last < LimitUi.CEILING && t?.speedUnitMph != true) "Acima de ${range.last} km/h só com 2 motores" else null,
            scooterMph = t?.speedUnitMph == true,
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
    private fun notice(s: HomeState): String? = refusalNotice(s) ?:
        (HomeReducer.availability(s.copy(pending = emptyMap(), commandsAvailable = true), ScooterCommand.SetSpeedLimit(LimitUi.RANGE.first)) as? Availability.Disabled)
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
        val reason = (availability as? Availability.Disabled)?.reason?.let { reasonText(it, c) }
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
            outcome is Outcome.Failed -> failedMessage(outcome.reason, c)
            outcome is Outcome.TimedOut -> "Sem resposta do patinete"
            else -> null
        }
        return ControlUi(status, enabled, reason, message)
    }

    /** Falha do comando: recusa do farol (nada enviado) e falta de confirmação têm texto próprio; o resto é recusa do patinete. */
    private fun failedMessage(reason: String, c: Control): String =
        HeadlightAckCodes.parseRefusal(reason)?.let(::headlightRefusalNotice)
            ?: SettingAckCodes.parseRefusal(reason)?.let { settingRefusalNotice(it, c) }
            ?: when {
                HeadlightAckCodes.isUnconfirmed(reason) -> "Sem confirmação do patinete — estado do farol desconhecido"
                SettingAckCodes.isUnconfirmed(reason) ->
                    "Sem confirmação do patinete — ${SETTING_WHAT.getValue(c).unconfirmed} pode não ter mudado. Confira o estado antes de tentar de novo"
                reason == SettingAckCodes.REJECTED -> "O patinete recusou ${SETTING_WHAT[c]?.rejected ?: "o comando"}"
                else -> "O patinete recusou o comando"
            }

    /** Recusa do transporte para unidade/modo/limite/tração (nada foi enviado). */
    private fun settingRefusalNotice(r: CommandRefusal, c: Control): String {
        val what = SETTING_WHAT[c]?.target ?: "a configuração"
        return when (r) {
            CommandRefusal.LinkNotReady -> "Conexão ainda não está pronta. Tente de novo em instantes"
            CommandRefusal.TelemetryStale -> "Aguarde a telemetria do patinete e tente de novo"
            CommandRefusal.SpeedUnknown -> "Velocidade desconhecida. Aguarde a telemetria e tente de novo"
            CommandRefusal.Moving -> "Pare o patinete para alterar $what"
            CommandRefusal.PendingReception -> "Aguarde a leitura do patinete e tente de novo"
            CommandRefusal.Busy -> "Outro comando está em andamento. Aguarde"
            CommandRefusal.Locked -> "Desbloqueie o patinete para alterar $what"
            CommandRefusal.UnitMustBeKmh -> "Troque a unidade para km/h para ajustar o limite"
            CommandRefusal.RangeUnknown -> "Faixa do limite desconhecida. Aguarde o patinete e tente de novo"
            CommandRefusal.OutOfRange -> "Valor fora da faixa do patinete (só com 2 motores acima de 50 km/h)"
            CommandRefusal.ModeUnavailable -> "Esse modo não está disponível neste patinete"
            CommandRefusal.InvalidValue -> "Valor inválido para $what"
            CommandRefusal.TxDisabled, CommandRefusal.NotSupported -> "Alteração indisponível neste modo"
        }
    }

    private fun headlightRefusalNotice(r: HeadlightRefusal): String = when (r) {
        HeadlightRefusal.LinkNotReady -> "Conexão ainda não está pronta. Tente de novo em instantes"
        HeadlightRefusal.TelemetryStale -> "Aguarde a telemetria do patinete e tente de novo"
        HeadlightRefusal.SpeedUnknown -> "Velocidade desconhecida. Aguarde a telemetria e tente de novo"
        HeadlightRefusal.Moving -> "Pare o patinete para mexer no farol"
        HeadlightRefusal.PendingReception -> "Aguarde a leitura do patinete e tente de novo"
        HeadlightRefusal.Busy -> "Outro comando está em andamento. Aguarde"
        HeadlightRefusal.Locked -> "Desbloqueie o patinete para usar o farol"
        HeadlightRefusal.TxDisabled, HeadlightRefusal.NotSupported -> "Farol indisponível neste modo"
    }

    /** Segundos restantes arredondados para cima (nunca "0 s" enquanto ainda há espera). */
    private fun pinCooldownText(s: HomeState): String = "Aguarde ${(s.pinCooldownRemainingMs + 999) / 1000} s para tentar de novo"

    private fun reasonText(r: DisabledReason, c: Control? = null): String? = when (r) {
        DisabledReason.NotConnected -> "Conecte ao patinete para usar"
        DisabledReason.Pending -> null
        DisabledReason.Moving -> "Pare o patinete para alterar"
        DisabledReason.SpeedUnknown -> "Velocidade desconhecida — aguarde o patinete"
        DisabledReason.TelemetryStale -> "Dados desatualizados — aguardando o patinete"
        DisabledReason.StateUnknown -> "Estado do bloqueio desconhecido"
        DisabledReason.NotAvailable -> "Bloqueio pelo app ainda não disponível"
        DisabledReason.CommandsUnavailable -> "Este comando ainda não está disponível com o patinete real"
        DisabledReason.Locked -> if (c == null || c == Control.Headlight) "Desbloqueie o patinete para usar o farol" else "Desbloqueie o patinete para alterar"
        DisabledReason.ModeUnavailable -> "Modo indisponível neste patinete"
        DisabledReason.UnitMustBeKmh -> "Troque a unidade para km/h para ajustar o limite"
        DisabledReason.UnitUnknown -> "Unidade do patinete desconhecida"
        DisabledReason.RangeUnknown -> "Faixa do limite desconhecida — aguarde o patinete"
        DisabledReason.LimitOutOfRange -> "Valor fora da faixa do patinete"
        DisabledReason.PinCooldown -> "Aguarde alguns segundos para tentar de novo"   // com contagem: ver pinCooldownText
    }

    /** Aviso específico de Desbloquear/Bloquear RECUSADO antes de escrever (nada foi enviado; não conta como PIN errado). */
    private fun refusalNotice(s: HomeState): String? {
        val u = ((s.unlock as? UnlockStatus.Finished)?.result as? UnlockResult.Refused)?.reason
        if (u != null) return unlockRefusalNotice(u)
        val l = ((s.lock as? LockStatus.Finished)?.result as? LockResult.Refused)?.reason
        return l?.let(::lockRefusalNotice)
    }

    private fun unlockRefusalNotice(r: UnlockRefusal): String = when (r) {
        UnlockRefusal.LinkNotReady -> "Conexão ainda não está pronta. Tente de novo em instantes"
        UnlockRefusal.TelemetryStale -> "Aguarde a telemetria do patinete e tente de novo"
        UnlockRefusal.SpeedUnknown -> "Velocidade desconhecida. Aguarde a telemetria e tente de novo"
        UnlockRefusal.Moving -> "Pare o patinete para desbloquear"
        UnlockRefusal.PendingReception -> "Aguarde a leitura do patinete e tente de novo"
        UnlockRefusal.Busy -> "Outro comando está em andamento. Aguarde"
        UnlockRefusal.NotLocked -> "O patinete já está desbloqueado"
        UnlockRefusal.TxDisabled, UnlockRefusal.NotSupported -> "Desbloqueio indisponível neste modo"
    }

    private fun lockRefusalNotice(r: LockRefusal): String = when (r) {
        LockRefusal.LinkNotReady -> "Conexão ainda não está pronta. Tente de novo em instantes"
        LockRefusal.TelemetryStale -> "Aguarde a telemetria do patinete e tente de novo"
        LockRefusal.SpeedUnknown -> "Velocidade desconhecida. Aguarde a telemetria e tente de novo"
        LockRefusal.Moving -> "Pare o patinete para bloquear"
        LockRefusal.PendingReception -> "Aguarde a leitura do patinete e tente de novo"
        LockRefusal.Busy -> "Outro comando está em andamento. Aguarde"
        LockRefusal.AlreadyLocked -> "O patinete já está bloqueado"
        LockRefusal.TxDisabled, LockRefusal.NotSupported -> "Bloqueio indisponível neste modo"
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
                    (a as? Availability.Disabled)?.reason?.let { if (it == DisabledReason.PinCooldown) pinCooldownText(s) else reasonText(it) },
                    asksPin = true,
                )
            }
        }
    }

    private companion object {
        const val UNKNOWN = "--"
        val KM_FORMAT = java.text.DecimalFormat("#,##0", java.text.DecimalFormatSymbols(java.util.Locale.forLanguageTag("pt-BR")))
        val MOTOR_OPTIONS = listOf(ChoiceOption(0, "1 motor"), ChoiceOption(1, "2 motores"))
        val UNIT_OPTIONS = listOf(ChoiceOption(0, "km/h"), ChoiceOption(1, "mph"))

        /** Nomes do original (`Walking/Eco/Balanced/Sports`, `[ESTÁTICO]` S31) para o CMD 0x35 valores 0..3. */
        val DRIVE_MODE_LABELS = mapOf(0 to "Caminhada", 1 to "Eco", 2 to "Equilibrado", 3 to "Esportivo")

        /** Perguntas Sim/Não antes de enviar a tração (S31: 2 motores = mais potência e teto de 60 km/h; o limite muda sozinho). */
        val DRIVE_CONFIRMATIONS = mapOf(
            1 to "Trocar para 2 motores? Há mais potência e o teto do limite sobe para 60 km/h (o limite atual pode subir sozinho). Use só com o patinete parado.",
            0 to "Trocar para 1 motor? O teto do limite volta para 50 km/h (o limite atual pode baixar sozinho).",
        )

        class SettingWhat(val target: String, val rejected: String, val unconfirmed: String)
        val SETTING_WHAT = mapOf(
            Control.Unit to SettingWhat("a unidade", "a mudança de unidade", "a unidade"),
            Control.Mode to SettingWhat("o modo", "a mudança de modo", "o modo"),
            Control.Limit to SettingWhat("o limite", "a mudança de limite", "o limite"),
            Control.Motors to SettingWhat("a tração", "a mudança de tração", "a tração"),
        )
    }
}

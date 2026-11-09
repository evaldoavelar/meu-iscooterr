package br.dev.evaldo.meuscooter.protocol

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map

/** Relógio injetável (ms monotônicos); permite tempo virtual nos testes. */
fun interface Clock {
    fun nowMs(): Long
}

enum class ConnectionStatus { Disconnected, Connecting, Connected }

/** Controles que a Home expõe. Cada um tem no máximo uma operação pendente. */
enum class Control { Lock, Headlight, Motors, Mode, Cruise, ZeroStart, Limit, Unit }

/** Comandos de alto nível; a codificação em bytes é assunto da camada real (Fase 4). */
sealed interface ScooterCommand {
    val control: Control

    data class SetLocked(val locked: Boolean) : ScooterCommand {
        override val control get() = Control.Lock
    }

    data class SetHeadlight(val on: Boolean) : ScooterCommand {
        override val control get() = Control.Headlight
    }

    /** true = os dois motores. */
    data class SetDualMotor(val dual: Boolean) : ScooterCommand {
        override val control get() = Control.Motors
    }

    /** Valor bruto do CMD 0x35 (0..3). Nome associado: só em [SpeedModeCatalog]. */
    data class SetSpeedMode(val raw: Int) : ScooterCommand {
        override val control get() = Control.Mode
    }

    data class SetCruise(val on: Boolean) : ScooterCommand {
        override val control get() = Control.Cruise
    }

    data class SetZeroStart(val on: Boolean) : ScooterCommand {
        override val control get() = Control.ZeroStart
    }

    data class SetSpeedLimit(val kmh: Int) : ScooterCommand {
        override val control get() = Control.Limit
    }

    /** Unidade de velocidade do patinete (S31): true = mph. Sempre pela API estreita [ScooterTransport.setUnit]. */
    data class SetSpeedUnit(val mph: Boolean) : ScooterCommand {
        override val control get() = Control.Unit
    }
}

/** Instantâneo de telemetria; `null` = campo desconhecido. [receivedAtMs] vem do [Clock] do transporte. */
data class Telemetry(
    val receivedAtMs: Long,
    val locked: Boolean? = null,
    val speedKmh: Float? = null,
    val batteryPercent: Int? = null,
    val headlightOn: Boolean? = null,
    /** S31 Meter1 byte[15] bit 4: true = dois motores. */
    val dualMotor: Boolean? = null,
    /** S31 Meter1 byte[15] bits 0..2; apenas enviar 0..3 é autorizado. */
    val speedModeRaw: Int? = null,
    val cruiseOn: Boolean? = null,
    val zeroStartOn: Boolean? = null,
    val speedLimitKmh: Int? = null,
    /** S31 Meter1 byte[15] bit 3: true = mph; null = status desconhecido. */
    val speedUnitMph: Boolean? = null,
    /** S31 Meter2 byte[20]: máscara dos modos autorizados; null = desconhecida. */
    val availableModes: Int? = null,
    /** Odômetro (Meter2 dado[8..9], s16 km) `[ESTÁTICO]` + coerente com o painel; ver S29-meter2-decodificacao.md. */
    val totalKm: Float? = null,
    /** Meter2 (S29): tensão/corrente/temp. do motor `[ESTÁTICO]` com plausibilidade em captura real; o Fake simula. */
    val voltageV: Float? = null,
    val currentA: Float? = null,
    val motor1TempC: Int? = null,
    val motor2TempC: Int? = null,
    /** Potência em W (Meter2 dado[0..1], s16 x 0,1) `[HIPÓTESE forte]`: unidade só fechada por V x I; sem medição externa. */
    val powerW: Float? = null,
    /** S19: Meter1 dado[4..6] todos zero = parado [CONFIRMADO]; não implica escala em km/h. */
    val wheelMoving: Boolean? = null,
)

/** Resposta de recebimento (ACK). NÃO significa executado: só a telemetria confirma. */
sealed interface Ack {
    data object Accepted : Ack
    data class Rejected(val reason: String) : Ack
}

/** Associação valor bruto ↔ rótulo. Isolada da UI; provisória até validação `[HIPÓTESE]`. */
data class SpeedModeOption(val raw: Int, val label: String)

fun interface SpeedModeCatalog {
    fun options(): List<SpeedModeOption>
}

/** Fronteira com o patinete. Implementações: simulada (Fake) agora; BLE real na Fase 4. */
interface ScooterTransport {
    val connection: StateFlow<ConnectionStatus>
    val telemetry: Flow<Telemetry>

    /**
     * Telemetria acumulada com a idade de cada grupo de campos. [telemetry] sozinho não basta: o `receivedAtMs` dele é o
     * do último quadro de QUALQUER grupo, então bateria/bloqueio (Meter1) parados pareceriam frescos. O padrão serve a
     * transportes que sempre entregam todos os campos juntos (o simulado).
     */
    val agedTelemetry: Flow<AgedTelemetry> get() = telemetry.map { AgedTelemetry(it, it.receivedAtMs, it.receivedAtMs) }
    val speedModes: SpeedModeCatalog

    /** Premissa (codex-r1): deve RETORNAR ao conectar ou ao falhar; o `HomeController` ignora novo `connect()` enquanto este estiver ativo. */
    suspend fun connect()
    suspend fun disconnect()

    /** Retorna após o ACK; a confirmação de estado chega depois em [telemetry]. */
    suspend fun send(command: ScooterCommand): Ack

    /** `false` se [send] não envia comandos genéricos (BLE real: só [lock]/[unlock]); a Home os desabilita com motivo claro. */
    val genericCommandsAvailable: Boolean get() = true

    /** `true` se este transporte oferece [unlock] (a Home então pede o PIN em vez de usar [send]). */
    val pinUnlockAvailable: Boolean get() = false

    /**
     * Desbloqueio com PIN (etapa 4.4): API estreita, separada de [send] (que segue recusando comandos genéricos).
     * Uma única tentativa, sem retry. Não guarda nem registra o PIN; quem chama o apaga ([Pin.wipe]) depois.
     */
    suspend fun unlock(pin: Pin): UnlockResult = UnlockResult.Refused(UnlockRefusal.NotSupported)

    /** `true` se este transporte oferece [lock] (bloqueio temporário, modo 2, sem PIN; S20). */
    val lockAvailable: Boolean get() = false

    /**
     * Bloqueio temporário (LOCK modo 2, sem PIN; S20): API estreita, separada de [send]. Uma única tentativa, sem retry.
     * Só [LockResult.Locked] significa executado (Meter1 novo com o bit de travado; regra 5).
     */
    suspend fun lock(): LockResult = LockResult.Refused(LockRefusal.NotSupported)

    /** `true` se [send] atende [ScooterCommand.SetHeadlight] mesmo sem comandos genéricos (BLE real: farol, S31). */
    val headlightAvailable: Boolean get() = genericCommandsAvailable

    /** APIs S31 estreitas para que a camada real aplique as travas de segurança. */
    val unitAvailable: Boolean get() = genericCommandsAvailable
    val driveModeAvailable: Boolean get() = genericCommandsAvailable
    val speedLimitAvailable: Boolean get() = genericCommandsAvailable
    val driveAvailable: Boolean get() = genericCommandsAvailable
    suspend fun setUnit(mph: Boolean): ScooterSettingResult = ScooterSettingResult.Refused(CommandRefusal.NotSupported)
    suspend fun setDriveMode(mode: Int): ScooterSettingResult = ScooterSettingResult.Refused(CommandRefusal.NotSupported)
    suspend fun setSpeedLimit(kmh: Int): ScooterSettingResult = ScooterSettingResult.Refused(CommandRefusal.NotSupported)
    suspend fun setDrive(twoMotors: Boolean): ScooterSettingResult = ScooterSettingResult.Refused(CommandRefusal.NotSupported)

    /** S32: cruise (CMD 0x38) e partida do zero (CMD 0x39) pelas APIs estreitas; mesmas regras de [setDrive]. */
    val cruiseAvailable: Boolean get() = genericCommandsAvailable
    val zeroStartAvailable: Boolean get() = genericCommandsAvailable
    suspend fun setCruise(on: Boolean): ScooterSettingResult = ScooterSettingResult.Refused(CommandRefusal.NotSupported)
    suspend fun setZeroStart(on: Boolean): ScooterSettingResult = ScooterSettingResult.Refused(CommandRefusal.NotSupported)

    /** `true` se este transporte oferece [setAirLock] (S34; ADR 0011). */
    val airLockAvailable: Boolean get() = false

    /**
     * Air Lock (CMD 0x41; S34, ADR 0011): `true` = o patinete bloqueia sozinho quando o app desconecta. API estreita,
     * até 2 escritas (1 envio + 1 reenvio se não houver ACK no prazo), nunca em rajada. Sem telemetria que confirme o
     * efeito: o melhor resultado é [AirLockResult.Accepted] (ACK = recebido, regra 5), nunca "concluído".
     */
    suspend fun setAirLock(on: Boolean): AirLockResult = AirLockResult.Refused(CommandRefusal.NotSupported)
}

/** Resultado do Air Lock. [Accepted] = o patinete ACEITOU o comando (ACK 0x05); o efeito real segue `[HIPÓTESE]`. */
sealed interface AirLockResult {
    /** Pré-condição falhou: NADA foi escrito. */
    data class Refused(val reason: CommandRefusal) : AirLockResult
    data object Accepted : AirLockResult
    /** Resposta de erro (tipo 0x89): não reenviado. */
    data object Rejected : AirLockResult
    /** Enviado (e reenviado uma vez) sem ACK, ou enlace caiu: o estado no patinete é DESCONHECIDO. */
    data class Unconfirmed(val cause: UnconfirmedCause) : AirLockResult
}

/** Resultado comum dos quatro comandos S31. Só [Confirmed] significa executado. */
sealed interface ScooterSettingResult {
    data class Refused(val reason: CommandRefusal) : ScooterSettingResult
    data object Confirmed : ScooterSettingResult
    data object Rejected : ScooterSettingResult
    data class Unconfirmed(val cause: UnconfirmedCause) : ScooterSettingResult
}

enum class CommandRefusal {
    NotSupported, InvalidValue, TxDisabled, Busy, LinkNotReady, TelemetryStale, SpeedUnknown, Moving, Locked,
    PendingReception, UnitMustBeKmh, RangeUnknown, OutOfRange, ModeUnavailable, StateUnknown,
}

/** Resultado do farol no BLE real (S31). Só [Confirmed] significa executado (Meter1 novo com o bit do farol; regra 5). */
sealed interface HeadlightResult {
    /** Pré-condição falhou: NADA foi escrito. */
    data class Refused(val reason: HeadlightRefusal) : HeadlightResult

    /** Meter1 NOVO (recebido após a escrita) com o bit do farol igual ao pedido, no prazo. */
    data object Confirmed : HeadlightResult

    /** O patinete respondeu com erro (tipo 0x89 do CMD 0x33 `[DESCONHECIDO]`). Não reenviar. */
    data object Rejected : HeadlightResult

    /** Enviado (ou possivelmente enviado) sem confirmação: o estado do farol é DESCONHECIDO. */
    data class Unconfirmed(val cause: UnconfirmedCause) : HeadlightResult
}

enum class HeadlightRefusal { NotSupported, TxDisabled, Busy, LinkNotReady, TelemetryStale, SpeedUnknown, Moving, Locked, PendingReception }

/**
 * Códigos de [Ack.Rejected.reason] do farol real: levam a recusa/falta de confirmação até a UI sem criar outro canal
 * (o domínio só guarda a `String`). Qualquer outro texto continua sendo "recusado pelo patinete".
 */
object HeadlightAckCodes {
    private const val REFUSED = "headlight-refused:"
    private const val UNCONFIRMED = "headlight-unconfirmed:"

    fun refused(r: HeadlightRefusal) = REFUSED + r.name
    fun unconfirmed(c: UnconfirmedCause) = UNCONFIRMED + c.name
    fun parseRefusal(code: String): HeadlightRefusal? =
        if (code.startsWith(REFUSED)) HeadlightRefusal.entries.firstOrNull { it.name == code.removePrefix(REFUSED) } else null
    fun isUnconfirmed(code: String) = code.startsWith(UNCONFIRMED)
}

/** Resultado de [ScooterTransport.lock]. Só [Locked] significa executado (o Meter1 confirmou; regra 5). */
sealed interface LockResult {
    /** Pré-condição falhou: NADA foi escrito. */
    data class Refused(val reason: LockRefusal) : LockResult

    /** Meter1 NOVO (recebido após a escrita) com o bit de travado = 1 no prazo. */
    data object Locked : LockResult

    /** O patinete respondeu com erro (tipo 0x89 do CMD 0x32 `[DESCONHECIDO]` para o modo 2). Não reenviar. */
    data object Rejected : LockResult

    /** Enviado (ou possivelmente enviado) sem confirmação: o estado do bloqueio é DESCONHECIDO. */
    data class Unconfirmed(val cause: UnconfirmedCause) : LockResult
}

enum class LockRefusal { NotSupported, TxDisabled, Busy, LinkNotReady, TelemetryStale, SpeedUnknown, Moving, AlreadyLocked, PendingReception }

/** Resultado de [ScooterTransport.unlock]. Só [Unlocked] significa executado (o Meter1 confirmou; regra 5). */
sealed interface UnlockResult {
    /** Pré-condição falhou: NADA foi escrito. */
    data class Refused(val reason: UnlockRefusal) : UnlockResult

    /** Meter1 `dado[1]` bit5 = 0 chegou no prazo após o envio. */
    data object Unlocked : UnlockResult

    /** O patinete respondeu tipo 0x89 (PIN recusado). Não reenviar. */
    data object PinRejected : UnlockResult

    /** Enviado (ou possivelmente enviado) sem confirmação: o estado do bloqueio é DESCONHECIDO. */
    data class Unconfirmed(val cause: UnconfirmedCause) : UnlockResult
}

enum class UnlockRefusal { NotSupported, TxDisabled, Busy, LinkNotReady, TelemetryStale, SpeedUnknown, Moving, NotLocked, PendingReception }

/** Timeout = nenhum Meter1 desbloqueado no prazo; WriteTimedOut/WriteFailed/LinkLost = GATT encerrado. */
enum class UnconfirmedCause { Timeout, LinkLost, WriteTimedOut, WriteFailed }

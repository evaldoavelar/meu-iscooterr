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
enum class Control { Lock, Headlight, Motors, Mode, Cruise, ZeroStart, Limit }

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
}

/** Instantâneo de telemetria; `null` = campo desconhecido. [receivedAtMs] vem do [Clock] do transporte. */
data class Telemetry(
    val receivedAtMs: Long,
    val locked: Boolean? = null,
    val speedKmh: Float? = null,
    val batteryPercent: Int? = null,
    val headlightOn: Boolean? = null,
    val dualMotor: Boolean? = null,
    val speedModeRaw: Int? = null,
    val cruiseOn: Boolean? = null,
    val zeroStartOn: Boolean? = null,
    val speedLimitKmh: Int? = null,
    val totalKm: Float? = null,
    /** Campos extras `[HIPÓTESE]`: só o Fake os fornece; o patinete real não foi confirmado (decisão 09). */
    val voltageV: Float? = null,
    val currentA: Float? = null,
    val motor1TempC: Int? = null,
    val motor2TempC: Int? = null,
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

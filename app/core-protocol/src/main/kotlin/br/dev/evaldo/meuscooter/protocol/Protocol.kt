package br.dev.evaldo.meuscooter.protocol

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

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
    val speedModes: SpeedModeCatalog

    suspend fun connect()
    suspend fun disconnect()

    /** Retorna após o ACK; a confirmação de estado chega depois em [telemetry]. */
    suspend fun send(command: ScooterCommand): Ack
}

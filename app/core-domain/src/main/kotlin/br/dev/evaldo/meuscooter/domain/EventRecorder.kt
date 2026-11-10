package br.dev.evaldo.meuscooter.domain

import br.dev.evaldo.meuscooter.protocol.Clock
import br.dev.evaldo.meuscooter.protocol.ConnectionStatus
import br.dev.evaldo.meuscooter.protocol.LockRefusal
import br.dev.evaldo.meuscooter.protocol.LockResult
import br.dev.evaldo.meuscooter.protocol.UnconfirmedCause
import br.dev.evaldo.meuscooter.protocol.UnlockRefusal
import br.dev.evaldo.meuscooter.protocol.UnlockResult

/**
 * Traduz transições reais (resultado de desbloqueio/bloqueio, mudança de enlace) em eventos do [EventLog].
 * Cada chamada com a mesma transição não repete: [unlock]/[lock] só registram `Finished` uma vez por instante.
 * Não recebe o PIN nem nada derivado dele. [clock] = relógio monotônico do controlador (para durações).
 */
class EventRecorder(private val log: EventLog, private val clock: Clock, private val deviceName: String? = null) {
    private var unlockSinceMs: Long? = null
    private var lockSinceMs: Long? = null
    private var connectingSinceMs: Long? = null
    private var lastUnlockAtMs: Long? = null
    private var lastLockAtMs: Long? = null

    /**
     * [rejects] = contagem de PINs recusados após este resultado (só importa em `PinRejected`); [cooldownStarted] = esta
     * recusa iniciou o cooldown local.
     */
    fun unlock(status: UnlockStatus?, viaSavedPin: Boolean, rejects: Int = 0, cooldownStarted: Boolean = false) {
        when (status) {
            is UnlockStatus.Waiting -> unlockSinceMs = status.sinceMs
            is UnlockStatus.Finished -> {
                if (lastUnlockAtMs == status.atMs) return
                lastUnlockAtMs = status.atMs
                val took = unlockSinceMs?.let { duration(status.atMs - it) }
                unlockSinceMs = null
                when (val r = status.result) {
                    UnlockResult.Unlocked ->
                        log.append(AppEventType.Unlock, listOfNotNull(if (viaSavedPin) "PIN salvo (biometria)" else "PIN digitado", took?.let { "confirmado em $it" }).joinToString(" · "))
                    UnlockResult.PinRejected -> {
                        val n = if (cooldownStarted) HomeRules.PIN_REJECTS_BEFORE_COOLDOWN else rejects
                        log.append(AppEventType.WrongPin, "Recusado pelo patinete ($n de ${HomeRules.PIN_REJECTS_BEFORE_COOLDOWN})")
                        if (cooldownStarted) log.append(AppEventType.Refused, "Aguarde ${HomeRules.PIN_COOLDOWN_MS / 1000} s para tentar de novo · nada foi enviado", "Desbloqueio pausado")
                    }
                    is UnlockResult.Refused -> log.append(AppEventType.Refused, "${unlockRefusalText(r.reason)} · nada foi enviado", "Desbloqueio recusado")
                    is UnlockResult.Unconfirmed -> log.append(AppEventType.Error, "Estado desconhecido · ${causeText(r.cause)}", "Desbloqueio sem confirmação")
                }
            }
            null -> Unit
        }
    }

    fun lock(status: LockStatus?) {
        when (status) {
            is LockStatus.Waiting -> lockSinceMs = status.sinceMs
            is LockStatus.Finished -> {
                if (lastLockAtMs == status.atMs) return
                lastLockAtMs = status.atMs
                val took = lockSinceMs?.let { duration(status.atMs - it) }
                lockSinceMs = null
                when (val r = status.result) {
                    LockResult.Locked -> log.append(AppEventType.Lock, listOfNotNull("Bloqueio temporário (modo 2)", took?.let { "confirmado em $it" }).joinToString(" · "))
                    is LockResult.Refused -> log.append(AppEventType.Refused, "${lockRefusalText(r.reason)} · nada foi enviado", "Bloqueio recusado")
                    LockResult.Rejected -> log.append(AppEventType.Error, "O patinete respondeu com erro", "Bloqueio rejeitado")
                    is LockResult.Unconfirmed -> log.append(AppEventType.Error, "Estado desconhecido · ${causeText(r.cause)}", "Bloqueio sem confirmação")
                }
            }
            null -> Unit
        }
    }

    /**
     * Mudança real do enlace ([previous] → [now]). [userInitiated] = pedido do usuário (desconectar/cancelar): então
     * `Conectado → Desconectado` é "Pedido do usuário" e `Conectando → Desconectado` não é erro.
     */
    fun connection(previous: ConnectionStatus, now: ConnectionStatus, userInitiated: Boolean) {
        if (previous == now) return
        when (now) {
            ConnectionStatus.Connecting -> connectingSinceMs = clock.nowMs()
            ConnectionStatus.Connected -> {
                val took = connectingSinceMs?.let { duration(clock.nowMs() - it) }
                connectingSinceMs = null
                val detail = if (took != null) listOfNotNull(deviceName, "conexão em $took").joinToString(" · ") else "Conectado"
                log.append(AppEventType.Connected, detail)
            }
            ConnectionStatus.Disconnected -> {
                connectingSinceMs = null
                when {
                    previous == ConnectionStatus.Connected && userInitiated -> log.append(AppEventType.Disconnected, "Pedido do usuário")
                    previous == ConnectionStatus.Connected -> log.append(AppEventType.LinkDropped, "Queda do enlace")
                    !userInitiated -> log.append(AppEventType.Error, "Não foi possível conectar", "Falha ao conectar")
                }
            }
        }
    }

    fun reconnectAttempt(attempt: Int, total: Int) = log.append(AppEventType.Reconnecting, "Tentativa $attempt de $total")

    private fun duration(ms: Long): String {
        val tenths = (ms.coerceAtLeast(0) + 50) / 100
        return "${tenths / 10},${tenths % 10} s"
    }

    private fun causeText(c: UnconfirmedCause) = when (c) {
        UnconfirmedCause.Timeout -> "sem resposta no prazo"
        UnconfirmedCause.LinkLost -> "enlace perdido"
        UnconfirmedCause.WriteTimedOut -> "envio expirou"
        UnconfirmedCause.WriteFailed -> "falha no envio"
    }

    private fun unlockRefusalText(r: UnlockRefusal) = when (r) {
        UnlockRefusal.LinkNotReady -> "Conexão ainda não está pronta"
        UnlockRefusal.TelemetryStale -> "Aguarde a telemetria"
        UnlockRefusal.SpeedUnknown -> "Velocidade desconhecida"
        UnlockRefusal.Moving -> "Patinete em movimento"
        UnlockRefusal.PendingReception -> "O patinete ainda está enviando dados. Toque de novo."
        UnlockRefusal.Busy -> "Outro comando em andamento"
        UnlockRefusal.NotLocked -> "O patinete já está desbloqueado"
        UnlockRefusal.TxDisabled, UnlockRefusal.NotSupported -> "Desbloqueio indisponível neste modo"
    }

    private fun lockRefusalText(r: LockRefusal) = when (r) {
        LockRefusal.LinkNotReady -> "Conexão ainda não está pronta"
        LockRefusal.TelemetryStale -> "Aguarde a telemetria"
        LockRefusal.SpeedUnknown -> "Velocidade desconhecida"
        LockRefusal.Moving -> "Patinete em movimento"
        LockRefusal.PendingReception -> "O patinete ainda está enviando dados. Toque de novo."
        LockRefusal.Busy -> "Outro comando em andamento"
        LockRefusal.AlreadyLocked -> "O patinete já está bloqueado"
        LockRefusal.TxDisabled, LockRefusal.NotSupported -> "Bloqueio indisponível neste modo"
    }
}

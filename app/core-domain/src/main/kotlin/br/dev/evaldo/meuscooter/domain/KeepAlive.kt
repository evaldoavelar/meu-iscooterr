package br.dev.evaldo.meuscooter.domain

import br.dev.evaldo.meuscooter.protocol.ConnectionStatus

/**
 * Mantém o processo vivo (serviço em primeiro plano no Android) enquanto a sessão BLE real está ativa (ADR 0007).
 * Não possui o transporte e não envia nada: só evita que o sistema congele o app com o heartbeat em curso.
 */
/** Botões da viagem pausada na notificação (S53); equivalem a Play, Parar e Reset da barra da Telemetria. */
enum class TripNotificationAction { Resume, Stop, Reset }

interface SessionKeepAlive {
    /** [onUserDisconnect]: pedido de desconexão vindo de fora da tela (ação "Desconectar" da notificação). */
    fun start(onUserDisconnect: () -> Unit)
    fun stop()

    /**
     * Viagem gravando (Running) ou não: no Android muda o tipo do serviço (`location`, ADR 0015). Não liga GPS; padrão nada.
     * Chamado ao mudar o estado e ao (re)iniciar o serviço.
     */
    fun setTripRecording(recording: Boolean) = Unit

    /**
     * Viagem PAUSADA (S53): a notificação troca "Desconectar" por Continuar | Parar | Reiniciar (limite de 3 ações do Android).
     * Mudar o valor com o serviço ativo atualiza a notificação. Local: nada é enviado ao patinete. Padrão nada.
     */
    fun setTripPaused(paused: Boolean) = Unit

    /** Quem recebe os toques nos botões da viagem na notificação (thread principal). `null` limpa. Padrão nada. */
    fun setTripActionHandler(handler: ((TripNotificationAction) -> Unit)?) = Unit

    /** Fake, primeiro uso e modo só escuta: nenhum serviço. */
    object None : SessionKeepAlive {
        override fun start(onUserDisconnect: () -> Unit) = Unit
        override fun stop() = Unit
    }
}

/** ADR 0007 §2: começa só com `Connected`; `Connecting`/reconexão automática pendente mantêm o estado anterior; senão para. */
object KeepAlivePolicy {
    fun next(active: Boolean, connection: ConnectionStatus, reconnectPending: Boolean): Boolean = when {
        connection == ConnectionStatus.Connected -> true
        connection == ConnectionStatus.Connecting || reconnectPending -> active
        else -> false
    }
}

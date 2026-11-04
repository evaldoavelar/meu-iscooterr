package br.dev.evaldo.meuscooter.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Passo MTU do GATT real, separado das demais operações pendentes. Um `onMtuChanged` espontâneo (o Android pode
 * negociar o MTU sozinho) só é anotado em [observed]; nunca completa connect/discover/CCCD.
 * Um sucesso < [BleScooterTransport.MIN_USABLE_MTU] durante a espera não encerra o pedido na hora (pode ser espontâneo);
 * se nada melhor vier na janela de graça, ele vale como resposta. Se o stack não gerar callback porque o MTU já estava negociado, vale o MTU observado quando ele é utilizável.
 */
class MtuNegotiator(private val graceMs: Long = DEFAULT_GRACE_MS) {
    @Volatile private var observed = 0
    @Volatile private var waiting: CompletableDeferred<Int>? = null

    /** Último sucesso PEQUENO (< mínimo) visto durante a espera atual; 0 = nenhum. */
    @Volatile private var smallDuringRequest = 0

    /** A janela de graça do pedido atual acabou: um sucesso pequeno passa a ser a resposta. */
    @Volatile private var graceOver = false

    /** Chamado pelo callback do framework (qualquer thread). */
    fun onChanged(mtu: Int, success: Boolean) {
        if (success) observed = mtu
        val w = waiting ?: return
        when {
            // Sucesso pequeno pode ser espontâneo e o callback real vir depois: só decide se a janela de graça acabar.
            success && mtu < BleScooterTransport.MIN_USABLE_MTU ->
                if (graceOver) w.complete(mtu) else smallDuringRequest = mtu
            success -> w.complete(mtu)
            else -> w.completeExceptionally(GattException(GattError.MtuFailed, "onMtuChanged recusado"))
        }
    }

    /** Nova sessão GATT: esquece o MTU da anterior. */
    fun reset() {
        observed = 0
        smallDuringRequest = 0
        graceOver = false
        waiting = null
    }

    /** O GATT foi fechado/perdido. */
    fun onClosed() {
        waiting?.completeExceptionally(GattException(GattError.Disconnected))
    }

    suspend fun request(start: () -> Boolean): Int {
        val w = CompletableDeferred<Int>()
        waiting = w
        smallDuringRequest = 0
        graceOver = false
        try {
            if (!start()) throw GattException(GattError.MtuFailed, "operação recusada pelo stack")
            withTimeoutOrNull(graceMs) { w.await() }?.let { return it }
            observed.takeIf { it >= BleScooterTransport.MIN_USABLE_MTU }?.let { return it }
            graceOver = true
            // Só chegou um MTU pequeno depois do pedido: é a resposta (o transporte falha com MtuTooSmall).
            smallDuringRequest.takeIf { it > 0 }?.let { return it }
            return w.await()
        } finally {
            waiting = null
        }
    }

    companion object {
        /** Espera pelo callback antes de aceitar o MTU já observado. */
        const val DEFAULT_GRACE_MS = 1_500L
    }
}

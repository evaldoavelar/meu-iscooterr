package br.dev.evaldo.meuscooter.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Passo MTU do GATT real, separado das demais operações pendentes. Um `onMtuChanged` espontâneo (o Android pode
 * negociar o MTU sozinho) só é anotado em [observed]; nunca completa connect/discover/CCCD.
 * Se o stack não gerar callback porque o MTU já estava negociado, vale o MTU observado quando ele é utilizável.
 */
class MtuNegotiator(private val graceMs: Long = DEFAULT_GRACE_MS) {
    @Volatile private var observed = 0
    @Volatile private var waiting: CompletableDeferred<Int>? = null

    /** Chamado pelo callback do framework (qualquer thread). */
    fun onChanged(mtu: Int, success: Boolean) {
        if (success) observed = mtu
        val w = waiting ?: return
        if (success) w.complete(mtu) else w.completeExceptionally(GattException(GattError.MtuFailed, "onMtuChanged recusado"))
    }

    /** Nova sessão GATT: esquece o MTU da anterior. */
    fun reset() {
        observed = 0
        waiting = null
    }

    /** O GATT foi fechado/perdido. */
    fun onClosed() {
        waiting?.completeExceptionally(GattException(GattError.Disconnected))
    }

    suspend fun request(start: () -> Boolean): Int {
        val w = CompletableDeferred<Int>()
        waiting = w
        try {
            if (!start()) throw GattException(GattError.MtuFailed, "operação recusada pelo stack")
            withTimeoutOrNull(graceMs) { w.await() }?.let { return it }
            observed.takeIf { it >= BleScooterTransport.MIN_USABLE_MTU }?.let { return it }
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

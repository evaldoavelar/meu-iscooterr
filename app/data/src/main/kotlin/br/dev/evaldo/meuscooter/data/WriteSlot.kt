package br.dev.evaldo.meuscooter.data

import kotlinx.coroutines.CompletableDeferred

/**
 * Regra pura das escritas de característica do `AndroidGattClient` (o callback `onCharacteristicWrite` não traz
 * identificador). Cada escrita recebe um [Op.id] próprio e um resultado SEPARADO do `pending` das outras operações.
 * Se a espera de uma escrita iniciada é abandonada (timeout/cancelamento) sem o callback, o stack ainda o deve:
 * o próximo `onCharacteristicWrite` é dessa escrita e é consumido por ela, e [begin] recusa outra escrita até lá
 * (o stack também recusaria — uma operação GATT por vez). Assim um callback tardio nunca completa outra escrita.
 *
 * Um slot por SESSÃO GATT (um por `connectGatt`), dono: [GattSession]. [close] é terminal: falha a escrita em voo e,
 * depois dele, [begin] recusa ([GattError.Disconnected]) e [onCallback] não completa nada. Um callback tardio de uma
 * sessão fechada só alcança o slot fechado dela, nunca a escrita da sessão nova. Thread-safe: os callbacks chegam numa thread do binder.
 */
class WriteSlot {
    class Op internal constructor(val id: Long) {
        val result = CompletableDeferred<Unit>()
        internal var started = false
        internal var callbackReceived = false
    }

    private var lastId = 0L
    private var current: Op? = null

    /** Id da escrita abandonada cujo callback o stack ainda deve (`null` = nenhuma). */
    private var owed: Long? = null

    private var closed = false

    /**
     * Reserva a vaga. Falha com [GattError.Disconnected] se o slot (a sessão) foi fechado, ou com [GattError.WriteFailed]
     * se outra escrita estiver em voo ou ainda devendo o callback.
     */
    @Synchronized
    fun begin(): Op {
        if (closed) throw GattException(GattError.Disconnected)
        if (current != null || owed != null) throw GattException(GattError.WriteFailed, "escrita anterior sem confirmação do stack")
        return Op(++lastId).also { current = it }
    }

    /** O stack aceitou a escrita de [op]: a partir daqui ela deve um callback. */
    @Synchronized
    fun started(op: Op) {
        if (current === op) op.started = true
    }

    /** `onCharacteristicWrite` ([error] `null` = sucesso). `true` se completou a escrita em voo. */
    fun onCallback(error: GattException?): Boolean {
        val completion = takeCallback(error) ?: return false
        completion()
        return true
    }

    /**
     * Transição do callback sob o monitor; devolve a conclusão da escrita em voo (`null` = nenhuma) para o chamador
     * executar FORA de qualquer lock (Codex r4: uma continuação retomada na hora não pode reentrar no meio da transição).
     */
    @Synchronized
    internal fun takeCallback(error: GattException?): (() -> Unit)? {
        val op = current
        if (op == null || op.callbackReceived) {
            if (op == null) owed = null // callback devido da escrita abandonada (ou espúrio): consumido, não completa ninguém
            return null
        }
        op.callbackReceived = true // já não deve callback, mesmo que `end` venha antes da conclusão
        return { if (error == null) op.result.complete(Unit) else op.result.completeExceptionally(error) }
    }

    /** Fim da espera de [op] (sucesso, falha, recusa ou abandono). Iniciada e sem callback → o callback fica devido. */
    @Synchronized
    fun end(op: Op) {
        if (current !== op) return
        current = null
        if (op.started && !op.callbackReceived && !op.result.isCompleted) owed = op.id
    }

    /** Sessão GATT encerrada (terminal): falha a escrita em voo com [GattError.Disconnected]; o slot não serve mais. */
    fun close() {
        closeTransition()?.invoke()
    }

    /** Transição de [close] sob o monitor; devolve a falha da escrita em voo para executar fora de qualquer lock. */
    @Synchronized
    internal fun closeTransition(): (() -> Unit)? {
        closed = true
        val op = current
        current = null
        owed = null
        return op?.let { { it.result.completeExceptionally(GattException(GattError.Disconnected)) } }
    }
}

package br.dev.evaldo.meuscooter.data

import kotlinx.coroutines.CompletableDeferred

/**
 * Regra pura das escritas de característica do `AndroidGattClient` (o callback `onCharacteristicWrite` não traz
 * identificador). Cada escrita recebe um [Op.id] próprio e um resultado SEPARADO do `pending` das outras operações.
 * Se a espera de uma escrita iniciada é abandonada (timeout/cancelamento) sem o callback, o stack ainda o deve:
 * o próximo `onCharacteristicWrite` é dessa escrita e é consumido por ela, e [begin] recusa outra escrita até lá
 * (o stack também recusaria — uma operação GATT por vez). Assim um callback tardio nunca completa outra escrita.
 * [close] (GATT encerrado) esquece tudo. Thread-safe: os callbacks chegam numa thread do binder.
 */
class WriteSlot {
    class Op internal constructor(val id: Long) {
        val result = CompletableDeferred<Unit>()
        internal var started = false
    }

    private var lastId = 0L
    private var current: Op? = null

    /** Id da escrita abandonada cujo callback o stack ainda deve (`null` = nenhuma). */
    private var owed: Long? = null

    /** Reserva a vaga. Falha com [GattError.WriteFailed] se outra escrita estiver em voo ou ainda devendo o callback. */
    @Synchronized
    fun begin(): Op {
        if (current != null || owed != null) throw GattException(GattError.WriteFailed, "escrita anterior sem confirmação do stack")
        return Op(++lastId).also { current = it }
    }

    /** O stack aceitou a escrita de [op]: a partir daqui ela deve um callback. */
    @Synchronized
    fun started(op: Op) {
        if (current === op) op.started = true
    }

    /** `onCharacteristicWrite` ([error] `null` = sucesso). `true` se completou a escrita em voo. */
    @Synchronized
    fun onCallback(error: GattException?): Boolean {
        val op = current
        if (op == null) {
            owed = null // callback devido da escrita abandonada (ou espúrio): consumido, não completa ninguém
            return false
        }
        if (error == null) op.result.complete(Unit) else op.result.completeExceptionally(error)
        return true
    }

    /** Fim da espera de [op] (sucesso, falha, recusa ou abandono). Iniciada e sem callback → o callback fica devido. */
    @Synchronized
    fun end(op: Op) {
        if (current !== op) return
        current = null
        if (op.started && !op.result.isCompleted) owed = op.id
    }

    /** GATT encerrado: falha a escrita em voo com [GattError.Disconnected] e esquece o callback devido. */
    @Synchronized
    fun close() {
        current?.result?.completeExceptionally(GattException(GattError.Disconnected))
        current = null
        owed = null
    }
}

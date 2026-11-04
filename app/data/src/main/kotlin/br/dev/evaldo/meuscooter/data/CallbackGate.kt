package br.dev.evaldo.meuscooter.data

import java.util.Collections
import java.util.WeakHashMap

/**
 * Decide se um callback do framework (ex.: `BluetoothGattCallback`) pertence à sessão GATT atual, por identidade.
 * Um callback tardio de um GATT já fechado nunca pode completar a operação da sessão nova.
 *
 * `connectGatt` só devolve o objeto DEPOIS de o stack já poder ter chamado o callback; por isso [expectConnect] abre
 * uma janela em que o 1º objeto nunca visto é adotado. Objetos já fechados ([close]) ficam em [retired] e não são
 * adotados nem dentro da janela. Thread-safe (callbacks chegam em thread de binder).
 */
class CallbackGate<T : Any> {
    private var current: T? = null
    private var expecting = false
    private val retired: MutableSet<T> = Collections.newSetFromMap(WeakHashMap())

    @Synchronized
    fun accepts(incoming: T): Boolean {
        if (current == null && expecting && incoming !in retired) {
            current = incoming
            expecting = false
        }
        return current === incoming
    }

    /** O retorno de `connectGatt`. Não troca uma sessão já adotada pelo 1º callback. */
    @Synchronized
    fun adopt(session: T) {
        if (current == null && session !in retired) current = session
        expecting = false
    }

    /** Antes de `connectGatt`: o 1º callback de um objeto novo é a sessão. */
    @Synchronized
    fun expectConnect() {
        current?.let { retired += it }
        current = null
        expecting = true
    }

    @Synchronized
    fun close() {
        current?.let { retired += it }
        current = null
        expecting = false
    }
}

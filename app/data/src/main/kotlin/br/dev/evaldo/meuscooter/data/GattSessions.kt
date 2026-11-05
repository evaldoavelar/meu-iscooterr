package br.dev.evaldo.meuscooter.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Posse das sessões GATT do `AndroidGattClient` (Codex r3 P2; projeto pedido no ADR 0003: posse por tentativa e um
 * único ponto de transição sob lock). Cada `connectGatt` usa uma [GattSession] NOVA, criada por [open] ANTES da chamada
 * (o stack pode chamar o callback antes de `connectGatt` retornar) e capturada pelo callback daquela conexão. Todo o
 * estado mutável por conexão — operação pendente (connect/discover/CCCD), pedido de MTU, [WriteSlot] e handle — é da
 * sessão, então um callback só alcança a SUA sessão. Cada entrega valida "esta sessão ainda é a corrente?" e faz a
 * transição no MESMO bloco `synchronized(lock)`; [state] só muda nesse bloco. Fechar é final: a sessão fechada não
 * começa nem conclui nada e suas esperas falham com [GattError.Disconnected]. Thread-safe (callbacks na thread do binder).
 *
 * Codex r4: (1) [open]/[close] retiram a sessão corrente atomicamente e devolvem EXATAMENTE a sessão retirada — cada
 * sessão aposentada é devolvida uma única vez; o GATT dela é liberado uma única vez por [GattSession.detachHandle] ou,
 * se o handle chegar depois da aposentadoria, por quem o recebe de [GattSession.attach] (`false`). (2) Concluir/falhar
 * uma espera retoma a continuação na hora em alguns despachantes (ex.: `Dispatchers.Unconfined`), que pode reentrar
 * aqui; por isso toda conclusão é coletada durante a transição e executada só DEPOIS dela, FORA do lock ([transition]).
 */
class GattSessions<T : Any> {
    internal val lock = Any()
    private var current: GattSession<T>? = null

    private val _state = MutableStateFlow(GattState.Disconnected)
    val state: StateFlow<GattState> = _state.asStateFlow()

    /** Resultado de [open]: a sessão nova (já corrente) e a que ela aposentou (`null` = nenhuma). */
    data class Opened<T : Any>(val session: GattSession<T>, val retired: GattSession<T>?)

    /** Nova sessão corrente. A anterior (se houver) é fechada e devolvida em [Opened.retired]; o chamador libera ESSA. */
    fun open(): Opened<T> = transition { after ->
        val retired = current
        retired?.retireLocked(after)
        Opened(GattSession(this).also { current = it }, retired)
    }

    fun current(): GattSession<T>? = synchronized(lock) { current }

    /** Fecha a sessão corrente (final) e publica [GattState.Disconnected]. Devolve-a para o chamador liberar o handle. */
    fun close(): GattSession<T>? = transition { after ->
        val retired = current
        current = null
        retired?.retireLocked(after)
        _state.value = GattState.Disconnected
        retired
    }

    /**
     * Único ponto de transição: [block] muda o estado sob o lock e só ANOTA em `after` as conclusões (e demais efeitos
     * externos); elas rodam depois que o lock é liberado, com a transição já completa.
     */
    internal inline fun <R> transition(block: (MutableList<() -> Unit>) -> R): R {
        val after = ArrayList<() -> Unit>(2)
        val result = synchronized(lock) { block(after) }
        after.forEach { it() }
        return result
    }

    internal fun isCurrentLocked(s: GattSession<T>) = current === s

    internal fun publishLocked(state: GattState) {
        _state.value = state
    }
}

/** Estado de UMA conexão GATT. Os métodos `on*` são os callbacks do framework, já traduzidos; só valem se corrente. */
class GattSession<T : Any> internal constructor(private val owner: GattSessions<T>) {
    /** O objeto do framework (`BluetoothGatt`), conhecido só depois de `connectGatt` retornar ([attach]). */
    @Volatile var handle: T? = null
        private set

    /** Escritas de característica desta sessão; [close][GattSessions.close] fecha o slot (final). */
    val writes = WriteSlot()

    private val mtu = MtuNegotiator()
    private var pending: CompletableDeferred<Unit>? = null

    /** Já deixou de ser a corrente (sob o lock); final. */
    private var retired = false

    /** O handle já foi entregue para liberação (por [detachHandle] ou pela recusa de [attach]). */
    private var handleHandedOff = false

    /**
     * Anexa o handle devolvido por `connectGatt`. `false` = a sessão já foi aposentada: o handle NÃO fica com ela e o
     * chamador deve liberá-lo (quem aposentou não o viu). Assim o GATT é liberado exatamente uma vez.
     */
    fun attach(h: T): Boolean = synchronized(owner.lock) {
        if (retired) {
            handleHandedOff = true
            false
        } else {
            handle = h
            true
        }
    }

    /**
     * Para a sessão aposentada (devolvida por [open][GattSessions.open]/[close][GattSessions.close]): entrega o handle
     * para liberar, uma única vez. `null` = ainda sem handle (quem o [attach] depois libera) ou já entregue.
     */
    fun detachHandle(): T? = synchronized(owner.lock) {
        check(retired) { "só a sessão aposentada entrega o handle" }
        if (handleHandedOff) return@synchronized null
        val h = handle ?: return@synchronized null
        handleHandedOff = true
        h
    }

    /** Executa [block] só se esta sessão é a corrente, sob o lock (ex.: emitir uma notificação). `null` = descartado. */
    fun <R> ifCurrent(block: () -> R): R? = synchronized(owner.lock) { if (owner.isCurrentLocked(this)) block() else null }

    /** Uma operação por vez (connect/discover/CCCD): [start] dispara no stack; conclui pelo callback desta sessão. */
    suspend fun await(failure: GattError, start: () -> Boolean) {
        val p = CompletableDeferred<Unit>()
        synchronized(owner.lock) {
            if (!owner.isCurrentLocked(this)) throw GattException(GattError.Disconnected)
            pending = p
        }
        try {
            if (!start()) throw GattException(failure, "operação recusada pelo stack")
            p.await()
        } catch (e: SecurityException) {
            throw GattException(GattError.PermissionDenied, e.message)
        } finally {
            synchronized(owner.lock) { if (pending === p) pending = null }
        }
    }

    /**
     * Pedido de MTU desta sessão. A posse é revalidada dentro de [start], depois de o negociador registrar a espera:
     * um `close()` concorrente ou vê a espera (e a falha) ou faz este pedido falhar aqui — nunca fica esperando.
     */
    suspend fun requestMtu(start: () -> Boolean): Int = try {
        mtu.request(start = {
            synchronized(owner.lock) { if (!owner.isCurrentLocked(this)) throw GattException(GattError.Disconnected) }
            start()
        })
    } catch (e: SecurityException) {
        throw GattException(GattError.PermissionDenied, e.message)
    }

    fun onConnected() = owner.transition { after ->
        if (owner.isCurrentLocked(this)) {
            owner.publishLocked(GattState.Connected)
            takePendingLocked()?.let { p -> after += { p.complete(Unit) } }
        }
    }

    /** Enlace caiu ou a conexão falhou ([error]: `Disconnected` limpo ou `ConnectFailed`). */
    fun onDisconnected(error: GattError, detail: String) = owner.transition { after ->
        if (owner.isCurrentLocked(this)) {
            owner.publishLocked(GattState.Disconnected)
            mtu.closed()?.let(after::add) // pedido de MTU pendente falha em vez de esperar o timeout
            takePendingLocked()?.let { p -> after += { p.completeExceptionally(GattException(error, detail)) } }
        }
    }

    /** Fim de discover/CCCD ([error] `null` = sucesso). */
    fun onOperationDone(error: GattException?) = owner.transition { after ->
        if (owner.isCurrentLocked(this)) {
            takePendingLocked()?.let { p ->
                after += { if (error == null) p.complete(Unit) else p.completeExceptionally(error) }
            }
        }
    }

    fun onMtuChanged(mtu: Int, success: Boolean) = owner.transition { after ->
        if (owner.isCurrentLocked(this)) this.mtu.changed(mtu, success)?.let(after::add)
    }

    /** `onCharacteristicWrite` ([error] `null` = sucesso): só a escrita identificada desta sessão. */
    fun onWriteDone(error: GattException?) = owner.transition { after ->
        if (owner.isCurrentLocked(this)) writes.takeCallback(error)?.let(after::add)
    }

    /** A espera em curso sai do estado já na transição: um `close()` logo depois não a falha por cima da conclusão. */
    private fun takePendingLocked(): CompletableDeferred<Unit>? = pending.also { pending = null }

    /** Chamado uma única vez (sob o lock), quando deixa de ser a corrente (nunca volta a ser): final. */
    internal fun retireLocked(after: MutableList<() -> Unit>) {
        retired = true
        takePendingLocked()?.let { p -> after += { p.completeExceptionally(GattException(GattError.Disconnected)) } }
        mtu.closed()?.let(after::add)
        writes.closeTransition()?.let(after::add)
    }
}

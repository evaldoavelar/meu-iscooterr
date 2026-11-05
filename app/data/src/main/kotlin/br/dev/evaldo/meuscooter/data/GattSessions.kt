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
 * sessão, então um callback só alcança a SUA sessão. Cada entrega valida "esta sessão ainda é a corrente?" e
 * muta/conclui no MESMO bloco `synchronized(lock)`; [state] só muda nesse bloco. Fechar é final: a sessão fechada não
 * começa nem conclui nada e suas esperas falham com [GattError.Disconnected]. Thread-safe (callbacks na thread do binder).
 */
class GattSessions<T : Any> {
    internal val lock = Any()
    private var current: GattSession<T>? = null

    private val _state = MutableStateFlow(GattState.Disconnected)
    val state: StateFlow<GattState> = _state.asStateFlow()

    /** Nova sessão corrente. A anterior (se houver) é fechada; o chamador libera o handle dela. */
    fun open(): GattSession<T> = synchronized(lock) {
        current?.closeLocked()
        GattSession(this).also { current = it }
    }

    fun current(): GattSession<T>? = synchronized(lock) { current }

    /** Fecha a sessão corrente (final) e publica [GattState.Disconnected]. Devolve-a para o chamador liberar o handle. */
    fun close(): GattSession<T>? = synchronized(lock) {
        val s = current
        current = null
        s?.closeLocked()
        _state.value = GattState.Disconnected
        s
    }

    internal fun isCurrentLocked(s: GattSession<T>) = current === s

    internal fun publishLocked(state: GattState) {
        _state.value = state
    }
}

/** Estado de UMA conexão GATT. Os métodos `on*` são os callbacks do framework, já traduzidos; só valem se corrente. */
class GattSession<T : Any> internal constructor(private val owner: GattSessions<T>) {
    /** O objeto do framework (`BluetoothGatt`), conhecido só depois de `connectGatt` retornar. */
    @Volatile var handle: T? = null

    /** Escritas de característica desta sessão; [close][GattSessions.close] fecha o slot (final). */
    val writes = WriteSlot()

    private val mtu = MtuNegotiator()
    private var pending: CompletableDeferred<Unit>? = null

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

    fun onConnected() = synchronized(owner.lock) {
        if (owner.isCurrentLocked(this)) {
            owner.publishLocked(GattState.Connected)
            pending?.complete(Unit)
        }
    }

    /** Enlace caiu ou a conexão falhou ([error]: `Disconnected` limpo ou `ConnectFailed`). */
    fun onDisconnected(error: GattError, detail: String) = synchronized(owner.lock) {
        if (owner.isCurrentLocked(this)) {
            owner.publishLocked(GattState.Disconnected)
            mtu.onClosed() // pedido de MTU pendente falha em vez de esperar o timeout
            pending?.completeExceptionally(GattException(error, detail))
        }
    }

    /** Fim de discover/CCCD ([error] `null` = sucesso). */
    fun onOperationDone(error: GattException?) = synchronized(owner.lock) {
        val p = pending
        if (owner.isCurrentLocked(this) && p != null) {
            if (error == null) p.complete(Unit) else p.completeExceptionally(error)
        }
    }

    fun onMtuChanged(mtu: Int, success: Boolean) = synchronized(owner.lock) {
        if (owner.isCurrentLocked(this)) this.mtu.onChanged(mtu, success)
    }

    /** `onCharacteristicWrite` ([error] `null` = sucesso): só a escrita identificada desta sessão. */
    fun onWriteDone(error: GattException?) = synchronized(owner.lock) {
        if (owner.isCurrentLocked(this)) writes.onCallback(error)
    }

    /** Chamado uma única vez, quando deixa de ser a corrente (nunca volta a ser): final. */
    internal fun closeLocked() {
        pending?.completeExceptionally(GattException(GattError.Disconnected))
        mtu.onClosed()
        writes.close()
    }
}

package br.dev.evaldo.meuscooter.data

import br.dev.evaldo.meuscooter.protocol.Clock
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * GATT simulado para testes (NENHUM BLE real). Latências em tempo virtual, falhas injetáveis, [dropLink] no meio de
 * uma operação, e [emit] a qualquer momento (inclusive notificações fora de ordem, duplicadas, lixo, ou TARDIAS
 * depois de `close()` — um enlace real não as entregaria, o fake sim, para provar que o transporte as ignora).
 */
class FakeGattClient(clock: Clock) : GattClient {
    var connectDelayMs = 0L
    var discoverDelayMs = 0L
    var subscribeDelayMs = 0L
    var mtuDelayMs = 0L
    var connectError: Throwable? = null
    var discoverError: Throwable? = null
    var subscribeError: Throwable? = null
    var mtuError: Throwable? = null

    /** MTU atual do enlace simulado (23 = padrão do ATT antes de negociar). */
    var mtu = 23
        private set
    var simulateTruncation = false

    /** Maior MTU que o patinete simulado aceita; o negociado é `min(pedido, peerMaxMtu)`. */
    var peerMaxMtu = 247
    var writeError: Throwable? = null

    /** Latência de cada `write` (tempo virtual) — a escrita só "conclui" depois dela, como `onCharacteristicWrite`. */
    var writeDelayMs = 0L

    /** Resposta simulada do patinete a uma escrita bem-sucedida (ex.: o ACK literal); `null` = ausência de ACK. */
    var reply: (ByteArray) -> ByteArray? = { null }

    /** Maior número de escritas simultâneas observado (o transporte deve manter 1). */
    var maxWritesInFlight = 0
        private set

    /**
     * Escritas ainda no "stack" simulado. Cancelar a coroutine de `write` (ex.: timeout do chamador) NÃO tira a escrita
     * de voo — no Android o stack segue com ela e o callback vem depois (ou nunca); só `close()`/[dropLink] a descartam.
     */
    var writesInFlight = 0
        private set
    private var abandonedWrites = 0
    var services: List<GattServiceInfo> = listOf(GattServiceInfo(ScooterGatt.SERVICE, setOf(ScooterGatt.RX, ScooterGatt.WR)))

    /** Registro de chamadas na ordem em que chegaram. */
    val calls = mutableListOf<String>()
    val writes = mutableListOf<Triple<UUID, UUID, List<Byte>>>()
    var closeCalls = 0
        private set

    private val _state = MutableStateFlow(GattState.Disconnected)
    override val state: StateFlow<GattState> = _state.asStateFlow()

    private val reception = GattReception(clock)
    override fun markBoundary(): GattBoundary = reception.markBoundary()

    private val _notifications = MutableSharedFlow<GattNotification>(extraBufferCapacity = 256)
    override val notifications: Flow<GattNotification> = _notifications.asSharedFlow()

    private val linkEpoch = MutableStateFlow(0)

    fun emit(bytes: ByteArray) {
        val value = if (simulateTruncation && bytes.size > mtu - ATT_HEADER) bytes.copyOf(mtu - ATT_HEADER) else bytes
        _notifications.tryEmit(reception.notification(value))
    }

    /** Perde o enlace (como o patinete desligando): falha a operação pendente e passa a [GattState.Disconnected]. */
    fun dropLink() = endLink()

    override suspend fun connect(address: String) {
        calls += "connect:$address"
        op(connectDelayMs, connectError) { _state.value = GattState.Connected }
    }

    override suspend fun discoverServices(): List<GattServiceInfo> {
        calls += "discover"
        return op(discoverDelayMs, discoverError) { services }
    }

    override suspend fun requestMtu(mtu: Int): Int {
        calls += "mtu:$mtu"
        return op(mtuDelayMs, mtuError) { minOf(mtu, peerMaxMtu).also { this.mtu = it } }
    }

    override suspend fun enableNotifications(service: UUID, characteristic: UUID) {
        calls += "subscribe:$service/$characteristic"
        op(subscribeDelayMs, subscribeError) { }
    }

    override suspend fun write(service: UUID, characteristic: UUID, value: ByteArray) {
        calls += "write:$service/$characteristic"
        writes += Triple(service, characteristic, value.toList())
        writesInFlight++
        maxWritesInFlight = maxOf(maxWritesInFlight, writesInFlight)
        try {
            op(writeDelayMs, writeError) { }
        } catch (e: CancellationException) {
            abandonedWrites++ // o chamador desistiu; a operação continua pendente no stack
            throw e
        } catch (e: Throwable) {
            writesInFlight--
            throw e
        }
        writesInFlight--
        reply(value)?.let { emit(it) }
    }

    override fun close() {
        calls += "close"
        closeCalls++
        endLink()
    }

    private fun endLink() {
        _state.value = GattState.Disconnected
        linkEpoch.value++
        writesInFlight -= abandonedWrites
        abandonedWrites = 0
    }

    private suspend fun <T> op(delayMs: Long, error: Throwable?, result: () -> T): T = coroutineScope {
        val epoch = linkEpoch.value
        val watcher = launch {
            linkEpoch.first { it != epoch }
            throw GattException(GattError.Disconnected)
        }
        try {
            delay(delayMs)
            error?.let { throw it }
            result()
        } finally {
            watcher.cancel()
        }
    }

    private companion object {
        /** Opcode(1) + handle(2) de uma notificação ATT. */
        const val ATT_HEADER = 3
    }
}

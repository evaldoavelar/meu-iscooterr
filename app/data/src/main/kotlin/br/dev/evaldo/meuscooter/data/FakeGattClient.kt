package br.dev.evaldo.meuscooter.data

import java.util.UUID
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
class FakeGattClient : GattClient {
    var connectDelayMs = 0L
    var discoverDelayMs = 0L
    var subscribeDelayMs = 0L
    var connectError: Throwable? = null
    var discoverError: Throwable? = null
    var subscribeError: Throwable? = null
    var writeError: Throwable? = null
    var services: List<GattServiceInfo> = listOf(GattServiceInfo(ScooterGatt.SERVICE, setOf(ScooterGatt.RX, ScooterGatt.WR)))

    /** Registro de chamadas na ordem em que chegaram. */
    val calls = mutableListOf<String>()
    val writes = mutableListOf<Triple<UUID, UUID, List<Byte>>>()
    var closeCalls = 0
        private set

    private val _state = MutableStateFlow(GattState.Disconnected)
    override val state: StateFlow<GattState> = _state.asStateFlow()

    private val _notifications = MutableSharedFlow<ByteArray>(extraBufferCapacity = 256)
    override val notifications: Flow<ByteArray> = _notifications.asSharedFlow()

    private val linkEpoch = MutableStateFlow(0)

    fun emit(bytes: ByteArray) {
        _notifications.tryEmit(bytes)
    }

    /** Perde o enlace (como o patinete desligando): falha a operação pendente e passa a [GattState.Disconnected]. */
    fun dropLink() {
        _state.value = GattState.Disconnected
        linkEpoch.value++
    }

    override suspend fun connect(address: String) {
        calls += "connect:$address"
        op(connectDelayMs, connectError) { _state.value = GattState.Connected }
    }

    override suspend fun discoverServices(): List<GattServiceInfo> {
        calls += "discover"
        return op(discoverDelayMs, discoverError) { services }
    }

    override suspend fun enableNotifications(service: UUID, characteristic: UUID) {
        calls += "subscribe:$service/$characteristic"
        op(subscribeDelayMs, subscribeError) { }
    }

    override suspend fun write(service: UUID, characteristic: UUID, value: ByteArray) {
        calls += "write:$service/$characteristic"
        writes += Triple(service, characteristic, value.toList())
        writeError?.let { throw it }
    }

    override fun close() {
        calls += "close"
        closeCalls++
        _state.value = GattState.Disconnected
        linkEpoch.value++
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
}

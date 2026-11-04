package br.dev.evaldo.meuscooter.data

import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** UUIDs do patinete. `FF61` (RX) e `FF62` (WR) `[CONFIRMADO]` no log S2; serviço `FF60` `[ESTÁTICO]` (T3-T7), a confirmar na etapa 4.2. */
object ScooterGatt {
    fun uuid16(short: Int): UUID = UUID.fromString(String.format("0000%04x-0000-1000-8000-00805f9b34fb", short))

    val SERVICE: UUID = uuid16(0xFF60)

    /** Notificações do patinete → app. */
    val RX: UUID = uuid16(0xFF61)

    /** Escritas do app → patinete. Nesta fase NADA é escrito aqui. */
    val WR: UUID = uuid16(0xFF62)
}

enum class GattState { Disconnected, Connected }

enum class GattError { ConnectFailed, Disconnected, ServiceDiscoveryFailed, SubscribeFailed, WriteFailed, PermissionDenied }

/** Falha tipada da camada GATT. Implementações reais devem converter status/exceções do framework nisto. */
class GattException(val error: GattError, message: String? = null) : Exception(message ?: error.name)

data class GattServiceInfo(val uuid: UUID, val characteristics: Set<UUID>)

/**
 * Fronteira fina sobre o GATT do Android (BluetoothGatt) para o transporte poder ser testado na JVM.
 * Todas as operações suspendem até o resultado ou lançam [GattException]; cancelar a coroutine abandona a espera
 * (um callback tardio do framework deve ser ignorado pela implementação).
 */
interface GattClient {
    /** [GattState.Connected] entre o `connect` bem-sucedido e a perda do enlace / `close()`. */
    val state: StateFlow<GattState>

    /** Notificações brutas (valores de característica). Fluxo quente: quem coleta antes de [enableNotifications] não perde nada. */
    val notifications: Flow<ByteArray>

    suspend fun connect(address: String)
    suspend fun discoverServices(): List<GattServiceInfo>

    /** Habilita notificações: grava o CCCD (escrita de configuração, única permitida na Fase 4.x). */
    suspend fun enableNotifications(service: UUID, characteristic: UUID)

    /** Existe na interface, mas `BleScooterTransport` NÃO a usa na Fase 4.x (nenhum comando é enviado). */
    suspend fun write(service: UUID, characteristic: UUID, value: ByteArray)

    /** Idempotente; libera o GATT e falha qualquer operação pendente com [GattError.Disconnected]. */
    fun close()
}

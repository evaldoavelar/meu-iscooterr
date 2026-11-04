package br.dev.evaldo.meuscooter.ble

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.annotation.SuppressLint
import android.content.Context
import br.dev.evaldo.meuscooter.data.CallbackGate
import br.dev.evaldo.meuscooter.data.CccdMode
import br.dev.evaldo.meuscooter.data.GattClient
import br.dev.evaldo.meuscooter.data.GattError
import br.dev.evaldo.meuscooter.data.GattException
import br.dev.evaldo.meuscooter.data.GattProperties
import br.dev.evaldo.meuscooter.data.GattServiceInfo
import br.dev.evaldo.meuscooter.data.GattState
import br.dev.evaldo.meuscooter.data.MtuNegotiator
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * [GattClient] sobre `BluetoothGatt`. CÓDIGO DE FRAMEWORK: fora da cobertura JaCoCo (depende do stack Bluetooth do
 * aparelho; a lógica pura foi extraída para `GattProperties`, testada em `:data`). Ligado SÓ pela chave de depuração.
 *
 * Fase 4.x: [write] está fisicamente desabilitado (lança). A única escrita é o CCCD de [enableNotifications].
 * Uma operação por vez; o callback tardio de uma operação já abandonada (timeout/cancelamento) é ignorado porque
 * `pending` é limpo ao sair e o transporte fecha o GATT em seguida; callbacks de um `BluetoothGatt` que não é o atual
 * (ex.: GATT anterior já fechado) são descartados por `gate`.
 */
// MissingPermission: toda chamada protegida roda dentro de `await` (que converte SecurityException em PermissionDenied) ou de `close()` (que a captura).
@SuppressLint("MissingPermission")
class AndroidGattClient(context: Context) : GattClient {
    private val appContext = context.applicationContext
    private val adapter: BluetoothAdapter? = appContext.getSystemService(BluetoothManager::class.java)?.adapter

    private val _state = MutableStateFlow(GattState.Disconnected)
    override val state: StateFlow<GattState> = _state.asStateFlow()

    private val _notifications = MutableSharedFlow<ByteArray>(extraBufferCapacity = 256)
    override val notifications: Flow<ByteArray> = _notifications.asSharedFlow()

    @Volatile private var gatt: BluetoothGatt? = null
    @Volatile private var pending: CompletableDeferred<Unit>? = null

    /** Passo MTU separado de [pending]: um `onMtuChanged` espontâneo nunca completa connect/discover/CCCD (regra pura em `:data`). */
    private val mtu = MtuNegotiator()

    /** Só callbacks do `BluetoothGatt` atual valem (regra pura e testada: `CallbackGate` em `:data`). */
    private val gate = CallbackGate<BluetoothGatt>()

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (!gate.accepts(g)) return
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                _state.value = GattState.Connected
                pending?.complete(Unit)
            } else {
                _state.value = GattState.Disconnected
                this@AndroidGattClient.mtu.onClosed() // M-1: pedido de MTU pendente falha em vez de esperar o timeout
                val cleanDisconnect = newState == BluetoothProfile.STATE_DISCONNECTED && status == BluetoothGatt.GATT_SUCCESS
                pending?.completeExceptionally(GattException(if (cleanDisconnect) GattError.Disconnected else GattError.ConnectFailed, "status=$status"))
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (gate.accepts(g)) finish(status, GattError.ServiceDiscoveryFailed)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (gate.accepts(g)) finish(status, GattError.SubscribeFailed)
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            if (!gate.accepts(g)) return
            this@AndroidGattClient.mtu.onChanged(mtu, status == BluetoothGatt.GATT_SUCCESS)
        }

        // API 33+ chama esta; abaixo disso, a versão antiga. Cada uma emite uma vez.
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            if (!gate.accepts(g)) return
            _notifications.tryEmit(value.copyOf())
        }

        @Deprecated("API < 33")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (!gate.accepts(g)) return
            characteristic.value?.let { _notifications.tryEmit(it.copyOf()) }
        }
    }

    private fun finish(status: Int, error: GattError) {
        val p = pending ?: return
        if (status == BluetoothGatt.GATT_SUCCESS) p.complete(Unit) else p.completeExceptionally(GattException(error, "status=$status"))
    }

    private suspend fun await(start: () -> Boolean, failure: GattError) {
        val p = CompletableDeferred<Unit>()
        pending = p
        try {
            if (!start()) throw GattException(failure, "operação recusada pelo stack")
            p.await()
        } catch (e: SecurityException) {
            throw GattException(GattError.PermissionDenied, e.message)
        } finally {
            pending = null
        }
    }

    override suspend fun connect(address: String) {
        val adapter = adapter ?: throw GattException(GattError.ConnectFailed, "Bluetooth indisponível")
        mtu.reset()
        if (!GattProperties.isValidAddress(address)) throw GattException(GattError.ConnectFailed, "endereço inválido")
        await(
            start = {
                val device: BluetoothDevice = adapter.getRemoteDevice(address)
                gate.expectConnect() // o stack pode chamar o callback antes de connectGatt retornar
                val created = device.connectGatt(appContext, false, callback, BluetoothDevice.TRANSPORT_LE)
                gatt = created
                if (created != null) gate.adopt(created)
                created != null
            },
            failure = GattError.ConnectFailed,
        )
    }

    override suspend fun discoverServices(): List<GattServiceInfo> {
        val g = gatt ?: throw GattException(GattError.Disconnected)
        await(start = { g.discoverServices() }, failure = GattError.ServiceDiscoveryFailed)
        return g.services.map { s -> GattServiceInfo(s.uuid, s.characteristics.map { it.uuid }.toSet()) }
    }

    override suspend fun requestMtu(mtu: Int): Int {
        val g = gatt ?: throw GattException(GattError.Disconnected)
        try {
            return this.mtu.request(start = { g.requestMtu(mtu) })
        } catch (e: SecurityException) {
            throw GattException(GattError.PermissionDenied, e.message)
        }
    }

    @Suppress("DEPRECATION")
    override suspend fun enableNotifications(service: UUID, characteristic: UUID) {
        val g = gatt ?: throw GattException(GattError.Disconnected)
        val c = g.getService(service)?.getCharacteristic(characteristic) ?: throw GattException(GattError.SubscribeFailed, "característica ausente")
        val mode = GattProperties.cccdMode(c.properties) ?: throw GattException(GattError.SubscribeFailed, "sem notify/indicate")
        val cccd = c.getDescriptor(CCCD) ?: throw GattException(GattError.SubscribeFailed, "CCCD ausente")
        val value = if (mode == CccdMode.Notify) BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE else BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        await(
            start = {
                g.setCharacteristicNotification(c, true) && if (android.os.Build.VERSION.SDK_INT >= 33) {
                    g.writeDescriptor(cccd, value) == BluetoothStatusCodes.SUCCESS
                } else {
                    cccd.value = value
                    g.writeDescriptor(cccd)
                }
            },
            failure = GattError.SubscribeFailed,
        )
    }

    /** Fase 4.x: nenhum comando é enviado. Mesmo que algo chame, a camada real recusa. */
    override suspend fun write(service: UUID, characteristic: UUID, value: ByteArray) {
        throw GattException(GattError.WriteFailed, "escrita desabilitada na Fase 4.x")
    }

    override fun close() {
        val g = gatt
        gatt = null
        gate.close()
        _state.value = GattState.Disconnected
        pending?.completeExceptionally(GattException(GattError.Disconnected))
        mtu.onClosed()
        if (g != null) {
            try {
                g.disconnect()
            } catch (_: SecurityException) {
            }
            try {
                g.close()
            } catch (_: SecurityException) {
            }
        }
    }

    private companion object {
        /** CCCD padrão do Bluetooth (0x2902); `[ESTÁTICO]` também em BleConstants do app original. */
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}

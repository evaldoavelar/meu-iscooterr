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
import br.dev.evaldo.meuscooter.data.CccdMode
import br.dev.evaldo.meuscooter.data.GattClient
import br.dev.evaldo.meuscooter.data.GattError
import br.dev.evaldo.meuscooter.data.GattException
import br.dev.evaldo.meuscooter.data.GattProperties
import br.dev.evaldo.meuscooter.data.GattServiceInfo
import br.dev.evaldo.meuscooter.data.GattSession
import br.dev.evaldo.meuscooter.data.GattSessions
import br.dev.evaldo.meuscooter.data.GattState
import br.dev.evaldo.meuscooter.data.WriteMode
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * [GattClient] sobre `BluetoothGatt`. CÓDIGO DE FRAMEWORK: fora da cobertura JaCoCo (depende do stack Bluetooth do
 * aparelho; a lógica pura foi extraída para `GattProperties` e `GattSessions`, testadas em `:data`). Ligado SÓ pela
 * chave de depuração.
 *
 * Posse por sessão (Codex r3; ADR 0003): cada `connectGatt` recebe uma `GattSession` NOVA, aberta antes da chamada e
 * capturada pelo seu [SessionCallback]. TODO o estado mutável por conexão — operação pendente (connect/discover/CCCD),
 * espera de MTU (`MtuNegotiator`), escritas (`WriteSlot`) e o próprio `BluetoothGatt` — é dela; a classe só traduz os
 * callbacks. `GattSessions` valida "esta sessão ainda é a corrente?" e conclui/publica [state] no mesmo lock, então um
 * callback tardio de um GATT fechado nunca conclui nada da sessão nova nem publica estado sobre ela. `close()` fecha a
 * sessão (final) e falha as esperas dela com `Disconnected`.
 *
 * [write] só é chamado pelo modo TX do transporte (chave `ble_tx`, etapa 4.3a: CONNECT 0x30 + heartbeat 0x10); sem ela
 * a única escrita é o CCCD de [enableNotifications]. A escrita conclui em `onCharacteristicWrite` pela vaga identificada
 * da sessão (`WriteSlot`): o callback de uma escrita abandonada (timeout/cancelamento) é consumido por ela e, enquanto
 * ele é devido, uma nova escrita é recusada (`WriteFailed`) — e o transporte, nesse caso, encerra o GATT (`WriteTimedOut`).
 */
// MissingPermission: toda chamada protegida roda dentro de `await`/`requestMtu` da sessão (que convertem SecurityException em PermissionDenied), de `write` ou de `release` (que a capturam).
@SuppressLint("MissingPermission")
class AndroidGattClient(context: Context) : GattClient {
    private val appContext = context.applicationContext
    private val adapter: BluetoothAdapter? = appContext.getSystemService(BluetoothManager::class.java)?.adapter

    private val sessions = GattSessions<BluetoothGatt>()
    override val state: StateFlow<GattState> = sessions.state

    private val _notifications = MutableSharedFlow<ByteArray>(extraBufferCapacity = 256)
    override val notifications: Flow<ByteArray> = _notifications.asSharedFlow()

    /** Callbacks de UMA sessão (um objeto por `connectGatt`): só tocam a [session] capturada. */
    private inner class SessionCallback(private val session: GattSession<BluetoothGatt>) : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                session.onConnected()
            } else {
                val cleanDisconnect = newState == BluetoothProfile.STATE_DISCONNECTED && status == BluetoothGatt.GATT_SUCCESS
                session.onDisconnected(if (cleanDisconnect) GattError.Disconnected else GattError.ConnectFailed, "status=$status")
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) = session.onOperationDone(failure(status, GattError.ServiceDiscoveryFailed))

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) =
            session.onOperationDone(failure(status, GattError.SubscribeFailed))

        override fun onCharacteristicWrite(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) =
            session.onWriteDone(failure(status, GattError.WriteFailed))

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) = session.onMtuChanged(mtu, status == BluetoothGatt.GATT_SUCCESS)

        // API 33+ chama esta; abaixo disso, a versão antiga. Cada uma emite uma vez.
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            session.ifCurrent { _notifications.tryEmit(value.copyOf()) }
        }

        @Deprecated("API < 33")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            val value = characteristic.value ?: return
            session.ifCurrent { _notifications.tryEmit(value.copyOf()) }
        }
    }

    private fun failure(status: Int, error: GattError): GattException? =
        if (status == BluetoothGatt.GATT_SUCCESS) null else GattException(error, "status=$status")

    /** O `BluetoothGatt` da sessão corrente (já devolvido por `connectGatt`). */
    private fun current(): Pair<GattSession<BluetoothGatt>, BluetoothGatt> {
        val s = sessions.current() ?: throw GattException(GattError.Disconnected)
        return s to (s.handle ?: throw GattException(GattError.Disconnected))
    }

    override suspend fun connect(address: String) {
        val adapter = adapter ?: throw GattException(GattError.ConnectFailed, "Bluetooth indisponível")
        if (!GattProperties.isValidAddress(address)) throw GattException(GattError.ConnectFailed, "endereço inválido")
        val previous = sessions.current()
        val session = sessions.open() // antes de connectGatt: o stack pode chamar o callback antes do retorno
        previous?.let(::release)
        session.await(GattError.ConnectFailed) {
            val device: BluetoothDevice = adapter.getRemoteDevice(address)
            val created = device.connectGatt(appContext, false, SessionCallback(session), BluetoothDevice.TRANSPORT_LE)
            session.handle = created
            // `close()` durante connectGatt já fechou a sessão sem o handle: libera aqui (fechar 2× é inócuo).
            if (created != null && session.ifCurrent { true } == null) release(session)
            created != null
        }
    }

    override suspend fun discoverServices(): List<GattServiceInfo> {
        val (s, g) = current()
        s.await(GattError.ServiceDiscoveryFailed) { g.discoverServices() }
        return g.services.map { svc -> GattServiceInfo(svc.uuid, svc.characteristics.map { it.uuid }.toSet()) }
    }

    override suspend fun requestMtu(mtu: Int): Int {
        val (s, g) = current()
        return s.requestMtu { g.requestMtu(mtu) }
    }

    @Suppress("DEPRECATION")
    override suspend fun enableNotifications(service: UUID, characteristic: UUID) {
        val (s, g) = current()
        val c = g.getService(service)?.getCharacteristic(characteristic) ?: throw GattException(GattError.SubscribeFailed, "característica ausente")
        val mode = GattProperties.cccdMode(c.properties) ?: throw GattException(GattError.SubscribeFailed, "sem notify/indicate")
        val cccd = c.getDescriptor(CCCD) ?: throw GattException(GattError.SubscribeFailed, "CCCD ausente")
        val value = if (mode == CccdMode.Notify) BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE else BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        s.await(GattError.SubscribeFailed) {
            g.setCharacteristicNotification(c, true) && if (android.os.Build.VERSION.SDK_INT >= 33) {
                g.writeDescriptor(cccd, value) == BluetoothStatusCodes.SUCCESS
            } else {
                cccd.value = value
                g.writeDescriptor(cccd)
            }
        }
    }

    /**
     * Grava [value] (cópia) e espera `onCharacteristicWrite`. Recusa do stack/status ≠ sucesso → [GattError.WriteFailed];
     * nunca trava: `close()` falha a espera e o transporte limita cada escrita com o prazo do ACK (vencido → fecha o GATT).
     */
    @Suppress("DEPRECATION")
    override suspend fun write(service: UUID, characteristic: UUID, value: ByteArray) {
        val (s, g) = current()
        val writes = s.writes // slot da mesma sessão de `g`; fechado por `close()` → `begin` recusa (Disconnected)
        val c = g.getService(service)?.getCharacteristic(characteristic) ?: throw GattException(GattError.WriteFailed, "característica ausente")
        val type = when (GattProperties.writeMode(c.properties) ?: throw GattException(GattError.WriteFailed, "característica sem escrita")) {
            WriteMode.WithResponse -> BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            WriteMode.WithoutResponse -> BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        }
        val bytes = value.copyOf()
        val op = writes.begin() // recusa se uma escrita anterior ainda deve o callback (estado indeterminado)
        try {
            val accepted = try {
                if (android.os.Build.VERSION.SDK_INT >= 33) {
                    g.writeCharacteristic(c, bytes, type) == BluetoothStatusCodes.SUCCESS
                } else {
                    c.writeType = type
                    c.value = bytes
                    g.writeCharacteristic(c)
                }
            } catch (e: SecurityException) {
                throw GattException(GattError.PermissionDenied, e.message)
            }
            if (!accepted) throw GattException(GattError.WriteFailed, "operação recusada pelo stack")
            writes.started(op)
            op.result.await()
        } finally {
            writes.end(op) // abandonada sem callback (timeout/cancelamento) → o próximo callback é dela, não de outra escrita
        }
    }

    override fun close() {
        sessions.close()?.let(::release)
    }

    /** Libera o `BluetoothGatt` de uma sessão já fechada (fora do lock de `GattSessions`). */
    private fun release(session: GattSession<BluetoothGatt>) {
        val g = session.handle ?: return
        try {
            g.disconnect()
        } catch (_: SecurityException) {
        }
        try {
            g.close()
        } catch (_: SecurityException) {
        }
    }

    private companion object {
        /** CCCD padrão do Bluetooth (0x2902); `[ESTÁTICO]` também em BleConstants do app original. */
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}

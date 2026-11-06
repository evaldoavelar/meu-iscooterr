package br.dev.evaldo.meuscooter

import android.app.Activity
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import br.dev.evaldo.meuscooter.domain.BluetoothEnvironment
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.lang.ref.WeakReference

/**
 * Ambiente Bluetooth REAL só para o primeiro uso com varredura (F5.2c): a tela de bloqueio ("Ligue o Bluetooth",
 * "Permitir") aparece em vez de a busca ficar muda. Nenhuma conexão nem escrita. O estado é relido por [refresh]
 * (resultado de permissão, `onResume` ao voltar das configurações).
 *
 * O ViewModel sobrevive à recriação da Activity e guarda esta instância: ela só retém o contexto de aplicação; a
 * Activity atual entra por [attach] (a cada `onCreate`) e sai por [detach] (`onDestroy`), por referência fraca.
 */
class AndroidScanEnvironment(
    private val context: Context,
    private val scanPermission: String,
    private val requestCode: Int,
) : BluetoothEnvironment {
    private val _bluetooth = MutableStateFlow(isBluetoothOn())
    private val _permission = MutableStateFlow(isPermissionGranted())
    override val bluetoothEnabled: StateFlow<Boolean> = _bluetooth.asStateFlow()
    override val permissionGranted: StateFlow<Boolean> = _permission.asStateFlow()
    private var host: WeakReference<Activity>? = null

    /** Religa a Activity atual e relê o estado: a instância compartilhada sobrevive à Activity e pode estar velha. */
    fun attach(activity: Activity) { host = WeakReference(activity); refresh() }
    fun detach(activity: Activity) { if (host?.get() === activity) host = null }

    fun refresh() {
        _bluetooth.value = isBluetoothOn()
        _permission.value = isPermissionGranted()
    }

    /** `ACTION_REQUEST_ENABLE` exige BLUETOOTH_CONNECT (31+): as configurações de Bluetooth não exigem nada. */
    override fun requestEnableBluetooth() {
        host?.get()?.let { runCatching { it.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) } }
    }

    override fun requestPermission() {
        host?.get()?.let { runCatching { it.requestPermissions(arrayOf(scanPermission), requestCode) } }
    }

    /** Depois de duas negações o sistema ignora `requestPermissions`: o caminho é a tela do app nas configurações. */
    override fun openSystemSettings() {
        host?.get()?.let {
            runCatching { it.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", it.packageName, null))) }
        }
    }

    private fun isBluetoothOn(): Boolean =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter?.isEnabled == true

    private fun isPermissionGranted(): Boolean = context.checkSelfPermission(scanPermission) == PackageManager.PERMISSION_GRANTED
}

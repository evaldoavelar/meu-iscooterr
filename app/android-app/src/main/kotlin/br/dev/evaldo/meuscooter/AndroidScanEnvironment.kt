package br.dev.evaldo.meuscooter

import android.app.Activity
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import br.dev.evaldo.meuscooter.domain.BluetoothEnvironment
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.lang.ref.WeakReference

/**
 * ADR 0006 (S41): Lógica pura de decisão da exigência de localização para varredura BLE.
 * Em API < 31, a varredura BLE exige localização ativada no sistema operacional.
 * Em API >= 31, a permissão BLUETOOTH_SCAN desacopla a varredura de localização.
 */
object ScanEnvironmentLogic {
    fun isLocationReady(sdkInt: Int, locationEnabled: Boolean): Boolean =
        sdkInt >= 31 || locationEnabled
}

/**
 * Ambiente Bluetooth REAL só para o primeiro uso com varredura (F5.2c): a tela de bloqueio ("Ligue o Bluetooth",
 * "Permitir", "Ligue a localização") aparece em vez de a busca ficar muda. Nenhuma conexão nem escrita. O estado é relido por [refresh]
 * (resultado de permissão, `onResume` ao voltar das configurações).
 *
 * O ViewModel sobrevive à recriação da Activity e guarda esta instância: ela só retém o contexto de aplicação; a
 * Activity atual entra por [attach] (a cada `onCreate`) e sai por [detach] (`onDestroy`), por referência fraca.
 */
class AndroidScanEnvironment(
    private val context: Context,
    private val scanPermission: String,
    private val requestCode: Int,
    private val sdkInt: Int = Build.VERSION.SDK_INT,
    private val isLocationOn: () -> Boolean = { isLocationSystemEnabled(context) },
) : BluetoothEnvironment {
    private val _bluetooth = MutableStateFlow(isBluetoothOn())
    private val _permission = MutableStateFlow(isPermissionGranted())
    private val _location = MutableStateFlow(ScanEnvironmentLogic.isLocationReady(sdkInt, isLocationOn()))
    override val bluetoothEnabled: StateFlow<Boolean> = _bluetooth.asStateFlow()
    override val permissionGranted: StateFlow<Boolean> = _permission.asStateFlow()
    override val locationEnabled: StateFlow<Boolean> = _location.asStateFlow()
    private var host: WeakReference<Activity>? = null

    /** Religa a Activity atual e relê o estado: a instância compartilhada sobrevive à Activity e pode estar velha. */
    fun attach(activity: Activity) { host = WeakReference(activity); refresh() }
    fun detach(activity: Activity) { if (host?.get() === activity) host = null }

    fun refresh() {
        _bluetooth.value = isBluetoothOn()
        _permission.value = isPermissionGranted()
        _location.value = ScanEnvironmentLogic.isLocationReady(sdkInt, isLocationOn())
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

    /** Em API < 31, abre a tela de configurações de localização do sistema. */
    override fun openLocationSettings() {
        val activity = host?.get()
        if (activity != null) {
            val intent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
            runCatching { activity.startActivity(intent) }.onFailure { openSystemSettings() }
        } else openSystemSettings()
    }

    private fun isBluetoothOn(): Boolean =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter?.isEnabled == true

    private fun isPermissionGranted(): Boolean = context.checkSelfPermission(scanPermission) == PackageManager.PERMISSION_GRANTED

    companion object {
        /**
         * Leitura do LocationManager do sistema Android (fina, sem teste JVM, [ESTÁTICO]).
         */
        fun isLocationSystemEnabled(context: Context): Boolean {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return false
            return if (Build.VERSION.SDK_INT >= 28) {
                lm.isLocationEnabled
            } else {
                lm.isProviderEnabled(LocationManager.GPS_PROVIDER) || lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
            }
        }
    }
}

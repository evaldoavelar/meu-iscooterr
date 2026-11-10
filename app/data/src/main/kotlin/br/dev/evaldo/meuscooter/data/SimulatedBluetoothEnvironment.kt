package br.dev.evaldo.meuscooter.data

import br.dev.evaldo.meuscooter.domain.BluetoothEnvironment
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Ambiente Bluetooth SIMULADO: nenhuma permissão nem intent real do Android. Os "pedidos" do usuário
 * (Ativar, Permitir) apenas viram o estado para ligado/concedido. A versão real é da Fase 4.
 */
class SimulatedBluetoothEnvironment(
    bluetoothEnabled: Boolean = true,
    permissionGranted: Boolean = true,
    locationEnabled: Boolean = true,
) : BluetoothEnvironment {
    private val _bluetooth = MutableStateFlow(bluetoothEnabled)
    private val _permission = MutableStateFlow(permissionGranted)
    private val _location = MutableStateFlow(locationEnabled)
    override val bluetoothEnabled: StateFlow<Boolean> = _bluetooth.asStateFlow()
    override val permissionGranted: StateFlow<Boolean> = _permission.asStateFlow()
    override val locationEnabled: StateFlow<Boolean> = _location.asStateFlow()

    /** Quantas vezes "Abrir configurações do Android" foi pedido (simulado: nada é aberto). */
    var settingsOpenedCount: Int = 0
        private set
    var locationSettingsOpenedCount: Int = 0
        private set

    fun setBluetoothEnabled(on: Boolean) { _bluetooth.value = on }
    fun setPermissionGranted(granted: Boolean) { _permission.value = granted }
    fun setLocationEnabled(on: Boolean) { _location.value = on }

    override fun requestEnableBluetooth() { _bluetooth.value = true }
    override fun requestPermission() { _permission.value = true }
    override fun openSystemSettings() { settingsOpenedCount++ }
    override fun openLocationSettings() { locationSettingsOpenedCount++; openSystemSettings() }
}

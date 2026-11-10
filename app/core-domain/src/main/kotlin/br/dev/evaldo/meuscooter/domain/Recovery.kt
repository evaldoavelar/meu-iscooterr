package br.dev.evaldo.meuscooter.domain

import kotlinx.coroutines.flow.StateFlow

/** Fotografia do ambiente Bluetooth do celular. */
data class BluetoothState(
    val bluetoothEnabled: Boolean,
    val permissionGranted: Boolean,
    val locationEnabled: Boolean = true,
) {
    val ready: Boolean get() = bluetoothEnabled && permissionGranted && locationEnabled
}

/**
 * Ambiente Bluetooth do celular atrás de interface. Nesta fase só existe a implementação SIMULADA
 * (`SimulatedBluetoothEnvironment`); permissões e intents reais do Android ficam para a Fase 4.
 */
interface BluetoothEnvironment {
    val bluetoothEnabled: StateFlow<Boolean>
    val permissionGranted: StateFlow<Boolean>
    val locationEnabled: StateFlow<Boolean> get() = kotlinx.coroutines.flow.MutableStateFlow(true)

    /** "Ativar Bluetooth". */
    fun requestEnableBluetooth()

    /** "Permitir" (Dispositivos próximos). */
    fun requestPermission()

    /** "Abrir configurações do Android". */
    fun openSystemSettings()

    /** "Abrir configurações de localização" (em API < 31 onde varredura BLE exige localização ligada). */
    fun openLocationSettings() { openSystemSettings() }
}

/** Por que o app não está conectado (design/screens.py `status`). */
sealed interface RecoveryReason {
    data object BluetoothOff : RecoveryReason
    data object PermissionDenied : RecoveryReason
    data object LocationOff : RecoveryReason
    data object OutOfRange : RecoveryReason
    data class Reconnecting(val attempt: Int, val max: Int = MAX_ATTEMPTS) : RecoveryReason {
        val attemptLabel: String get() = "Tentativa $attempt de $max"
    }

    companion object {
        const val MAX_ATTEMPTS = 5

        /**
         * Prioridade (decisão): permissão negada > Bluetooth desligado > localização desligada > fora de alcance > reconectando.
         * Sem permissão, sem Bluetooth ou sem localização (quando exigida), tentar de novo é inútil, então o motivo
         * do ambiente vem primeiro; "fora de alcance" (tentativas esgotadas) vence "reconectando" (tentativa em curso). `null` = nada a recuperar.
         */
        fun from(env: BluetoothState, outOfRange: Boolean, reconnectAttempt: Int?): RecoveryReason? = when {
            !env.permissionGranted -> PermissionDenied
            !env.bluetoothEnabled -> BluetoothOff
            !env.locationEnabled -> LocationOff
            outOfRange -> OutOfRange
            reconnectAttempt != null -> Reconnecting(reconnectAttempt)
            else -> null
        }
    }
}

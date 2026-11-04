package br.dev.evaldo.meuscooter.ble

import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanSettings
import android.content.Context
import br.dev.evaldo.meuscooter.data.ScanError
import br.dev.evaldo.meuscooter.data.ScanException
import br.dev.evaldo.meuscooter.data.ScanResult
import br.dev.evaldo.meuscooter.data.ScooterScanner
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * [ScooterScanner] sobre `BluetoothLeScanner`. CÓDIGO DE FRAMEWORK (fora da cobertura). Só varredura: não conecta,
 * não escreve. Coletar inicia o scan; cancelar a coleta chama `stopScan`. Sem filtro de sistema: o nome do patinete
 * é `[DESCONHECIDO]`, a filtragem é lógica pura em `ScooterDiscovery`.
 */
class AndroidBleScanner(context: Context) : ScooterScanner {
    private val adapter = context.applicationContext.getSystemService(BluetoothManager::class.java)?.adapter

    override fun scan(): Flow<ScanResult> = callbackFlow {
        val scanner = adapter?.takeIf { it.isEnabled }?.bluetoothLeScanner
        if (scanner == null) {
            close(ScanException(ScanError.Unavailable))
            awaitClose { }
            return@callbackFlow
        }
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: android.bluetooth.le.ScanResult) {
                // scanRecord.deviceName não exige BLUETOOTH_CONNECT; device.name exigiria.
                trySend(ScanResult(result.scanRecord?.deviceName, result.device.address, result.rssi))
            }

            override fun onScanFailed(errorCode: Int) {
                close(ScanException(ScanError.Unavailable))
            }
        }
        try {
            scanner.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), callback)
        } catch (e: SecurityException) {
            close(ScanException(ScanError.PermissionDenied))
        }
        awaitClose {
            try {
                scanner.stopScan(callback)
            } catch (_: SecurityException) {
            }
        }
    }
}

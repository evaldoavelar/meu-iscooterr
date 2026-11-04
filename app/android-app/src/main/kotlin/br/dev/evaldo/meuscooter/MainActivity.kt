package br.dev.evaldo.meuscooter

import android.Manifest
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import br.dev.evaldo.meuscooter.ble.AndroidGattClient
import br.dev.evaldo.meuscooter.data.BleScooterTransport
import br.dev.evaldo.meuscooter.data.FakeScooterTransport
import br.dev.evaldo.meuscooter.data.RealBleSelection
import br.dev.evaldo.meuscooter.protocol.Clock
import br.dev.evaldo.meuscooter.ui.AppRoot
import br.dev.evaldo.meuscooter.ui.HomeViewModel
import br.dev.evaldo.meuscooter.ui.theme.MeuPatineteTheme
import br.dev.evaldo.meuscooter.ui.theme.resolveDark
import java.text.DateFormat
import java.util.Date

/**
 * Padrão: APENAS o `FakeScooterTransport`. Chave de depuração (desligada por padrão): em build depurável,
 * `--ez ble_real true --es ble_address AA:BB:CC:DD:EE:FF` troca para `BleScooterTransport` (só escuta; nenhum
 * comando é enviado na Fase 4.x). Só o proprietário liga isso, com o patinete no cavalete (plano Fase 4, etapa 4.2).
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val debuggable = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        val bleAddress = intent.getStringExtra(EXTRA_BLE_ADDRESS)
        val realBle = RealBleSelection.enabled(debuggable, intent.getBooleanExtra(EXTRA_BLE_REAL, false), bleAddress)
        if (realBle && Build.VERSION.SDK_INT >= 31 &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 1)
        }
        setContent {
            val vm = viewModel<HomeViewModel>(factory = object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    val start = System.currentTimeMillis()
                    val mono = android.os.SystemClock.elapsedRealtime()
                    val timeFormat = DateFormat.getTimeInstance(DateFormat.SHORT)
                    return HomeViewModel(
                        transportFactory = { scope, clock ->
                            if (realBle) BleScooterTransport(scope, clock, AndroidGattClient(applicationContext), bleAddress!!)
                            else FakeScooterTransport(scope, clock)
                        },
                        clock = Clock { android.os.SystemClock.elapsedRealtime() },
                        deviceName = null,
                        // Fase 3: ambiente Bluetooth SIMULADO; permissões reais só na Fase 4.
                        bluetoothEnvironment = br.dev.evaldo.meuscooter.data.SimulatedBluetoothEnvironment(),
                        tripRepository = br.dev.evaldo.meuscooter.domain.InMemoryTripRepository(br.dev.evaldo.meuscooter.data.SampleTrips.build(start)),
                        timeLabel = { atMs -> timeFormat.format(Date(start + (atMs - mono))) },
                    ) as T
                }
            })
            val prefs by vm.preferences.collectAsStateWithLifecycle()
            MeuPatineteTheme(darkTheme = prefs.theme.resolveDark(isSystemInDarkTheme())) { AppRoot(vm) }
        }
    }

    private companion object {
        const val EXTRA_BLE_REAL = "ble_real"
        const val EXTRA_BLE_ADDRESS = "ble_address"
    }
}

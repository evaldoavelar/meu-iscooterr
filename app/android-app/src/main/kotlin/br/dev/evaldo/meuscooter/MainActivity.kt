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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import br.dev.evaldo.meuscooter.ble.AndroidGattClient
import br.dev.evaldo.meuscooter.data.BleConfig
import br.dev.evaldo.meuscooter.data.BleScooterTransport
import br.dev.evaldo.meuscooter.data.FakeScooterTransport
import br.dev.evaldo.meuscooter.data.RealBleSelection
import br.dev.evaldo.meuscooter.protocol.Clock
import br.dev.evaldo.meuscooter.data.DiskTileCacheStore
import br.dev.evaldo.meuscooter.data.HttpTileNetwork
import br.dev.evaldo.meuscooter.domain.CachingTileLoader
import br.dev.evaldo.meuscooter.domain.TileCachePolicy
import br.dev.evaldo.meuscooter.domain.TileProvider
import br.dev.evaldo.meuscooter.domain.TileSources
import br.dev.evaldo.meuscooter.ui.AppRoot
import br.dev.evaldo.meuscooter.ui.history.LocalMapTiles
import br.dev.evaldo.meuscooter.ui.history.MapTiles
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import br.dev.evaldo.meuscooter.ui.HomeViewModel
import br.dev.evaldo.meuscooter.ui.theme.MeuPatineteTheme
import br.dev.evaldo.meuscooter.ui.theme.resolveDark
import java.text.DateFormat
import java.util.Date

/**
 * Padrão: APENAS o `FakeScooterTransport`. Chave de depuração (desligada por padrão): em build depurável,
 * `--ez ble_real true --es ble_address AA:BB:CC:DD:EE:FF` troca para `BleScooterTransport` (só escuta: a única
 * escrita é o CCCD). A chave SEPARADA `--ez ble_tx true` (também desligada por padrão, só com `ble_real`) liga o modo TX
 * da etapa 4.3a: CONNECT 0x30 + heartbeat 0x10, nada mais. Só o proprietário liga isso, com o patinete no cavalete
 * (plano Fase 4, §7.3).
 */
class MainActivity : ComponentActivity() {
    private val tileScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Tiles do mapa: provedor padrão (OSM), cache em disco (30 dias, 50 MB) e User-Agent "Meu iScooter/<versão> (uso pessoal)". */
    private val mapTiles by lazy {
        val version = packageManager.getPackageInfo(packageName, 0).versionName ?: "dev"
        val source = TileSources.create(TileProvider.DEFAULT, version)
        MapTiles(
            CachingTileLoader(source, HttpTileNetwork(), DiskTileCacheStore(java.io.File(cacheDir, "map-tiles")), TileCachePolicy(), System::currentTimeMillis, tileScope),
            source.attribution,
        )
    }

    private val locationGranted = kotlinx.coroutines.flow.MutableStateFlow(false)

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_LOCATION) locationGranted.value = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    override fun onDestroy() {
        tileScope.cancel()
        super.onDestroy()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val debuggable = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        val bleAddress = intent.getStringExtra(EXTRA_BLE_ADDRESS)
        val realBle = RealBleSelection.enabled(debuggable, intent.getBooleanExtra(EXTRA_BLE_REAL, false), bleAddress)
        val bleTx = RealBleSelection.txEnabled(debuggable, realBle, intent.getBooleanExtra(EXTRA_BLE_TX, false))
        if (realBle && Build.VERSION.SDK_INT >= 31 &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 1)
        }
        // Localização é opcional (velocidade/distância da viagem por GPS): pedida só com o BLE real e sem bloquear o app.
        if (realBle) {
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), REQUEST_LOCATION)
            } else locationGranted.value = true
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
                            if (realBle) BleScooterTransport(scope, clock, AndroidGattClient(applicationContext, clock), bleAddress!!, BleConfig(txEnabled = bleTx))
                            else FakeScooterTransport(scope, clock)
                        },
                        clock = Clock { android.os.SystemClock.elapsedRealtime() },
                        deviceName = null,
                        // GPS só com o BLE real (o Fake já entrega km/h); permissão opcional, nunca bloqueia.
                        gpsSpeedSource = if (realBle) AndroidGpsSpeedSource(applicationContext, locationGranted) else br.dev.evaldo.meuscooter.domain.GpsSpeedSource.None,
                        // Fase 3: ambiente Bluetooth SIMULADO; permissões reais só na Fase 4.
                        bluetoothEnvironment = br.dev.evaldo.meuscooter.data.SimulatedBluetoothEnvironment(),
                        tripRepository = br.dev.evaldo.meuscooter.domain.InMemoryTripRepository(br.dev.evaldo.meuscooter.data.SampleTrips.build(start)),
                        timeLabel = { atMs -> timeFormat.format(Date(start + (atMs - mono))) },
                    ) as T
                }
            })
            val prefs by vm.preferences.collectAsStateWithLifecycle()
            MeuPatineteTheme(darkTheme = prefs.theme.resolveDark(isSystemInDarkTheme())) { CompositionLocalProvider(LocalMapTiles provides mapTiles) { AppRoot(vm) } }
        }
    }

    private companion object {
        const val EXTRA_BLE_REAL = "ble_real"
        const val EXTRA_BLE_ADDRESS = "ble_address"
        const val EXTRA_BLE_TX = "ble_tx"
        const val REQUEST_LOCATION = 2
    }
}

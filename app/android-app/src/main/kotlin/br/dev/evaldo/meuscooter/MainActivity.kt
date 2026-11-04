package br.dev.evaldo.meuscooter

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
import br.dev.evaldo.meuscooter.data.FakeScooterTransport
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

/** Fase 3: liga APENAS o `FakeScooterTransport` (nenhum BLE real, nenhum comando ao patinete). */
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

    override fun onDestroy() {
        tileScope.cancel()
        super.onDestroy()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val vm = viewModel<HomeViewModel>(factory = object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    val start = System.currentTimeMillis()
                    val mono = android.os.SystemClock.elapsedRealtime()
                    val timeFormat = DateFormat.getTimeInstance(DateFormat.SHORT)
                    return HomeViewModel(
                        transportFactory = { scope, clock -> FakeScooterTransport(scope, clock) },
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
            MeuPatineteTheme(darkTheme = prefs.theme.resolveDark(isSystemInDarkTheme())) { CompositionLocalProvider(LocalMapTiles provides mapTiles) { AppRoot(vm) } }
        }
    }
}

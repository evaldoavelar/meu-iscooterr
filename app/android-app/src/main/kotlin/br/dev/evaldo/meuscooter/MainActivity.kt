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
import br.dev.evaldo.meuscooter.ble.AndroidBleScanner
import br.dev.evaldo.meuscooter.ble.AndroidGattClient
import br.dev.evaldo.meuscooter.data.DiscoveringScooterFinder
import br.dev.evaldo.meuscooter.data.NameFilter
import br.dev.evaldo.meuscooter.data.BleConfig
import br.dev.evaldo.meuscooter.data.BleScooterTransport
import br.dev.evaldo.meuscooter.data.FakeScooterTransport
import br.dev.evaldo.meuscooter.data.GattProperties
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
 * Sem patinete cadastrado: APENAS o `FakeScooterTransport`. Com patinete cadastrado (ADR 0006) o transporte real é o padrão,
 * sem extras. Chave de depuração (só build depurável): `--ez ble_real true --es ble_address AA:BB:CC:DD:EE:FF` usa o
 * `BleScooterTransport` e CADASTRA esse MAC (só escuta: a única escrita é o CCCD). O modo TX (CONNECT 0x30 + heartbeat 0x10, Unlock, Lock modo 2; nada mais) liga com patinete JÁ cadastrado
 * (ADR 0006) ou, em build depurável, com `--ez ble_tx true` (só com `ble_real`). Só o proprietário liga isso, com o patinete no cavalete
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
        if (requestCode == REQUEST_PERMISSIONS) locationGranted.value = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (requestCode == REQUEST_SCAN_PERMISSION) scanEnvironment?.refresh()
    }

    /** Volta das configurações (Bluetooth/permissão): relê o ambiente do primeiro uso real. */
    override fun onResume() {
        super.onResume()
        scanEnvironment?.refresh()
    }

    private var scanEnvironment: AndroidScanEnvironment? = null

    override fun onDestroy() {
        scanEnvironment?.detach(this)
        tileScope.cancel()
        super.onDestroy()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val debuggable = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        // Criado uma vez por Activity; o ViewModel guarda a referência (preferências sobrevivem a reinício do processo).
        val preferencesStore = br.dev.evaldo.meuscooter.domain.KeyValuePreferencesStore(SharedPrefsStorage(applicationContext))
        // ADR 0006: patinete cadastrado → BLE real sem extras; extras de depuração (só build depurável) cadastram o MAC.
        val savedAddress = preferencesStore.current.scooterAddress?.takeIf { GattProperties.isValidAddress(it.trim()) }
        val selection = RealBleSelection.resolve(debuggable, intent.getBooleanExtra(EXTRA_BLE_REAL, false), intent.getStringExtra(EXTRA_BLE_ADDRESS), savedAddress)
        val realBle = selection.real
        val bleAddress = selection.address
        if (selection.registerExtra) {
            // Cadastrar já conta como "primeiro uso concluído" (senão a Home mostraria o fluxo simulado com o transporte real).
            kotlinx.coroutines.runBlocking { preferencesStore.update { it.copy(scooterAddress = bleAddress, onboardingDone = true) } }
        }
        // TX (CONNECT/heartbeat, Unlock, Lock modo 2) com patinete JÁ cadastrado (estado anterior a esta execução) ou via `ble_tx` em build depurável.
        val bleTx = RealBleSelection.txEnabled(debuggable, realBle, intent.getBooleanExtra(EXTRA_BLE_TX, false), registered = selection.registered)
        // Um único requestPermissions (um segundo pedido seguido seria recusado pelo sistema). A localização é opcional
        // (velocidade/distância da viagem por GPS), só com o BLE real e fora dos fluxos de bloqueio/desbloqueio.
        // Só a permissão "aproximada" (COARSE) NÃO liga o GPS (exige FINE); e uma concessão posterior só vale no próximo onCreate.
        if (realBle) {
            val wanted = mutableListOf<String>()
            if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                wanted += Manifest.permission.BLUETOOTH_CONNECT
            }
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                locationGranted.value = true
            } else {
                wanted += Manifest.permission.ACCESS_FINE_LOCATION
                wanted += Manifest.permission.ACCESS_COARSE_LOCATION
            }
            // ADR 0007: notificação do serviço em primeiro plano (só com TX). Negada: o serviço ainda roda, sem notificação visível.
            if (bleTx && Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                wanted += Manifest.permission.POST_NOTIFICATIONS
            }
            if (wanted.isNotEmpty()) requestPermissions(wanted.toTypedArray(), REQUEST_PERMISSIONS)
        }
        // F5.2c: sem patinete cadastrado, o primeiro uso varre de verdade (só varredura, sem conectar). Depende de BLUETOOTH_SCAN (31+) ou localização (<31).
        val scanFinder = if (!realBle && !preferencesStore.current.onboardingDone) {
            val scanPermission = if (Build.VERSION.SDK_INT >= 31) Manifest.permission.BLUETOOTH_SCAN else Manifest.permission.ACCESS_FINE_LOCATION
            // Reaproveita a instância do ViewModel retido (recriação da Activity); só religa a Activity atual.
            scanEnvironment = (sharedScanEnvironment ?: AndroidScanEnvironment(applicationContext, scanPermission, REQUEST_SCAN_PERMISSION)).also { sharedScanEnvironment = it; it.attach(this) }
            if (checkSelfPermission(scanPermission) != PackageManager.PERMISSION_GRANTED) requestPermissions(arrayOf(scanPermission), REQUEST_SCAN_PERMISSION)
            // `ix7`: anúncio `ix7Pro` visto na varredura do S17 `[CONFIRMADO]` como identidade do dispositivo.
            DiscoveringScooterFinder(AndroidBleScanner(applicationContext), NameFilter(listOf("ix7")))
        } else null
        setContent {
            val vm = viewModel<HomeViewModel>(factory = object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    val start = System.currentTimeMillis()
                    val mono = android.os.SystemClock.elapsedRealtime()
                    val timeFormat = DateFormat.getTimeInstance(DateFormat.SHORT)
                    return HomeViewModel(
                        transportFactory = { scope, clock ->
                            if (realBle) BleScooterTransport(scope, clock, AndroidGattClient(applicationContext, clock), bleAddress!!, BleConfig(txEnabled = bleTx), audit = br.dev.evaldo.meuscooter.ble.LogcatTxAuditLog.create(debuggable, bleTx))
                            else FakeScooterTransport(scope, clock)
                        },
                        clock = Clock { android.os.SystemClock.elapsedRealtime() },
                        deviceName = null,
                        // GPS só com o BLE real (o Fake já entrega km/h); permissão opcional, nunca bloqueia.
                        gpsSpeedSource = if (realBle) AndroidGpsSpeedSource(applicationContext, locationGranted) else br.dev.evaldo.meuscooter.domain.GpsSpeedSource.None,
                        // Trajeto GPS (ADR 0012): só com o BLE real; arquivos em disco privado, só neste celular.
                        gpsFixSource = if (realBle) AndroidGpsFixSource(applicationContext, locationGranted) else br.dev.evaldo.meuscooter.domain.GpsFixSource.None,
                        routeStore = br.dev.evaldo.meuscooter.data.FileRouteStore(java.io.File(applicationContext.filesDir, "routes")),
                        locationGranted = locationGranted,
                        ioDispatcher = kotlinx.coroutines.Dispatchers.IO,
                        // Fase 3: ambiente Bluetooth SIMULADO; permissões reais só na Fase 4.
                        preferencesStore = preferencesStore,
                        eventLog = eventLogFor(applicationContext),
                        scooterFinder = scanFinder,
                        // Cadastrado: reabre a Activity (novo ViewModel) para o transporte real e o TX passarem a valer (ADR 0006).
                        onScooterRegistered = {
                            startActivity(android.content.Intent(applicationContext, MainActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK))
                            finish()
                        },
                        // Cofre do PIN só com o patinete real: o PIN sintético do Fake nunca pode ser gravado como se fosse o real.
                        pinVault = if (realBle) createPinVault(applicationContext) else br.dev.evaldo.meuscooter.domain.PinVault.None,
                        // Primeiro uso real: ambiente de verdade (permissão de varredura/Bluetooth); senão o simulado.
                        bluetoothEnvironment = scanEnvironment ?: br.dev.evaldo.meuscooter.data.SimulatedBluetoothEnvironment(),
                        // Patinete real: histórico persistente e começa vazio (ADR 0014); simulado: amostras em memória.
                        tripRepository = if (realBle) br.dev.evaldo.meuscooter.data.FileTripRepository(java.io.File(applicationContext.filesDir, "trips.v1"))
                        else br.dev.evaldo.meuscooter.domain.InMemoryTripRepository(br.dev.evaldo.meuscooter.data.SampleTrips.build(start)),
                        // Patinete cadastrado (BLE real): conecta sozinho ao abrir, também após o cadastro (S29).
                        connectOnStart = realBle,
                        // ADR 0007: serviço em primeiro plano só com BLE real + TX (mantém o heartbeat em segundo plano).
                        keepAlive = if (realBle && bleTx) AndroidSessionKeepAlive(applicationContext) else br.dev.evaldo.meuscooter.domain.SessionKeepAlive.None,
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
        const val REQUEST_PERMISSIONS = 1
        const val REQUEST_SCAN_PERMISSION = 2
        /** Escritor do registro de eventos: vive com o processo (a Activity pode morrer com uma gravação pendente). */
        private val eventLogScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        @Volatile private var sharedEventLog: br.dev.evaldo.meuscooter.domain.EventLog? = null
        /** Um único registro por processo (recriar a Activity não cria um segundo escritor). */
        fun eventLogFor(context: android.content.Context): br.dev.evaldo.meuscooter.domain.EventLog = sharedEventLog ?: synchronized(this) {
            sharedEventLog ?: br.dev.evaldo.meuscooter.domain.KeyValueEventLogStore(
                SharedPrefsStorage(context, "event_log"), Clock { System.currentTimeMillis() }, eventLogScope,
            ).also { sharedEventLog = it }
        }
        /** Mesmo ambiente do ViewModel retido (a factory só roda uma vez); guarda só o contexto de aplicação. */
        @Volatile var sharedScanEnvironment: AndroidScanEnvironment? = null
    }
}

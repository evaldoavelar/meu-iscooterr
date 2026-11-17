package br.dev.evaldo.meuscooter

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.PowerManager
import br.dev.evaldo.meuscooter.ui.PowerSaveState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Regra pura (testável na JVM): a Economia de energia só compromete o GPS nos modos de localização que o desligam/limitam com a tela apagada. */
object PowerSaveLogic {
    /** `PowerManager.LOCATION_MODE_GPS_DISABLED_WHEN_SCREEN_OFF`. */
    const val GPS_DISABLED_WHEN_SCREEN_OFF = 1
    /** `PowerManager.LOCATION_MODE_ALL_DISABLED_WHEN_SCREEN_OFF`. */
    const val ALL_DISABLED_WHEN_SCREEN_OFF = 2
    /** `PowerManager.LOCATION_MODE_THROTTLE_REQUESTS_WHEN_SCREEN_OFF`. */
    const val THROTTLE_REQUESTS_WHEN_SCREEN_OFF = 4

    /** API < 28 não tem `locationPowerSaveMode`: basta [isPowerSaveMode]. */
    fun gpsImpaired(sdkInt: Int, isPowerSaveMode: Boolean, locationPowerSaveMode: Int): Boolean {
        if (!isPowerSaveMode) return false
        if (sdkInt < 28) return true
        return locationPowerSaveMode == GPS_DISABLED_WHEN_SCREEN_OFF ||
            locationPowerSaveMode == ALL_DISABLED_WHEN_SCREEN_OFF ||
            locationPowerSaveMode == THROTTLE_REQUESTS_WHEN_SCREEN_OFF
    }
}

/**
 * Adaptador Android de [PowerSaveState]: valor inicial via `PowerManager` e atualização pelo broadcast protegido
 * `ACTION_POWER_SAVE_MODE_CHANGED` (sem permissão nova). Usa o `applicationContext`; [attach]/[detach] são idempotentes
 * (a MainActivity chama em `onStart`/`onStop`).
 */
class AndroidPowerSaveMonitor(context: Context) : PowerSaveState {
    private val appContext = context.applicationContext
    private val powerManager = appContext.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val _gpsImpaired = MutableStateFlow(read())
    override val gpsImpaired: StateFlow<Boolean> = _gpsImpaired
    private var registered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action == PowerManager.ACTION_POWER_SAVE_MODE_CHANGED) _gpsImpaired.value = read()
        }
    }

    /** Registra o receiver (se ainda não) e relê o estado (pode ter mudado enquanto parado). */
    @Synchronized fun attach() {
        if (!registered) {
            val filter = IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
            if (Build.VERSION.SDK_INT >= 33) appContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            else appContext.registerReceiver(receiver, filter)
            registered = true
        }
        _gpsImpaired.value = read()
    }

    @Synchronized fun detach() {
        if (registered) {
            runCatching { appContext.unregisterReceiver(receiver) }
            registered = false
        }
    }

    private fun read(): Boolean = PowerSaveLogic.gpsImpaired(
        Build.VERSION.SDK_INT,
        powerManager.isPowerSaveMode,
        if (Build.VERSION.SDK_INT >= 28) powerManager.locationPowerSaveMode else 0,
    )
}

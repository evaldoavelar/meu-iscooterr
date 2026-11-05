package br.dev.evaldo.meuscooter

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import android.os.SystemClock
import br.dev.evaldo.meuscooter.domain.GpsSpeedSample
import br.dev.evaldo.meuscooter.domain.GpsSpeedSource
import br.dev.evaldo.meuscooter.domain.gpsSampleOf
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf

/**
 * Velocidade do GPS via `LocationManager` (fonte fina, SEM teste JVM: depende do framework Android; a conversão está em
 * `gpsSampleOf`, testada). Só assina o GPS com a permissão concedida ([permissionGranted]); nunca pede permissão.
 * Carimba com `SystemClock.elapsedRealtime()`, o mesmo relógio da telemetria. Só para exibição/viagem.
 */
class AndroidGpsSpeedSource(private val context: Context, private val permissionGranted: StateFlow<Boolean>) : GpsSpeedSource {
    @SuppressLint("MissingPermission")
    override val samples: Flow<GpsSpeedSample?> = permissionGranted.flatMapLatest<Boolean, GpsSpeedSample?> { granted ->
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        if (!granted || lm == null ||
            context.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED ||
            !lm.allProviders.contains(LocationManager.GPS_PROVIDER)
        ) flowOf(null)
        else callbackFlow<GpsSpeedSample?> {
            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    trySend(gpsSampleOf(location.hasSpeed(), location.speed, SystemClock.elapsedRealtime()))
                }
                @Deprecated("API < 29") override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) = Unit
                override fun onProviderEnabled(provider: String) = Unit
                override fun onProviderDisabled(provider: String) { trySend(null) }
            }
            trySend(null)
            try {
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1_000L, 0f, listener, Looper.getMainLooper())
            } catch (e: RuntimeException) {   // inclui SecurityException (permissão revogada) e provedor indisponível
                trySend(null)
            }
            awaitClose { runCatching { lm.removeUpdates(listener) } }
        }
    }.catch { emit(null) }   // GPS é opcional: qualquer falha vira "sem GPS", nunca derruba o app
}

package br.dev.evaldo.meuscooter

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import android.os.SystemClock
import br.dev.evaldo.meuscooter.domain.GpsFix
import br.dev.evaldo.meuscooter.domain.GpsFixSource
import br.dev.evaldo.meuscooter.domain.gpsFixOf
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf

/** Posição do GPS via `LocationManager` (fonte fina, sem teste JVM; a conversão é `gpsFixOf`). Mesmo contrato de permissão de [AndroidGpsSpeedSource]. Dado sensível: ADR 0012. */
class AndroidGpsFixSource(private val context: Context, private val permissionGranted: StateFlow<Boolean>) : GpsFixSource {
    @SuppressLint("MissingPermission")
    override val fixes: Flow<GpsFix?> = permissionGranted.flatMapLatest<Boolean, GpsFix?> { granted ->
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        if (!granted || lm == null ||
            context.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED ||
            !lm.allProviders.contains(LocationManager.GPS_PROVIDER)
        ) flowOf(null)
        else callbackFlow<GpsFix?> {
            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    trySend(gpsFixOf(location.hasAccuracy(), location.accuracy, location.latitude, location.longitude, SystemClock.elapsedRealtime()))
                }
                @Deprecated("API < 29") override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) = Unit
                override fun onProviderEnabled(provider: String) = Unit
                override fun onProviderDisabled(provider: String) { trySend(null) }
            }
            trySend(null)
            try {
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 2_000L, 0f, listener, Looper.getMainLooper())
            } catch (e: RuntimeException) { trySend(null) }
            awaitClose { runCatching { lm.removeUpdates(listener) } }
        }
    }.catch { emit(null) }
}

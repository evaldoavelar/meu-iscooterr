package br.dev.evaldo.meuscooter

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import br.dev.evaldo.meuscooter.domain.GeoPoint
import br.dev.evaldo.meuscooter.domain.LastKnownLocation
import br.dev.evaldo.meuscooter.domain.LastKnownLocationSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Última posição conhecida via `LocationManager.getLastKnownLocation` (só em memória; ADR 0012, adendo S45).
 * Usa só a permissão FINE JÁ concedida (nunca pede); sem ela, sem provedor ou sem posição devolve `null`. Não grava nem registra a posição.
 */
class AndroidLastKnownLocationSource(private val context: Context) : LastKnownLocationSource {
    @SuppressLint("MissingPermission")
    override suspend fun lastKnown(): LastKnownLocation? = withContext(Dispatchers.IO) {
        if (context.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return@withContext null
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return@withContext null
        try {
            lm.allProviders
                .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
                .maxByOrNull { it.time }
                ?.let { LastKnownLocation(GeoPoint(it.latitude, it.longitude)) }
        } catch (e: RuntimeException) { null }
    }
}

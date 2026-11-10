package br.dev.evaldo.meuscooter.domain

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlinx.coroutines.flow.Flow

/** Posição do GPS do celular. [atMs] usa o relógio monotônico da telemetria. Dado de localização SENSÍVEL (ADR 0012). */
data class GpsFix(val lat: Double, val lon: Double, val accuracyM: Float, val atMs: Long) {
    // toString sem coordenadas: evita vazar posição em log/exceção por acidente.
    override fun toString() = "GpsFix(accuracyM=$accuracyM, atMs=$atMs)"
}

/** Fonte de posições; `null` = sem GPS/permissão. */
interface GpsFixSource {
    val fixes: Flow<GpsFix?>

    companion object {
        val None: GpsFixSource = object : GpsFixSource { override val fixes = kotlinx.coroutines.flow.flowOf<GpsFix?>(null) }
    }
}

/** Converte uma leitura de GPS em [GpsFix]; coordenada ou precisão inválida → `null`. Lógica pura, fora da classe Android. */
fun gpsFixOf(hasAccuracy: Boolean, accuracyM: Float, lat: Double, lon: Double, atMs: Long): GpsFix? =
    if (hasAccuracy && accuracyM.isFinite() && accuracyM >= 0f && isValidCoordinate(lat, lon))
        GpsFix(lat, lon, accuracyM, atMs) else null

/** Distância em metros (haversine). */
fun distanceMeters(a: GeoPoint, b: GeoPoint): Double {
    val r = 6_371_000.0
    val dLat = Math.toRadians(b.lat - a.lat)
    val dLon = Math.toRadians(b.lon - a.lon)
    val h = sin(dLat / 2).pow(2) + cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLon / 2).pow(2)
    return 2 * r * asin(sqrt(h))
}

/** Pré-condições da gravação, lidas a cada amostra (lambdas: o recorder não conhece Preferences nem ViewModel). */
data class RouteGate(val saveRoute: Boolean, val permissionGranted: Boolean, val tripActive: Boolean) {
    val open get() = saveRoute && permissionGranted && tripActive
}

/**
 * Grava o trajeto de UMA viagem. Só aceita ponto com [RouteGate.open]; descarta precisão pior que [maxAccuracyM] e pontos a
 * menos de [minDistanceM] do último gravado; para ao atingir [maxPoints]. Com o portão fechado nada é guardado
 * (e o que já havia permanece até [reset]). Não é thread-safe: usar de uma só corrotina.
 */
class RouteRecorder(
    private val maxAccuracyM: Float = 30f,
    private val minDistanceM: Double = 10.0,
    private val maxPoints: Int = 2_000,
) {
    private val buf = ArrayList<GeoPoint>()

    /** Cópia imutável dos pontos gravados. */
    val points: List<GeoPoint> get() = buf.toList()

    /** @return `true` se o ponto foi gravado. */
    fun offer(fix: GpsFix?, gate: RouteGate): Boolean {
        if (fix == null || !gate.open || buf.size >= maxPoints) return false
        // Validação própria: GpsFix pode ser construído sem passar por gpsFixOf (NaN compara falso e passaria no filtro).
        if (!fix.accuracyM.isFinite() || fix.accuracyM < 0f || fix.accuracyM > maxAccuracyM || !isValidCoordinate(fix.lat, fix.lon)) return false
        val p = GeoPoint(fix.lat, fix.lon)
        val last = buf.lastOrNull()
        if (last != null && distanceMeters(last, p) < minDistanceM) return false
        buf.add(p)
        return true
    }

    /** Nova viagem: descarta os pontos. */
    fun reset() { buf.clear() }
}

/** Armazenamento local dos trajetos por viagem (só neste celular). */
interface RouteStore {
    fun write(tripId: Long, points: List<GeoPoint>)
    /** Vazio se não há trajeto ou o arquivo está corrompido. */
    fun read(tripId: Long): List<GeoPoint>
    fun delete(tripId: Long)
    /** Chamado por "Apagar histórico". */
    fun clear()
}

class InMemoryRouteStore : RouteStore {
    private val m = java.util.concurrent.ConcurrentHashMap<Long, List<GeoPoint>>()
    override fun write(tripId: Long, points: List<GeoPoint>) { if (points.isEmpty()) m.remove(tripId) else m[tripId] = points.toList() }
    override fun read(tripId: Long) = m[tripId].orEmpty()
    override fun delete(tripId: Long) { m.remove(tripId) }
    override fun clear() { m.clear() }
}

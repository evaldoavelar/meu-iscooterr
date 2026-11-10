package br.dev.evaldo.meuscooter.data

import br.dev.evaldo.meuscooter.domain.GeoPoint
import br.dev.evaldo.meuscooter.domain.RouteStore
import br.dev.evaldo.meuscooter.domain.isValidCoordinate
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException

/**
 * Trajetos em disco privado do app (`<dir>/<tripId>.route`): int (contagem) + pares de double (lat, lon). Nunca vai a log,
 * IX7TX nem exportação (ADR 0012). Arquivo truncado/corrompido lê como vazio.
 */
class FileRouteStore(private val dir: File, private val maxPoints: Int = 10_000) : RouteStore {
    init {
        dir.mkdirs()
        dir.listFiles { f -> f.name.endsWith(".tmp") }.orEmpty().forEach { it.delete() }
    }

    private fun file(id: Long) = File(dir, "$id.route")

    @Synchronized override fun write(tripId: Long, points: List<GeoPoint>) {
        if (points.isEmpty()) { delete(tripId); return }
        val tmp = File(dir, "$tripId.tmp")
        DataOutputStream(tmp.outputStream().buffered()).use { o ->
            o.writeInt(points.size)
            points.forEach { o.writeDouble(it.lat); o.writeDouble(it.lon) }
        }
        if (!tmp.renameTo(file(tripId))) { tmp.delete(); throw IOException("não foi possível gravar o trajeto") }
    }

    override fun read(tripId: Long): List<GeoPoint> = try {
        DataInputStream(file(tripId).inputStream().buffered()).use { i ->
            val n = i.readInt()
            if (n !in 0..maxPoints) emptyList() else {
                val pts = List(n) { GeoPoint(i.readDouble(), i.readDouble()) }
                if (pts.all { isValidCoordinate(it.lat, it.lon) }) pts else emptyList()
            }
        }
    } catch (_: IOException) { emptyList() }

    @Synchronized override fun delete(tripId: Long) { file(tripId).delete() }

    @Synchronized override fun clear() { dir.listFiles { f -> f.name.endsWith(".route") || f.name.endsWith(".tmp") }.orEmpty().forEach { it.delete() } }

    /** Apaga só `<id>.route` com id fora de [tripIds]; tolerante a falha de E/S (nunca lança). */
    @Synchronized override fun retainOnly(tripIds: Set<Long>) {
        try {
            dir.listFiles { f -> f.name.endsWith(".route") }.orEmpty().forEach { f ->
                val id = f.name.removeSuffix(".route").toLongOrNull()
                if (id == null || id !in tripIds) try { f.delete() } catch (_: SecurityException) { }
            }
        } catch (_: SecurityException) { }
    }
}

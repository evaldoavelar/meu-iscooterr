package br.dev.evaldo.meuscooter.data

import br.dev.evaldo.meuscooter.domain.GeoPoint
import br.dev.evaldo.meuscooter.domain.RouteStore
import br.dev.evaldo.meuscooter.domain.isValidCoordinate
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException

/**
 * Trajetos em disco privado do app (`<dir>/<tripId>.route`). V2: marcador + versão + contagem (int),
 * seguido de lat/lon (double), presença (boolean) e altitude opcional (double, metros) por ponto.
 * V1 legado: contagem + pares lat/lon, lido sem altitude; regravação migra para V2, sem alterar o original na leitura.
 * Nunca vai a log, IX7TX nem exportação (ADR 0012). Arquivo truncado/corrompido ou versão desconhecida lê vazio.
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
            o.writeInt(MAGIC)
            o.writeInt(VERSION)
            o.writeInt(points.size)
            points.forEach {
                o.writeDouble(it.lat); o.writeDouble(it.lon)
                val altitude = it.altitudeM?.takeIf { m -> m.isFinite() }
                o.writeBoolean(altitude != null)
                if (altitude != null) o.writeDouble(altitude)
            }
        }
        if (!tmp.renameTo(file(tripId))) { tmp.delete(); throw IOException("não foi possível gravar o trajeto") }
    }

    override fun read(tripId: Long): List<GeoPoint> {
        return try {
            DataInputStream(file(tripId).inputStream().buffered()).use { i ->
                val header = i.readInt()
                val versioned = header == MAGIC
                if (versioned && i.readInt() != VERSION) return emptyList()
                val n = if (versioned) i.readInt() else header
                if (n !in 0..maxPoints) emptyList() else {
                    val pts = List(n) {
                        val lat = i.readDouble(); val lon = i.readDouble()
                        val altitude = if (versioned) when (i.readUnsignedByte()) {
                            0 -> null
                            1 -> i.readDouble()
                            else -> return emptyList()
                        } else null
                        GeoPoint(lat, lon, altitude)
                    }
                    if (i.read() == -1 && pts.all { isValidCoordinate(it.lat, it.lon) && it.altitudeM?.isFinite() != false }) pts else emptyList()
                }
            }
        } catch (_: IOException) { emptyList() }
    }

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

    private companion object {
        // Negativo: nunca se confunde com uma contagem válida do formato antigo.
        const val MAGIC = -0x495837
        const val VERSION = 2
    }
}

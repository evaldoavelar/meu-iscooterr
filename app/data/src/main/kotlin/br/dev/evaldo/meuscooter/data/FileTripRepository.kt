package br.dev.evaldo.meuscooter.data

import br.dev.evaldo.meuscooter.domain.Trip
import br.dev.evaldo.meuscooter.domain.TripRepository
import java.io.File
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Histórico de viagens em um arquivo de texto privado (ADR 0014): cabeçalho `TRIPS1` e uma linha por viagem,
 * `id|startedAtMs|distanceKm|durationMs|avgKmh|maxKmh|avgWhPerKm|batteryStart|batteryEnd` (`-` = nulo).
 * NUNCA grava [Trip.route] (coordenada é dado sensível, ADR 0012). Escrita atômica (tmp + rename); arquivo ausente,
 * corrompido ou linha inválida/não finita/id repetido é ignorado (nunca lança); falha de escrita não derruba o app
 * (o estado em memória segue valendo na sessão). [save] de viagem com valor não finito não grava e devolve 0.
 */
class FileTripRepository(private val file: File) : TripRepository {
    private val mutex = Mutex()
    private val all = MutableStateFlow(load())
    private var nextId = (all.value.maxOfOrNull { it.id } ?: 0L) + 1

    override val trips: Flow<List<Trip>> = all.map { list -> list.sortedByDescending { it.startedAtMs } }

    override suspend fun save(trip: Trip): Long = mutex.withLock {
        if (!trip.isFinite()) return 0L
        val saved = (if (trip.id <= 0L) trip.copy(id = nextId) else trip).copy(route = emptyList())
        if (saved.id >= nextId) nextId = saved.id + 1
        all.value = all.value.filterNot { it.id == saved.id } + saved
        persist()
        saved.id
    }

    override suspend fun get(id: Long): Trip? = all.value.firstOrNull { it.id == id }

    override suspend fun clear(): Unit = mutex.withLock {
        all.value = emptyList()
        nextId = 1 // igual ao que reabrir o arquivo vazio produziria
        try { file.delete(); File(file.path + ".tmp").delete() } catch (_: SecurityException) { }
    }

    private fun persist() {
        try {
            file.absoluteFile.parentFile?.mkdirs()
            val tmp = File(file.path + ".tmp")
            tmp.writeText(buildString {
                append(HEADER).append('\n')
                all.value.forEach { append(encode(it)).append('\n') }
            })
            if (!tmp.renameTo(file)) { tmp.delete(); throw IOException("rename") }
        } catch (_: IOException) {
            // sem espaço/permissão: mantém só em memória nesta sessão
        } catch (_: SecurityException) { }
    }

    private fun load(): List<Trip> = try {
        val lines = file.readLines()
        if (lines.firstOrNull() != HEADER) emptyList()
        else lines.drop(1).mapNotNull(::decode).distinctBy { it.id }
    } catch (_: Exception) { emptyList() }

    private companion object {
        const val HEADER = "TRIPS1"

        fun Trip.isFinite() = distanceKm.isFinite() && avgKmh.isFinite() && maxKmh.isFinite() && (avgWhPerKm?.isFinite() ?: true)

        fun <T> T?.orDash() = this?.toString() ?: "-"

        fun encode(t: Trip) = listOf(
            t.id, t.startedAtMs, t.distanceKm, t.durationMs, t.avgKmh, t.maxKmh, t.avgWhPerKm.orDash(),
            t.batteryStartPercent.orDash(), t.batteryEndPercent.orDash(),
        ).joinToString("|")

        fun decode(line: String): Trip? = try {
            val p = line.split('|')
            if (p.size != 9) null else {
                val t = Trip(
                    id = p[0].toLong(), startedAtMs = p[1].toLong(), distanceKm = p[2].toFloat(), durationMs = p[3].toLong(),
                    avgKmh = p[4].toFloat(), maxKmh = p[5].toFloat(),
                    avgWhPerKm = p[6].takeIf { it != "-" }?.toFloat(),
                    batteryStartPercent = p[7].takeIf { it != "-" }?.toInt(),
                    batteryEndPercent = p[8].takeIf { it != "-" }?.toInt(),
                )
                if (t.id > 0 && t.isFinite()) t else null
            }
        } catch (_: NumberFormatException) { null }
    }
}

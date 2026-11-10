package br.dev.evaldo.meuscooter.data

import br.dev.evaldo.meuscooter.domain.Trip
import br.dev.evaldo.meuscooter.domain.TripRepository
import java.io.File
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Histórico de viagens em um arquivo de texto privado (ADR 0014): cabeçalho `TRIPS1|<próximo id>` (contador monotônico, sobrevive a `clear`) e uma linha por viagem,
 * `id|startedAtMs|distanceKm|durationMs|avgKmh|maxKmh|avgWhPerKm|batteryStart|batteryEnd` (`-` = nulo).
 * NUNCA grava [Trip.route] (coordenada é dado sensível, ADR 0012). Escrita atômica (tmp + rename); arquivo ausente,
 * corrompido ou linha inválida/não finita/id repetido é ignorado (nunca lança); falha de escrita não derruba o app
 * (o estado em memória segue valendo na sessão). [save] de viagem com valor não finito não grava e devolve 0.
 */
class FileTripRepository(private val file: File) : TripRepository {
    private val mutex = Mutex()
    /** Leitura do arquivo adiada até o primeiro uso (que vem do dispatcher de E/S), nunca na composição da UI. */
    private val loaded by lazy {
        try { File(file.path + ".tmp").delete() } catch (_: SecurityException) { }
        load()
    }
    private val all by lazy { MutableStateFlow(loaded.first) }
    private var nextId = 0L
        get() = maxOf(field, loaded.second, (all.value.maxOfOrNull { it.id } ?: 0L) + 1)

    override val trips: Flow<List<Trip>> = flow { emitAll(all.map { list -> list.sortedByDescending { it.startedAtMs } }) }

    override suspend fun save(trip: Trip): Long = mutex.withLock {
        if (!trip.isValid()) return 0L
        val saved = (if (trip.id <= 0L) trip.copy(id = nextId) else trip).copy(route = emptyList())
        if (saved.id >= nextId) nextId = saved.id + 1
        all.value = all.value.filterNot { it.id == saved.id } + saved
        persist()
        saved.id
    }

    override suspend fun get(id: Long): Trip? = all.value.firstOrNull { it.id == id }

    override suspend fun delete(id: Long): Unit = mutex.withLock {
        all.value = all.value.filterNot { it.id == id }
        persist()
    }

    override suspend fun clear(): Unit = mutex.withLock {
        all.value = emptyList() // o contador de ids NÃO volta: um `.route` órfão nunca casa com viagem nova
        persist()
    }

    private fun persist() {
        try {
            file.absoluteFile.parentFile?.mkdirs()
            val tmp = File(file.path + ".tmp")
            tmp.writeText(buildString {
                append(HEADER).append('|').append(nextId).append('\n')
                all.value.forEach { append(encode(it)).append('\n') }
            })
            if (!tmp.renameTo(file)) { tmp.delete(); throw IOException("rename") }
        } catch (_: IOException) {
            // sem espaço/permissão: mantém só em memória nesta sessão
        } catch (_: SecurityException) { }
    }

    private fun load(): Pair<List<Trip>, Long> = try {
        val lines = file.readLines()
        val head = lines.firstOrNull()?.split('|')
        // `TRIPS1` legado (sem contador de id) também lê; o próximo id sai do maior id existente (ver `nextId`).
        if (head == null || head.size !in 1..2 || head[0] != HEADER) emptyList<Trip>() to 1L
        else lines.drop(1).mapNotNull(::decode).distinctBy { it.id } to (head.getOrNull(1)?.toLongOrNull() ?: 1L)
    } catch (_: Exception) { emptyList<Trip>() to 1L }

    private companion object {
        const val HEADER = "TRIPS1"

        fun Trip.isValid() = startedAtMs >= 0 && durationMs >= 0 && (batteryStartPercent ?: 0) in 0..100 && (batteryEndPercent ?: 0) in 0..100 && distanceKm.isFinite() && avgKmh.isFinite() && maxKmh.isFinite() && (avgWhPerKm?.isFinite() ?: true)

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
                if (t.id > 0 && t.isValid()) t else null
            }
        } catch (_: NumberFormatException) { null }
    }
}

package br.dev.evaldo.meuscooter.data

import br.dev.evaldo.meuscooter.protocol.Clock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withTimeoutOrNull

/** Um anúncio BLE recebido. Só varredura: nenhuma conexão, nenhuma escrita. */
data class ScanResult(val name: String?, val address: String, val rssi: Int)

enum class ScanError { PermissionDenied, Unavailable }

class ScanException(val error: ScanError) : Exception(error.name)

/** Fronteira da varredura. Fluxo frio: coletar inicia o scan, cancelar a coleta o interrompe. */
interface ScooterScanner {
    fun scan(): Flow<ScanResult>
}

/**
 * Filtro por nome. O nome anunciado pelo patinete é `[DESCONHECIDO]` (nenhuma captura/estático o registra; ver
 * docs/RESUME.md e test-plan H9): por padrão não há prefixo conhecido, então nenhum dispositivo é "reconhecido" e o
 * proprietário escolhe na lista. Depois da 4.1 real, preencher [prefixes] com o nome observado.
 */
data class NameFilter(val prefixes: List<String> = emptyList())

data class ScanCandidate(val name: String, val address: String, val rssi: Int, val recognized: Boolean)

data class DiscoveryOutcome(
    val candidates: List<ScanCandidate>,
    /** ms desde o início da varredura até o 1º candidato (alimenta `ConnectionTimings.scanMs`). */
    val timeToFirstCandidateMs: Long?,
    val error: ScanError? = null,
) {
    val none get() = candidates.isEmpty()

    /** Só dispensa a escolha quando há exatamente um candidato E ele foi reconhecido pelo nome. */
    val needsOwnerChoice get() = !(candidates.size == 1 && candidates[0].recognized)
}

object ScooterDiscovery {
    /** Junta anúncios por endereço (vale o último RSSI; um anúncio sem nome não apaga o nome), filtra e ordena pelo sinal mais forte. */
    fun candidates(results: List<ScanResult>, filter: NameFilter): List<ScanCandidate> {
        val byAddress = LinkedHashMap<String, ScanResult>()
        for (r in results) {
            val previous = byAddress[r.address]
            val name = r.name?.takeIf { it.isNotBlank() } ?: previous?.name
            byAddress[r.address] = r.copy(name = name)
        }
        return byAddress.values
            .filter { !it.name.isNullOrBlank() }
            .filter { filter.prefixes.isEmpty() || filter.prefixes.any { p -> it.name!!.startsWith(p, ignoreCase = true) } }
            .map { ScanCandidate(it.name!!, it.address, it.rssi, recognized = filter.prefixes.isNotEmpty()) }
            .sortedByDescending { it.rssi }
    }

    /** Varre por [windowMs] e devolve os candidatos. Sempre interrompe o scan ao terminar. Não conecta. */
    suspend fun discover(scanner: ScooterScanner, filter: NameFilter, clock: Clock, windowMs: Long): DiscoveryOutcome {
        val start = clock.nowMs()
        val seen = mutableListOf<ScanResult>()
        var firstAt: Long? = null
        var error: ScanError? = null
        try {
            withTimeoutOrNull(windowMs) {
                scanner.scan().collect {
                    seen += it
                    if (firstAt == null && candidates(listOf(it), filter).isNotEmpty()) firstAt = clock.nowMs() - start
                }
            }
        } catch (e: ScanException) {
            error = e.error
        }
        return DiscoveryOutcome(candidates(seen, filter), firstAt, error)
    }
}

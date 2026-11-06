package br.dev.evaldo.meuscooter.data

import br.dev.evaldo.meuscooter.domain.FoundScooter
import br.dev.evaldo.meuscooter.domain.ScooterFinder
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * [ScooterFinder] sobre [ScooterScanner]: varre em janelas de [windowMs] e para no 1º anúncio cujo nome casa com o filtro
 * (junta anúncios do mesmo endereço, pois o nome pode vir só na resposta de scan). Janela vazia ou erro (ex.: permissão
 * ainda não concedida) → pausa de [retryDelayMs] e nova janela (o Android limita o reinício de varreduras). Só varredura.
 * Se houver 2+ patinetes com o nome, vale o primeiro visto; o proprietário confere o nome antes de confirmar.
 */
class DiscoveringScooterFinder(
    private val scanner: ScooterScanner,
    private val filter: NameFilter,
    private val windowMs: Long = 10_000,
    private val retryDelayMs: Long = 3_000,
) : ScooterFinder {
    init { require(filter.prefixes.isNotEmpty()) { "o filtro de nome é obrigatório (nunca oferecer qualquer dispositivo)" } }

    override suspend fun find(): FoundScooter {
        while (true) {
            val seen = mutableListOf<ScanResult>()
            var found: ScanCandidate? = null
            try {
                withTimeoutOrNull(windowMs) {
                    scanner.scan().first { seen += it; found = ScooterDiscovery.candidates(seen, filter).firstOrNull(); found != null }
                }
            } catch (_: ScanException) {
                // sem permissão/Bluetooth: tenta de novo depois da pausa
            }
            found?.let { return FoundScooter(it.name, it.address) }
            delay(retryDelayMs)
        }
    }
}

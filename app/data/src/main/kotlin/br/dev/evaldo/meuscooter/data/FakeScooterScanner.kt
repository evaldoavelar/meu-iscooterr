package br.dev.evaldo.meuscooter.data

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** Scanner simulado (NENHUM BLE real): emite `(atraso, anúncio)` em sequência e depois continua "varrendo" até ser cancelado, como o real. */
class FakeScooterScanner(
    private val results: List<Pair<Long, ScanResult>>,
    private val failWith: ScanException? = null,
) : ScooterScanner {
    var starts = 0
        private set
    var stops = 0
        private set

    override fun scan(): Flow<ScanResult> = flow {
        starts++
        try {
            failWith?.let { throw it }
            for ((wait, result) in results) {
                delay(wait)
                emit(result)
            }
            awaitCancellation()
        } finally {
            stops++
        }
    }
}

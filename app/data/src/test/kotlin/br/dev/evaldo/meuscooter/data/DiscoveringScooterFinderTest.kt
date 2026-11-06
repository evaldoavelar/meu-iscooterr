package br.dev.evaldo.meuscooter.data

import br.dev.evaldo.meuscooter.domain.FoundScooter
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** F5.2c: achar o patinete por nome (prefixo "ix7", S17: anúncio `ix7Pro`). Só varredura. */
@OptIn(ExperimentalCoroutinesApi::class)
class DiscoveringScooterFinderTest {
    private val mac = "AA:BB:CC:DD:EE:FF"
    private fun r(name: String?, addr: String, rssi: Int = -60) = ScanResult(name, addr, rssi)

    /** Uma lista de anúncios por varredura (a N-ésima chamada de scan usa a N-ésima lista; a última se repete). */
    private class Scripted(private val rounds: List<List<ScanResult>>, private val errorRounds: Set<Int> = emptySet()) : ScooterScanner {
        var starts = 0; private set
        var stops = 0; private set
        override fun scan(): Flow<ScanResult> = flow {
            val round = starts++
            try {
                if (round in errorRounds) throw ScanException(ScanError.PermissionDenied)
                for (x in rounds[minOf(round, rounds.lastIndex)]) emit(x)
                awaitCancellation()
            } finally { stops++ }
        }
    }

    private fun finder(s: ScooterScanner, scope: kotlinx.coroutines.test.TestScope) =
        DiscoveringScooterFinder(s, NameFilter(listOf("ix7")), windowMs = 10_000, retryDelayMs = 3_000)

    @Test fun `acha o ix7 entre outros dispositivos e interrompe a varredura`() = runTest {
        val s = Scripted(listOf(listOf(r("Headphones", "11:11:11:11:11:11"), r(null, "22:22:22:22:22:22"), r("ix7Pro", mac, -71))))
        assertEquals(FoundScooter("ix7Pro", mac), finder(s, this).find())
        assertEquals(1, s.starts); assertEquals(1, s.stops)
    }

    @Test fun `nome vem de anuncio posterior do mesmo endereco`() = runTest {
        val s = Scripted(listOf(listOf(r(null, mac), r("ix7Pro", mac))))
        assertEquals(mac, finder(s, this).find().address)
    }

    @Test fun `janela sem ix7 reinicia a varredura apos a pausa e acha depois`() = runTest {
        val s = Scripted(listOf(listOf(r("Mouse", "11:11:11:11:11:11")), listOf(r("ix7Pro", mac))))
        val job = async { finder(s, this@runTest).find() }
        advanceTimeBy(9_999); runCurrent(); assertEquals(1, s.starts); assertFalse(job.isCompleted)
        advanceTimeBy(1); runCurrent(); assertEquals(1, s.stops)           // janela acabou, scan parado
        advanceTimeBy(2_999); runCurrent(); assertEquals(1, s.starts)      // pausa
        advanceTimeBy(1); runCurrent()
        assertEquals(mac, job.await().address); assertEquals(2, s.starts); assertEquals(2, s.stops)
    }

    @Test fun `erro de varredura tenta de novo apos a pausa`() = runTest {
        val s = Scripted(listOf(emptyList(), listOf(r("ix7Pro", mac))), errorRounds = setOf(0))
        val job = async { finder(s, this@runTest).find() }
        runCurrent(); assertEquals(1, s.starts)
        advanceTimeBy(2_999); runCurrent(); assertEquals(1, s.starts)
        advanceTimeBy(1); runCurrent()
        assertEquals(mac, job.await().address); assertEquals(3_000, currentTime)
    }

    @Test fun `cancelar interrompe a varredura`() = runTest {
        val s = Scripted(listOf(emptyList()))
        val job = async(start = CoroutineStart.UNDISPATCHED) { finder(s, this@runTest).find() }
        runCurrent(); assertEquals(1, s.starts); assertEquals(0, s.stops)
        job.cancel(); runCurrent()
        assertEquals(1, s.stops); assertTrue(job.isCancelled)
    }

    @Test(expected = IllegalArgumentException::class) fun `exige prefixo de nome`() {
        DiscoveringScooterFinder(Scripted(listOf(emptyList())), NameFilter(), windowMs = 1, retryDelayMs = 1)
    }
}

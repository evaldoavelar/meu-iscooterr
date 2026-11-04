package br.dev.evaldo.meuscooter.data

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regra pura do passo MTU do `AndroidGattClient` (A1 da revisão r1). Tempo virtual; valores escritos à mão. */
@OptIn(ExperimentalCoroutinesApi::class)
class MtuNegotiatorTest {
    @Test fun callbackAfterTheRequestIsTheResult() = runTest {
        val n = MtuNegotiator(graceMs = 1_500)
        val result = n.request(start = { n.onChanged(247, success = true); true })
        assertEquals(247, result)
    }

    @Test fun spontaneousChangeBeforeTheRequestIsRememberedAndUsedWhenNoCallbackFollows() = runTest {
        val n = MtuNegotiator(graceMs = 1_500)
        n.onChanged(517, success = true) // Android negociou sozinho; requestMtu(247) não gera novo callback
        val result = n.request(start = { true })
        assertEquals(517, result)
        assertEquals(1_500L, currentTime)
    }

    @Test fun smallObservedMtuIsNotEnoughSoItKeepsWaitingForTheCallback() = runTest {
        val n = MtuNegotiator(graceMs = 1_500)
        n.onChanged(23, success = true) // 23 < 30: não cabe o quadro de 27 B
        val late = launch { delay(4_000); n.onChanged(247, success = true) }
        val result = n.request(start = { true })
        late.join()
        assertEquals(247, result)
        assertEquals(4_000L, currentTime)
    }

    @Test fun refusedCallbackFailsWithMtuFailed() = runTest {
        val n = MtuNegotiator(graceMs = 1_500)
        val e = runCatching { n.request(start = { n.onChanged(23, success = false); true }) }.exceptionOrNull() as GattException
        assertEquals(GattError.MtuFailed, e.error)
    }

    @Test fun stackRefusingTheRequestFailsImmediately() = runTest {
        val e = runCatching { MtuNegotiator().request(start = { false }) }.exceptionOrNull() as GattException
        assertEquals(GattError.MtuFailed, e.error)
        assertEquals(0L, currentTime)
    }

    @Test fun resetForgetsTheMtuOfThePreviousSession() = runTest {
        val n = MtuNegotiator(graceMs = 1_500)
        n.onChanged(517, success = true)
        n.reset()
        val late = launch { delay(4_000); n.onChanged(247, success = true) }
        assertEquals(247, n.request(start = { true }))
        late.join()
    }

    @Test fun spontaneousChangeWhileNobodyWaitsDoesNotThrow() {
        MtuNegotiator().onChanged(517, success = true)
        MtuNegotiator().onChanged(23, success = false)
    }

    @Test fun closingTheLinkFailsAPendingRequestWithDisconnected() = runTest {
        val n = MtuNegotiator(graceMs = 1_500)
        val pending = async { runCatching { n.request(start = { true }) } }
        runCurrent()
        n.onClosed()
        runCurrent()
        val done = pending.isCompleted
        if (!done) pending.cancel()
        assertTrue("o pedido pendente deve falhar ao fechar", done)
        assertEquals(GattError.Disconnected, (pending.await().exceptionOrNull() as GattException).error)
    }
}

package br.dev.evaldo.meuscooter.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class) // getCompletionExceptionOrNull
/** Regra pura do `AndroidGattClient.write`: cada `onCharacteristicWrite` pertence a UMA escrita identificada. */
class WriteSlotTest {
    private fun assertBusy(slot: WriteSlot) {
        try { slot.begin(); fail("deveria recusar") } catch (e: GattException) { assertEquals(GattError.WriteFailed, e.error) }
    }

    @Test fun aCallbackCompletesTheWriteInFlight() {
        val slot = WriteSlot()
        val op = slot.begin()
        slot.started(op)
        assertTrue(slot.onCallback(null))
        assertTrue(op.result.isCompleted)
        slot.end(op)
        val next = slot.begin()
        assertNotEquals(op.id, next.id)
    }

    @Test fun aFailedCallbackFailsTheWriteTyped() {
        val slot = WriteSlot()
        val op = slot.begin()
        slot.started(op)
        slot.onCallback(GattException(GattError.WriteFailed, "status=133"))
        assertEquals(GattError.WriteFailed, (op.result.getCompletionExceptionOrNull() as GattException).error)
    }

    @Test fun aLateCallbackOfAnAbandonedWriteNeverCompletesTheNextOne() {
        val slot = WriteSlot()
        val abandoned = slot.begin()
        slot.started(abandoned)
        slot.end(abandoned) // timeout/cancelamento: o stack ainda deve o callback desta escrita
        assertBusy(slot) // não inicia outra escrita no mesmo GATT enquanto a anterior está indeterminada
        assertFalse(slot.onCallback(null)) // o callback tardio é consumido pela abandonada…
        assertFalse(abandoned.result.isCompleted)
        val next = slot.begin() // …e só então outra escrita pode começar
        slot.started(next)
        assertFalse(next.result.isCompleted)
        assertTrue(slot.onCallback(null))
        assertTrue(next.result.isCompleted)
    }

    @Test fun aWriteRefusedByTheStackOwesNoCallback() {
        val slot = WriteSlot()
        val refused = slot.begin()
        slot.end(refused) // start() devolveu false: nunca virá callback
        val next = slot.begin()
        slot.started(next)
        assertTrue(slot.onCallback(null))
        assertTrue(next.result.isCompleted)
    }

    @Test fun anUnexpectedCallbackWithNothingPendingIsIgnored() {
        val slot = WriteSlot()
        assertFalse(slot.onCallback(null))
        val op = slot.begin()
        slot.started(op)
        assertFalse(op.result.isCompleted)
    }

    /**
     * Codex r4: a conclusão roda fora do lock, depois da transição. Se a espera termina (`end`) nesse intervalo, a
     * escrita já recebeu o callback: não fica "devendo" e a próxima escrita não é recusada.
     */
    @Test fun aCallbackTakenBeforeEndIsNotOwedEvenIfTheCompletionRunsLater() {
        val slot = WriteSlot()
        val op = slot.begin()
        slot.started(op)
        val completion = slot.takeCallback(null)!!
        slot.end(op)
        completion()
        assertTrue(op.result.isCompleted)
        val next = slot.begin()
        slot.started(next)
        assertTrue(slot.onCallback(null))
        assertTrue(next.result.isCompleted)
    }

    @Test fun closeFailsTheWriteInFlightAndTheClosedSlotRefusesEverything() {
        val slot = WriteSlot()
        val op = slot.begin()
        slot.started(op)
        slot.close()
        assertEquals(GattError.Disconnected, (op.result.getCompletionExceptionOrNull() as GattException).error)
        slot.end(op)
        try { slot.begin(); fail("slot fechado deveria recusar") } catch (e: GattException) { assertEquals(GattError.Disconnected, e.error) }
        assertFalse(slot.onCallback(null))
    }

    /**
     * Codex r2 [P2]: o callback do GATT A "passou pelo gate", a thread parou; `close()` + reconexão criou o GATT B com
     * uma escrita nova. Com um slot por sessão, o callback de A só alcança o slot de A (fechado) e nunca o de B.
     */
    @Test fun aLateCallbackOfAClosedSessionNeverCompletesTheNewSessionsWrite() {
        val sessionA = WriteSlot()
        val writeA = sessionA.begin()
        sessionA.started(writeA)
        val lateCallbackOfA = sessionA // capturado pelo callback de A antes de suspender (já "passou pelo gate")
        sessionA.close() // close() do GATT A
        sessionA.end(writeA)

        val sessionB = WriteSlot() // reconexão: GATT B com slot próprio
        val writeB = sessionB.begin()
        sessionB.started(writeB)

        assertFalse(lateCallbackOfA.onCallback(null)) // a thread de A retoma
        assertFalse(writeB.result.isCompleted)
        try { lateCallbackOfA.begin(); fail("slot de A fechado") } catch (e: GattException) { assertEquals(GattError.Disconnected, e.error) }

        assertTrue(sessionB.onCallback(null)) // só o callback de B conclui a escrita de B
        assertTrue(writeB.result.isCompleted)
    }
}

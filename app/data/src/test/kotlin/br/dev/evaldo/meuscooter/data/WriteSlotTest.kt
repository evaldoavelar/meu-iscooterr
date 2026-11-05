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

    @Test fun closeFailsTheWriteInFlightAndForgetsTheAbandonedOne() {
        val slot = WriteSlot()
        val op = slot.begin()
        slot.started(op)
        slot.close()
        assertEquals(GattError.Disconnected, (op.result.getCompletionExceptionOrNull() as GattException).error)
        slot.end(op) // depois do close: nada fica devido
        slot.begin()

        val slot2 = WriteSlot()
        val abandoned = slot2.begin()
        slot2.started(abandoned)
        slot2.end(abandoned)
        slot2.close() // novo GATT: o callback devido do anterior nunca chega (e seria barrado pelo CallbackGate)
        val fresh = slot2.begin()
        slot2.started(fresh)
        assertTrue(slot2.onCallback(null))
        assertTrue(fresh.result.isCompleted)
    }
}

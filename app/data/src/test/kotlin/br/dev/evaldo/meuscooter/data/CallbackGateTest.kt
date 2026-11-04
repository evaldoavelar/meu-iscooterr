package br.dev.evaldo.meuscooter.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CallbackGateTest {
    private class Session

    @Test fun acceptsOnlyTheAdoptedSession() {
        val gate = CallbackGate<Session>()
        val a = Session()
        val b = Session()
        gate.adopt(a)
        assertTrue(gate.accepts(a))
        assertFalse(gate.accepts(b))
    }

    @Test fun callbackBeforeAnythingIsAdoptedOrExpectedIsRejected() {
        assertFalse(CallbackGate<Session>().accepts(Session()))
    }

    @Test fun firstCallbackWhileExpectingConnectAdoptsItsSessionAndLaterAdoptIsConsistent() {
        // connectGatt ainda não retornou: o 1º callback chega antes de `gatt` ser atribuído.
        val gate = CallbackGate<Session>()
        val a = Session()
        val b = Session()
        gate.expectConnect()
        assertTrue(gate.accepts(a))
        assertTrue(gate.accepts(a))
        assertFalse(gate.accepts(b))
        gate.adopt(a) // o retorno de connectGatt: mesma sessão, nada muda
        assertTrue(gate.accepts(a))
    }

    @Test fun closedSessionIsRejectedAndCannotBeAdoptedByTheNextConnectWindow() {
        val gate = CallbackGate<Session>()
        val old = Session()
        val fresh = Session()
        gate.expectConnect()
        assertTrue(gate.accepts(old))
        gate.close()
        assertFalse(gate.accepts(old))
        gate.expectConnect()
        assertFalse(gate.accepts(old)) // callback tardio do GATT antigo NÃO vira a sessão nova
        assertTrue(gate.accepts(fresh))
        assertFalse(gate.accepts(old))
    }

    @Test fun closeWithoutAnySessionIsHarmless() {
        val gate = CallbackGate<Session>()
        gate.close()
        assertFalse(gate.accepts(Session()))
    }
}

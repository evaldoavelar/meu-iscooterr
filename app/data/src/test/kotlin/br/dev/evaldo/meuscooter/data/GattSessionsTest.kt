package br.dev.evaldo.meuscooter.data

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regra pura de posse das sessões do `AndroidGattClient` (Codex r3 P2): um callback tardio de uma sessão fechada não
 * conclui a operação pendente, o pedido de MTU nem o estado da sessão nova. Tempo virtual; valores escritos à mão.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GattSessionsTest {
    private fun error(r: Result<*>): GattError? = (r.exceptionOrNull() as? GattException)?.error

    /** Cenário (a): o `onDescriptorWrite(SUCCESS)` atrasado de A não conclui o CCCD que B aguarda. */
    @Test fun aLateOperationCallbackOfAClosedSessionNeverCompletesTheNewSessionsPending() = runTest {
        val sessions = GattSessions<String>()
        val a = sessions.open()
        sessions.close()
        val b = sessions.open()
        val cccd = async { runCatching { b.await(GattError.SubscribeFailed) { true } } }
        runCurrent()
        a.onOperationDone(null)
        runCurrent()
        assertFalse(cccd.isCompleted)
        b.onOperationDone(null)
        assertTrue(cccd.await().isSuccess)
    }

    /** Cenário (b): o `onMtuChanged(A, 247)` atrasado não conclui o pedido de MTU de B. */
    @Test fun aLateMtuCallbackOfAClosedSessionNeverCompletesTheNewSessionsRequest() = runTest {
        val sessions = GattSessions<String>()
        val a = sessions.open()
        sessions.close()
        val b = sessions.open()
        val mtu = async { runCatching { b.requestMtu { true } } }
        runCurrent()
        a.onMtuChanged(247, success = true)
        runCurrent()
        assertFalse(mtu.isCompleted)
        b.onMtuChanged(185, success = true)
        assertEquals(185, mtu.await().getOrThrow())
    }

    /** Cenário (c): o `DISCONNECTED` atrasado de A não publica `Disconnected` sobre B conectado (nem falha o que B espera). */
    @Test fun aLateDisconnectOfAClosedSessionNeverPublishesStateOverTheNewSession() = runTest {
        val sessions = GattSessions<String>()
        val a = sessions.open()
        sessions.close()
        val b = sessions.open()
        val connect = async { runCatching { b.await(GattError.ConnectFailed) { true } } }
        runCurrent()
        b.onConnected()
        assertTrue(connect.await().isSuccess)
        assertEquals(GattState.Connected, sessions.state.value)
        val cccd = async { runCatching { b.await(GattError.SubscribeFailed) { true } } }
        runCurrent()
        a.onDisconnected(GattError.Disconnected, "status=0")
        a.onConnected()
        runCurrent()
        assertEquals(GattState.Connected, sessions.state.value)
        assertFalse(cccd.isCompleted)
        b.onOperationDone(null)
        assertTrue(cccd.await().isSuccess)
    }

    @Test fun theCurrentSessionsCallbacksDriveItsOwnOperationsAndTheState() = runTest {
        val sessions = GattSessions<String>()
        val s = sessions.open()
        assertSame(s, sessions.current())
        val connect = async { runCatching { s.await(GattError.ConnectFailed) { true } } }
        runCurrent()
        s.onConnected()
        assertTrue(connect.await().isSuccess)
        assertEquals(GattState.Connected, sessions.state.value)

        val discover = async { runCatching { s.await(GattError.ServiceDiscoveryFailed) { true } } }
        runCurrent()
        s.onOperationDone(GattException(GattError.ServiceDiscoveryFailed, "status=129"))
        assertEquals(GattError.ServiceDiscoveryFailed, error(discover.await()))

        var emitted = 0
        assertEquals(1, s.ifCurrent { ++emitted })

        val mtu = async { runCatching { s.requestMtu { true } } }
        val cccd = async { runCatching { s.await(GattError.SubscribeFailed) { true } } }
        runCurrent()
        s.onDisconnected(GattError.Disconnected, "status=0") // queda do enlace: falha o que espera e publica
        assertEquals(GattState.Disconnected, sessions.state.value)
        assertEquals(GattError.Disconnected, error(mtu.await()))
        assertEquals(GattError.Disconnected, error(cccd.await()))
    }

    @Test fun closeIsFinalFailsEveryWaitOfTheSessionAndReturnsItForRelease() = runTest {
        val sessions = GattSessions<String>()
        val s = sessions.open()
        s.handle = "gatt-A"
        val pending = async { runCatching { s.await(GattError.SubscribeFailed) { true } } }
        val mtu = async { runCatching { s.requestMtu { true } } }
        val op = s.writes.begin()
        s.writes.started(op)
        runCurrent()
        s.onConnected() // conclui só o pending; o MTU e a escrita seguem em voo
        assertEquals(GattState.Connected, sessions.state.value)

        assertSame(s, sessions.close())
        assertEquals(GattState.Disconnected, sessions.state.value)
        assertNull(sessions.current())
        assertTrue(pending.await().isSuccess)
        assertEquals(GattError.Disconnected, error(mtu.await()))
        assertEquals(GattError.Disconnected, error(runCatching { op.result.await() }))

        // Fechada é final: nada novo começa nem é entregue.
        assertEquals(GattError.Disconnected, error(runCatching { s.await(GattError.SubscribeFailed) { true } }))
        assertEquals(GattError.Disconnected, error(runCatching { s.requestMtu { true } }))
        assertEquals(GattError.Disconnected, error(runCatching { s.writes.begin() }))
        assertNull(s.ifCurrent { 1 })
        s.onConnected()
        assertEquals(GattState.Disconnected, sessions.state.value)
        assertNull(sessions.close())
    }

    @Test fun closeDuringStartFailsTheOperationInsteadOfLeavingItWaiting() = runTest {
        val sessions = GattSessions<String>()
        val s = sessions.open()
        assertEquals(GattError.Disconnected, error(runCatching { s.await(GattError.SubscribeFailed) { sessions.close(); true } }))
        val t = sessions.open()
        assertEquals(GattError.Disconnected, error(runCatching { t.requestMtu { sessions.close(); true } }))
    }

    @Test fun openingANewSessionClosesThePreviousOne() = runTest {
        val sessions = GattSessions<String>()
        val a = sessions.open()
        val pending = async { runCatching { a.await(GattError.ConnectFailed) { true } } }
        runCurrent()
        val b = sessions.open()
        assertSame(b, sessions.current())
        assertEquals(GattError.Disconnected, error(pending.await()))
        assertNull(a.ifCurrent { 1 })
    }

    @Test fun stackRefusalAndPermissionErrorsAreTyped() = runTest {
        val s = GattSessions<String>().open()
        assertEquals(GattError.SubscribeFailed, error(runCatching { s.await(GattError.SubscribeFailed) { false } }))
        assertEquals(GattError.PermissionDenied, error(runCatching { s.await(GattError.SubscribeFailed) { throw SecurityException("x") } }))
        assertEquals(GattError.PermissionDenied, error(runCatching { s.requestMtu { throw SecurityException("x") } }))
        s.onOperationDone(null) // sem operação pendente: ignorado
    }
}

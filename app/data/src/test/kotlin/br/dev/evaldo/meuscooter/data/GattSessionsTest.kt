package br.dev.evaldo.meuscooter.data

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
        val a = sessions.open().session
        sessions.close()
        val b = sessions.open().session
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
        val a = sessions.open().session
        sessions.close()
        val b = sessions.open().session
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
        val a = sessions.open().session
        sessions.close()
        val b = sessions.open().session
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
        val s = sessions.open().session
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
        val s = sessions.open().session
        assertTrue(s.attach("gatt-A"))
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
        val s = sessions.open().session
        assertEquals(GattError.Disconnected, error(runCatching { s.await(GattError.SubscribeFailed) { sessions.close(); true } }))
        val t = sessions.open().session
        assertEquals(GattError.Disconnected, error(runCatching { t.requestMtu { sessions.close(); true } }))
    }

    @Test fun openingANewSessionClosesThePreviousOne() = runTest {
        val sessions = GattSessions<String>()
        val a = sessions.open().session
        val pending = async { runCatching { a.await(GattError.ConnectFailed) { true } } }
        runCurrent()
        val b = sessions.open().session
        assertSame(b, sessions.current())
        assertEquals(GattError.Disconnected, error(pending.await()))
        assertNull(a.ifCurrent { 1 })
    }

    @Test fun stackRefusalAndPermissionErrorsAreTyped() = runTest {
        val s = GattSessions<String>().open().session
        assertEquals(GattError.SubscribeFailed, error(runCatching { s.await(GattError.SubscribeFailed) { false } }))
        assertEquals(GattError.PermissionDenied, error(runCatching { s.await(GattError.SubscribeFailed) { throw SecurityException("x") } }))
        assertEquals(GattError.PermissionDenied, error(runCatching { s.requestMtu { throw SecurityException("x") } }))
        s.onOperationDone(null) // sem operação pendente: ignorado
    }

    /**
     * Codex r4 P2 (1): dois `connect()` concorrentes. O 1º abre B (aposentando A); antes de B obter o handle, o 2º abre C
     * (aposentando B). Cada `open()` devolve EXATAMENTE a sessão que ele retirou; quem chegar por último entre "aposentar"
     * e "anexar o handle" libera o GATT de B — uma única vez.
     */
    @Test fun everyRetiredSessionIsHandedBackOnceAndItsGattReleasedExactlyOnce() = runTest {
        val sessions = GattSessions<String>()
        val released = mutableListOf<String>()
        val a = sessions.open().session
        assertTrue(a.attach("gatt-A"))

        val first = sessions.open() // connect #1: abre B
        val second = sessions.open() // connect #2: abre C antes de B ter handle
        assertSame(a, first.retired)
        assertSame(first.session, second.retired)
        first.retired?.detachHandle()?.let(released::add) // #1 libera A
        second.retired?.detachHandle()?.let(released::add) // #2 libera B: ainda sem handle

        if (!first.session.attach("gatt-B")) released += "gatt-B" // connectGatt de #1 retorna: B já não é corrente
        if (!second.session.attach("gatt-C")) released += "gatt-C"
        assertEquals(listOf("gatt-A", "gatt-B"), released)

        sessions.close()?.detachHandle()?.let(released::add)
        assertEquals(listOf("gatt-A", "gatt-B", "gatt-C"), released)
        assertNull(first.session.detachHandle()) // já liberado: nunca de novo
    }

    /** Idem sob concorrência real: N threads abrem/anexam/liberam; todo GATT criado é liberado exatamente uma vez. */
    @Test fun concurrentOpensReleaseEveryGattExactlyOnce() {
        val sessions = GattSessions<String>()
        val released = java.util.concurrent.ConcurrentLinkedQueue<String>()
        val threads = (0 until 8).map { t ->
            Thread {
                repeat(500) { i ->
                    val opened = sessions.open()
                    opened.retired?.detachHandle()?.let(released::add)
                    val gatt = "gatt-$t-$i"
                    if (!opened.session.attach(gatt)) released += gatt
                }
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        sessions.close()?.detachHandle()?.let(released::add)
        assertEquals(8 * 500, released.size)
        assertEquals(8 * 500, released.toSet().size)
    }

    /**
     * Codex r4 P2 (2): a continuação que `open()` falha é retomada na hora (Dispatchers.Unconfined) e abre outra sessão.
     * Completar DENTRO do lock deixava o `open()` externo sobrescrever essa sessão sem fechá-la (espera órfã). Agora a
     * transição termina antes de qualquer continuação rodar: a reentrada vê o estado final e aposenta a sessão do externo.
     */
    @Test fun aReentrantContinuationResumedByOpenNeverLeavesASessionOrphaned() = runTest {
        val sessions = GattSessions<String>()
        val retired = mutableListOf<GattSession<String>>()
        val a = sessions.open().session
        var b: GattSession<String>? = null
        var bWait: Result<Unit>? = null
        launch(Dispatchers.Unconfined) {
            runCatching { a.await(GattError.ConnectFailed) { true } }
            val inner = sessions.open() // reentrada a partir da continuação de A
            inner.retired?.let(retired::add)
            b = inner.session
            bWait = runCatching { inner.session.await(GattError.ConnectFailed) { true } }
        }
        val outer = sessions.open() // falha a espera de A → a continuação roda
        outer.retired?.let(retired::add)

        assertNotNull(b)
        assertSame(b, sessions.current())
        assertEquals(listOf(outer.session, a), retired) // cada aposentada devolvida uma vez
        assertNull(bWait) // B é a corrente: sua espera segue válida
        assertEquals(GattError.Disconnected, error(runCatching { outer.session.await(GattError.ConnectFailed) { true } }))

        sessions.close()
        runCurrent()
        assertEquals(GattError.Disconnected, error(bWait!!)) // nada órfão: fechar a corrente libera a espera de B
    }

    /** Idem pelo `close()`: a continuação reentrante abre B; o `close()` externo não pode publicar sobre B nem esquecê-la. */
    @Test fun aReentrantContinuationResumedByCloseSeesTheFinishedTransition() = runTest {
        val sessions = GattSessions<String>()
        val a = sessions.open().session
        var b: GattSession<String>? = null
        launch(Dispatchers.Unconfined) {
            runCatching { a.await(GattError.ConnectFailed) { true } }
            val inner = sessions.open()
            assertNull(inner.retired) // A já foi devolvida ao close(); nada a liberar de novo
            b = inner.session
            runCatching { inner.session.await(GattError.ConnectFailed) { true } }
            inner.session.onConnected()
        }
        assertSame(a, sessions.close())
        assertSame(b, sessions.current())
        sessions.close()
    }

    /** Callback de conclusão com continuação reentrante: a conclusão é entregue depois da transição, fora do lock. */
    @Test fun aReentrantContinuationResumedByACallbackRunsOutsideTheLock() = runTest {
        val sessions = GattSessions<String>()
        val a = sessions.open().session
        var heldLock: Boolean? = null
        launch(Dispatchers.Unconfined) {
            a.await(GattError.ConnectFailed) { true }
            heldLock = Thread.holdsLock(sessions.lock)
        }
        a.onConnected()
        assertEquals(false, heldLock)
    }
}

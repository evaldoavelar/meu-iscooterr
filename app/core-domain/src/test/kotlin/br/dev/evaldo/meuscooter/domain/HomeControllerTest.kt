package br.dev.evaldo.meuscooter.domain

import br.dev.evaldo.meuscooter.data.FakeScooterTransport
import br.dev.evaldo.meuscooter.protocol.Ack
import br.dev.evaldo.meuscooter.protocol.Clock
import br.dev.evaldo.meuscooter.protocol.ConnectionStatus
import br.dev.evaldo.meuscooter.protocol.Control
import br.dev.evaldo.meuscooter.protocol.ScooterCommand
import br.dev.evaldo.meuscooter.protocol.ScooterTransport
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Seam S3: controlador + transporte simulado, tempo virtual. ACK 50 ms / estado 420 ms [CONFIRMADO] (S2). */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeControllerTest {
    /** Observa o que chega ao transporte sem alterar o comportamento. */
    private class Counting(private val d: FakeScooterTransport) : ScooterTransport by d {
        val sent = mutableListOf<ScooterCommand>()
        var connects = 0
        override suspend fun connect() { connects++; d.connect() }
        override suspend fun send(command: ScooterCommand): Ack { sent += command; return d.send(command) }
    }

    private class Rig(val fake: FakeScooterTransport, val transport: Counting, val controller: HomeController)

    private suspend fun TestScope.rig(connect: Boolean = true): Rig {
        val clock = Clock { currentTime }
        val fake = FakeScooterTransport(backgroundScope, clock)
        val counting = Counting(fake)
        val controller = HomeController(counting, backgroundScope, clock)
        runCurrent()
        if (connect) { controller.connect(); advanceTimeBy(900); runCurrent() }
        return Rig(fake, counting, controller)
    }

    private val Rig.state: HomeState get() = controller.state.value

    @Test
    fun connectsAndReceivesTelemetry() = runTest {
        val r = rig(connect = false)
        assertEquals(ConnectionStatus.Disconnected, r.state.connection)
        r.controller.connect(); advanceTimeBy(900); runCurrent()
        assertEquals(ConnectionStatus.Connected, r.state.connection)
        assertEquals(true, r.state.telemetry?.locked)
    }

    @Test
    fun unlockIsPendingThenConfirmedOnlyAfterTelemetryThenFades() = runTest {
        val r = rig()
        r.controller.request(ScooterCommand.SetLocked(false)); runCurrent()
        assertTrue(Control.Lock in r.state.pending)
        advanceTimeBy(60); runCurrent()                       // ACK já chegou
        assertTrue(Control.Lock in r.state.pending); assertNull(r.state.outcomes[Control.Lock])
        advanceTimeBy(365); runCurrent()                      // ~425 ms: telemetria nova
        assertFalse(Control.Lock in r.state.pending)
        assertTrue(r.state.outcomes[Control.Lock] is Outcome.Confirmed)
        assertEquals(false, r.state.telemetry?.locked)
        advanceTimeBy(2_300); runCurrent()
        assertNull(r.state.outcomes[Control.Lock])
    }

    @Test
    fun duplicateRequestSendsOnlyOneCommand() = runTest {
        val r = rig()
        r.controller.request(ScooterCommand.SetHeadlight(true))
        r.controller.request(ScooterCommand.SetHeadlight(true)); runCurrent()
        assertEquals(1, r.transport.sent.size)
    }

    @Test
    fun rejectionShowsFailureAndRetryWorks() = runTest {
        val r = rig()
        r.fake.failNextCommand("recusado")
        r.controller.request(ScooterCommand.SetHeadlight(true)); advanceTimeBy(100); runCurrent()
        val failure = r.state.outcomes[Control.Headlight] as Outcome.Failed
        assertEquals("recusado", failure.reason)
        r.controller.request(ScooterCommand.SetHeadlight(true)); advanceTimeBy(500); runCurrent()
        assertTrue(r.state.outcomes[Control.Headlight] is Outcome.Confirmed)
    }

    @Test
    fun ackedButNotReflectedTimesOutAfterThreeSeconds() = runTest {
        val r = rig()
        r.fake.ignoreNextCommand()
        r.controller.request(ScooterCommand.SetCruise(true)); advanceTimeBy(2_900); runCurrent()
        assertTrue(Control.Cruise in r.state.pending)
        advanceTimeBy(400); runCurrent()
        assertTrue(r.state.outcomes[Control.Cruise] is Outcome.TimedOut)
    }

    @Test
    fun movingBlocksRestrictedCommandsWithoutTouchingTransport() = runTest {
        val r = rig()
        r.fake.setSpeed(15f); advanceTimeBy(400); runCurrent()
        r.controller.request(ScooterCommand.SetLocked(true))
        r.controller.request(ScooterCommand.SetSpeedLimit(30)); runCurrent()
        assertTrue(r.transport.sent.isEmpty())
        r.controller.request(ScooterCommand.SetHeadlight(true)); runCurrent()
        assertEquals(1, r.transport.sent.size)
    }

    @Test
    fun staleTelemetryDisablesRestrictedControls() = runTest {
        val r = rig()
        r.fake.freezeTelemetry(true); advanceTimeBy(2_600); runCurrent()
        assertEquals(
            Availability.Disabled(DisabledReason.TelemetryStale),
            HomeReducer.availability(r.state, ScooterCommand.SetCruise(true)),
        )
    }

    @Test
    fun disconnectDuringPendingFailsIt() = runTest {
        val r = rig()
        r.controller.request(ScooterCommand.SetHeadlight(true)); runCurrent()
        r.controller.disconnect(); runCurrent()
        assertEquals(ConnectionStatus.Disconnected, r.state.connection)
        assertTrue(r.state.outcomes[Control.Headlight] is Outcome.Failed)
    }

    @Test
    fun repeatedConnectWhileConnectingOrConnectedReachesTransportOnce() = runTest {
        val r = rig(connect = false)
        r.controller.connect(); runCurrent()
        r.controller.connect(); runCurrent()          // ainda conectando
        advanceTimeBy(900); runCurrent()
        r.controller.connect(); runCurrent()          // já conectado
        assertEquals(1, r.transport.connects)
    }

    /** BAIXO do codex-r1: com um connect() pendurado, repetir é ignorado; disconnect() cancela e libera nova tentativa. */
    @Test fun `disconnect cancela o connect pendente e permite reconectar`() = runTest {
        val clock = Clock { currentTime }
        val fake = FakeScooterTransport(backgroundScope, clock)
        var connects = 0
        val hanging = object : ScooterTransport by fake {
            override suspend fun connect() { connects++; kotlinx.coroutines.delay(60_000) }
        }
        val controller = HomeController(hanging, backgroundScope, clock)
        runCurrent()
        controller.connect(); runCurrent(); controller.connect(); runCurrent()
        assertEquals(1, connects)
        controller.disconnect(); runCurrent()
        controller.connect(); runCurrent()
        assertEquals(2, connects)
    }
}

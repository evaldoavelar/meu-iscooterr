package br.dev.evaldo.meuscooter.data

import br.dev.evaldo.meuscooter.protocol.Ack
import br.dev.evaldo.meuscooter.protocol.Clock
import br.dev.evaldo.meuscooter.protocol.FieldGroup
import br.dev.evaldo.meuscooter.protocol.ConnectionStatus
import br.dev.evaldo.meuscooter.protocol.ScooterCommand
import br.dev.evaldo.meuscooter.protocol.Telemetry
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `BleScooterTransport` sobre `FakeGattClient` e tempo virtual. NENHUM BLE real.
 * Quadros = linhas `RX` reais da captura S2 (`ix7tap-roteiro2.dedup.txt`); valores esperados escritos à mão (ver RxFrameParserTest).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BleScooterTransportTest {
    private val meter1 = hex("1aa120031200000064000000320000000023232302030853f81ff1")
    private val meter1Locked = hex("1aa120031200200064000000320000000023232302030873e01ff1")
    private val meter2 = hex("1aa12103120000ae1500000000170000000000160f03085d8b1ff1")
    private val ackConnect = hex("1aa130050101d5b31ff1")

    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private class Rig(scope: TestScope, config: BleConfig = BleConfig(), discoveryMs: Long? = null) {
        val gatt = FakeGattClient(Clock { scope.currentTime })
        val transport = BleScooterTransport(
            scope = scope.backgroundScope,
            clock = Clock { scope.currentTime },
            gatt = gatt,
            address = ADDRESS,
            config = config,
            discoveryMs = discoveryMs,
        )
        val seen = mutableListOf<Telemetry>()

        init {
            scope.backgroundScope.launch { transport.telemetry.collect { seen += it } }
        }
    }

    private fun TestScope.at(timeMs: Long, block: () -> Unit) {
        backgroundScope.launch { delay(timeMs); block() }
    }

    private val noWrites get() = { g: FakeGattClient -> assertTrue("nenhuma escrita/comando", g.calls.none { it.startsWith("write") } && g.writes.isEmpty()) }

    @Test fun defaultsAreTheApprovedTimeouts() {
        val c = BleConfig()
        assertEquals(15_000L, c.firstTelemetryTimeoutMs) // plano Fase 4 §6: 1ª notificação 15 s
        assertEquals(60_000L, c.observationWindowMs) // plano Fase 4 §6: janela de observação 60 s (separada)
    }

    @Test fun happyPathConnectsSubscribesAndMeasuresEachStep() = runTest {
        val r = Rig(this, discoveryMs = 1234)
        r.gatt.connectDelayMs = 300; r.gatt.discoverDelayMs = 200; r.gatt.mtuDelayMs = 50; r.gatt.subscribeDelayMs = 100
        at(800) { r.gatt.emit(meter1) }
        r.transport.connect()
        assertEquals(800L, currentTime) // 1ª telemetria em 800 = 250 ms depois de a inscrição começar (em 550)
        assertEquals(ConnectionStatus.Connected, r.transport.connection.value)
        assertNull(r.transport.failure.value)
        assertEquals(ConnectionTimings(scanMs = 1234, connectMs = 300, servicesMs = 200, mtuMs = 50, subscribeMs = 100, firstTelemetryMs = 250), r.transport.timings.value)
        assertEquals(listOf(Telemetry(receivedAtMs = 800, locked = false, batteryPercent = 100, speedLimitKmh = 50)), r.seen)
        assertEquals(listOf("connect:$ADDRESS", "discover", "mtu:247", "subscribe:${ScooterGatt.SERVICE}/${ScooterGatt.RX}"), r.gatt.calls)
        noWrites(r.gatt)
    }

    @Test fun mtuIsRequestedBetweenDiscoveryAndSubscribe() = runTest {
        val r = Rig(this)
        at(10) { r.gatt.emit(meter1) }
        r.transport.connect()
        assertEquals(listOf("connect:$ADDRESS", "discover", "mtu:247", "subscribe:${ScooterGatt.SERVICE}/${ScooterGatt.RX}"), r.gatt.calls)
    }

    @Test fun mtuStepTimesOutAndClosesTheGatt() = runTest {
        val r = Rig(this, BleConfig(mtuTimeoutMs = 1500))
        r.gatt.mtuDelayMs = 9000
        r.transport.connect()
        assertEquals(1500L, currentTime)
        assertEquals(ConnectFailure.Timeout(ConnectStep.Mtu), r.transport.failure.value)
        assertEquals(ConnectionStatus.Disconnected, r.transport.connection.value)
        assertTrue(r.gatt.calls.none { it.startsWith("subscribe") })
        assertEquals("close", r.gatt.calls.last())
    }

    @Test fun refusedMtuIsFatalAndTyped() = runTest {
        val r = Rig(this)
        r.gatt.mtuError = GattException(GattError.MtuFailed)
        r.transport.connect()
        assertEquals(ConnectFailure.Gatt(GattError.MtuFailed), r.transport.failure.value)
        assertEquals(ConnectionStatus.Disconnected, r.transport.connection.value)
        assertTrue(r.gatt.calls.none { it.startsWith("subscribe") })
        assertEquals("close", r.gatt.calls.last())
    }

    @Test fun frameIsIntactAfterMtuNegotiationEvenWhenTruncationIsSimulated() = runTest {
        val r = Rig(this)
        r.gatt.simulateTruncation = true
        at(10) { r.gatt.emit(meter1) }
        r.transport.connect()
        assertEquals(247, r.gatt.mtu)
        assertEquals(ConnectionStatus.Connected, r.transport.connection.value)
        assertEquals(listOf(Telemetry(receivedAtMs = 10, locked = false, batteryPercent = 100, speedLimitKmh = 50)), r.seen)
        assertEquals(RxStats(valid = 1, telemetry = 1, invalid = 0), r.transport.stats.value)
    }

    @Test fun grantedMtuTooSmallForA27ByteFrameIsFatalAndNothingIsSubscribed() = runTest {
        val r = Rig(this)
        r.gatt.simulateTruncation = true
        r.gatt.peerMaxMtu = 23
        r.transport.connect()
        assertEquals(ConnectFailure.MtuTooSmall(granted = 23), r.transport.failure.value)
        assertEquals(ConnectionStatus.Disconnected, r.transport.connection.value)
        assertTrue(r.gatt.calls.none { it.startsWith("subscribe") })
        assertEquals("close", r.gatt.calls.last())
        assertTrue(r.seen.isEmpty())
    }

    @Test fun fieldAgesSurviveAMeter2OnlyStream() = runTest {
        val r = Rig(this)
        at(10) { r.gatt.emit(meter1) }
        at(5010) { r.gatt.emit(meter2) }
        r.transport.connect()
        advanceTimeBy(5100)
        val aged = r.transport.aged.value!!
        assertEquals(5000L, aged.ageMs(FieldGroup.Meter1, nowMs = 5010)) // bateria/lock têm 5 s
        assertEquals(0L, aged.ageMs(FieldGroup.Meter2, nowMs = 5010))
        assertTrue(aged.isStale(FieldGroup.Meter1, nowMs = 5010, maxAgeMs = 4000))
        assertEquals(100, aged.telemetry.batteryPercent)
        assertEquals(r.seen.last(), aged.telemetry) // mesmo valor que a telemetria emitida
    }

    @Test fun agedTelemetryThroughTheInterfaceKeepsTheStaleMeter1Time() = runTest {
        val r = Rig(this)
        val iface: br.dev.evaldo.meuscooter.protocol.ScooterTransport = r.transport
        val seenAged = mutableListOf<br.dev.evaldo.meuscooter.protocol.AgedTelemetry>()
        backgroundScope.launch { iface.agedTelemetry.collect { seenAged += it } }
        at(10) { r.gatt.emit(meter1) }
        at(5010) { r.gatt.emit(meter2) }
        r.transport.connect()
        advanceTimeBy(5100)
        assertEquals(10L, seenAged.last().meter1AtMs) // Meter1 parado desde t=10
        assertEquals(5010L, seenAged.last().meter2AtMs)
    }

    @Test fun missingServiceIsATypedFailureAndNothingIsSubscribed() = runTest {
        val r = Rig(this)
        r.gatt.services = listOf(GattServiceInfo(UUID.randomUUID(), setOf(ScooterGatt.RX, ScooterGatt.WR)))
        r.transport.connect()
        assertEquals(ConnectFailure.ServiceMissing(listOf(ScooterGatt.SERVICE)), r.transport.failure.value)
        assertEquals(ConnectionStatus.Disconnected, r.transport.connection.value)
        assertTrue(r.gatt.calls.none { it.startsWith("subscribe") })
        assertEquals("close", r.gatt.calls.last())
    }

    @Test fun missingCharacteristicsAreReported() = runTest {
        val r = Rig(this)
        r.gatt.services = listOf(GattServiceInfo(ScooterGatt.SERVICE, setOf(ScooterGatt.RX)))
        r.transport.connect()
        assertEquals(ConnectFailure.ServiceMissing(listOf(ScooterGatt.WR)), r.transport.failure.value)
        r.gatt.services = listOf(GattServiceInfo(ScooterGatt.SERVICE, emptySet()))
        r.transport.connect()
        assertEquals(ConnectFailure.ServiceMissing(listOf(ScooterGatt.RX, ScooterGatt.WR)), r.transport.failure.value)
    }

    @Test fun connectStepTimesOutWithVirtualTime() = runTest {
        val r = Rig(this, BleConfig(connectTimeoutMs = 1000))
        r.gatt.connectDelayMs = 5000
        r.transport.connect()
        assertEquals(1000L, currentTime)
        assertEquals(ConnectFailure.Timeout(ConnectStep.Connect), r.transport.failure.value)
        assertEquals(ConnectionStatus.Disconnected, r.transport.connection.value)
        assertEquals("close", r.gatt.calls.last())
    }

    @Test fun serviceDiscoveryTimesOutKeepingPartialTimings() = runTest {
        val r = Rig(this, BleConfig(discoverTimeoutMs = 2000))
        r.gatt.connectDelayMs = 100; r.gatt.discoverDelayMs = 9000
        r.transport.connect()
        assertEquals(2100L, currentTime)
        assertEquals(ConnectFailure.Timeout(ConnectStep.DiscoverServices), r.transport.failure.value)
        assertEquals(ConnectionTimings(connectMs = 100), r.transport.timings.value)
    }

    @Test fun subscribeTimesOut() = runTest {
        val r = Rig(this, BleConfig(subscribeTimeoutMs = 3000))
        r.gatt.subscribeDelayMs = 9000
        r.transport.connect()
        assertEquals(3000L, currentTime)
        assertEquals(ConnectFailure.Timeout(ConnectStep.Subscribe), r.transport.failure.value)
    }

    @Test fun noTelemetryAfterCccdFailsAtFifteenSecondsAndSendsNothing() = runTest {
        val r = Rig(this)
        r.transport.connect()
        assertEquals(15_000L, currentTime)
        assertEquals(ConnectFailure.NoTelemetry, r.transport.failure.value)
        assertEquals(ConnectionStatus.Disconnected, r.transport.connection.value)
        assertEquals(listOf("connect:$ADDRESS", "discover", "mtu:247", "subscribe:${ScooterGatt.SERVICE}/${ScooterGatt.RX}", "close"), r.gatt.calls)
        noWrites(r.gatt)
    }

    @Test fun garbageAndAcksAreNotTelemetry() = runTest {
        val r = Rig(this)
        for (t in 1000L..14_000L step 1000L) at(t) { r.gatt.emit(byteArrayOf(1, 2, 3)); r.gatt.emit(ackConnect) }
        r.transport.connect()
        assertEquals(ConnectFailure.NoTelemetry, r.transport.failure.value)
        assertTrue(r.seen.isEmpty())
        assertEquals(RxStats(valid = 14, telemetry = 0, invalid = 14), r.transport.stats.value)
    }

    @Test fun meter2WithWrongPayloadSizeIsInvalidAndNeverConfirmsTheConnection() = runTest {
        val r = Rig(this)
        // Revisão Codex P4: cabeçalho/rodapé corretos, LEN 0 (S2 registra LEN 18).
        for (t in 1000L..3000L step 1000L) at(t) { r.gatt.emit(hex("1aa121030000001ff1")) }
        r.transport.connect()
        assertEquals(ConnectFailure.NoTelemetry, r.transport.failure.value)
        assertEquals(ConnectionStatus.Disconnected, r.transport.connection.value)
        assertTrue(r.seen.isEmpty())
        assertEquals(RxStats(valid = 0, telemetry = 0, invalid = 3), r.transport.stats.value)
    }

    @Test fun cancellingTheTransportScopeAfterConnectedClosesTheGatt() = runTest {
        // Revisão Codex P1: o ViewModel é destruído depois de connect() retornar Connected.
        val gatt = FakeGattClient(Clock { currentTime })
        val transportScope = CoroutineScope(backgroundScope.coroutineContext + Job())
        val transport = BleScooterTransport(transportScope, Clock { currentTime }, gatt, ADDRESS)
        at(10) { gatt.emit(meter1) }
        transport.connect()
        assertEquals(ConnectionStatus.Connected, transport.connection.value)
        assertEquals(0, gatt.closeCalls)
        transportScope.cancel()
        runCurrent()
        assertEquals(1, gatt.closeCalls)
        assertEquals(ConnectionStatus.Disconnected, transport.connection.value)
    }

    @Test fun lostLinkDuringServiceDiscoveryIsTyped() = runTest {
        val r = Rig(this)
        r.gatt.discoverDelayMs = 10_000
        at(500) { r.gatt.dropLink() }
        r.transport.connect()
        assertEquals(500L, currentTime)
        assertEquals(ConnectFailure.LinkLost, r.transport.failure.value)
        assertEquals(ConnectionStatus.Disconnected, r.transport.connection.value)
    }

    @Test fun lostLinkDuringTheMtuStepIsTypedAndNothingIsSubscribed() = runTest {
        val r = Rig(this)
        r.gatt.mtuDelayMs = 10_000
        at(500) { r.gatt.dropLink() }
        r.transport.connect()
        assertEquals(500L, currentTime)
        assertEquals(ConnectFailure.LinkLost, r.transport.failure.value)
        assertEquals(ConnectionStatus.Disconnected, r.transport.connection.value)
        assertTrue(r.gatt.calls.none { it.startsWith("subscribe") })
        noWrites(r.gatt)
    }

    @Test fun lostLinkWhileWaitingForFirstTelemetry() = runTest {
        val r = Rig(this)
        at(2000) { r.gatt.dropLink() }
        r.transport.connect()
        assertEquals(2000L, currentTime)
        assertEquals(ConnectFailure.LinkLost, r.transport.failure.value)
    }

    @Test fun gattAndUnexpectedErrorsAreMapped() = runTest {
        val r = Rig(this)
        r.gatt.connectError = GattException(GattError.ConnectFailed)
        r.transport.connect()
        assertEquals(ConnectFailure.Gatt(GattError.ConnectFailed), r.transport.failure.value)
        r.gatt.connectError = null
        r.gatt.subscribeError = GattException(GattError.SubscribeFailed)
        r.transport.connect()
        assertEquals(ConnectFailure.Gatt(GattError.SubscribeFailed), r.transport.failure.value)
        r.gatt.subscribeError = null
        r.gatt.discoverError = IllegalStateException("boom")
        r.transport.connect()
        assertEquals(ConnectFailure.Unexpected("boom"), r.transport.failure.value)
    }

    @Test fun disconnectDuringConnectEndsItWithoutFailure() = runTest {
        val r = Rig(this)
        r.gatt.discoverDelayMs = 10_000
        val c = launch { r.transport.connect() }
        advanceTimeBy(500)
        r.transport.disconnect()
        runCurrent()
        assertTrue(c.isCompleted)
        assertEquals(ConnectionStatus.Disconnected, r.transport.connection.value)
        assertNull(r.transport.failure.value)
        assertEquals("close", r.gatt.calls.last())
        advanceTimeBy(60_000) // nada acontece depois
        assertEquals(ConnectionStatus.Disconnected, r.transport.connection.value)
    }

    @Test fun cancellingTheCallerClosesTheGatt() = runTest {
        val r = Rig(this)
        r.gatt.discoverDelayMs = 10_000
        val c = launch { r.transport.connect() }
        advanceTimeBy(500)
        c.cancelAndJoin()
        assertEquals(ConnectionStatus.Disconnected, r.transport.connection.value)
        assertNull(r.transport.failure.value)
        assertEquals("close", r.gatt.calls.last())
    }

    @Test fun lateNotificationsAfterFailureAreIgnored() = runTest {
        val r = Rig(this)
        r.transport.connect() // falha em 15 s
        val before = r.transport.stats.value
        r.gatt.emit(meter1)
        advanceTimeBy(5000)
        assertEquals(before, r.transport.stats.value)
        assertTrue(r.seen.isEmpty())
        assertEquals(ConnectionStatus.Disconnected, r.transport.connection.value)
    }

    @Test fun lateNotificationAfterDisconnectIsIgnored() = runTest {
        val r = Rig(this)
        at(10) { r.gatt.emit(meter1) }
        r.transport.connect()
        r.transport.disconnect()
        r.gatt.emit(meter2)
        advanceTimeBy(1000)
        assertEquals(1, r.seen.size)
    }

    @Test fun notificationBeforeCccdCompletesCountsAsFirstTelemetry() = runTest {
        val r = Rig(this)
        r.gatt.subscribeDelayMs = 300
        at(100) { r.gatt.emit(meter1) }
        r.transport.connect()
        assertEquals(300L, currentTime)
        assertEquals(ConnectionStatus.Connected, r.transport.connection.value)
        assertEquals(100L, r.transport.timings.value.firstTelemetryMs)
        assertEquals(300L, r.transport.timings.value.subscribeMs)
    }

    @Test fun duplicatedOutOfOrderAndGarbageNotificationsAreTolerated() = runTest {
        val r = Rig(this)
        at(10) { r.gatt.emit(meter2) } // Meter2 antes do Meter1: ordem não importa
        r.transport.connect()
        r.gatt.emit(byteArrayOf())
        r.gatt.emit(meter1Locked.copyOf(12)) // fragmento
        r.gatt.emit(meter1Locked)
        r.gatt.emit(meter1Locked) // duplicado
        r.gatt.emit(meter2)
        runCurrent()
        assertEquals(RxStats(valid = 4, telemetry = 4, invalid = 2), r.transport.stats.value)
        val last = r.seen.last()
        assertEquals(true, last.locked) // vem do Meter1; o Meter2 posterior não apaga
        assertEquals(100, last.batteryPercent)
        assertEquals(50, last.speedLimitKmh)
    }

    @Test fun linkLostAfterConnectingReportsAndStopsTelemetry() = runTest {
        val r = Rig(this)
        at(10) { r.gatt.emit(meter1) }
        r.transport.connect()
        r.gatt.dropLink()
        runCurrent()
        assertEquals(ConnectionStatus.Disconnected, r.transport.connection.value)
        assertEquals(ConnectFailure.LinkLost, r.transport.failure.value)
        r.gatt.emit(meter1)
        runCurrent()
        assertEquals(1, r.seen.size)
    }

    @Test fun disconnectIsIdempotent() = runTest {
        val r = Rig(this)
        r.transport.disconnect() // antes de conectar
        at(10) { r.gatt.emit(meter1) }
        r.transport.connect()
        r.transport.disconnect()
        r.transport.disconnect()
        assertEquals(ConnectionStatus.Disconnected, r.transport.connection.value)
        assertNull(r.transport.failure.value)
    }

    @Test fun connectWhileConnectingOrConnectedIsIgnored() = runTest {
        val r = Rig(this)
        r.gatt.connectDelayMs = 100
        at(500) { r.gatt.emit(meter1) }
        val first = launch { r.transport.connect() }
        runCurrent()
        r.transport.connect() // Connecting: retorna na hora
        assertEquals(0L, currentTime)
        first.join()
        r.transport.connect() // Connected: retorna na hora
        assertEquals(1, r.gatt.calls.count { it.startsWith("connect:") })
    }

    @Test fun reconnectAfterFailureStartsClean() = runTest {
        val r = Rig(this)
        r.gatt.services = emptyList()
        r.transport.connect()
        assertTrue(r.transport.failure.value is ConnectFailure.ServiceMissing)
        r.gatt.services = listOf(GattServiceInfo(ScooterGatt.SERVICE, setOf(ScooterGatt.RX, ScooterGatt.WR)))
        at(10) { r.gatt.emit(meter1) }
        r.transport.connect()
        assertNull(r.transport.failure.value)
        assertEquals(ConnectionStatus.Connected, r.transport.connection.value)
        assertEquals(RxStats(valid = 1, telemetry = 1, invalid = 0), r.transport.stats.value)
    }

    @Test fun sendIsRejectedAndNeverTouchesTheGatt() = runTest {
        val r = Rig(this)
        val msg = "comandos desabilitados na Fase 4.x"
        assertEquals(Ack.Rejected(msg), r.transport.send(ScooterCommand.SetLocked(true)))
        at(10) { r.gatt.emit(meter1) }
        r.transport.connect()
        val callsBefore = r.gatt.calls.toList()
        assertEquals(Ack.Rejected(msg), r.transport.send(ScooterCommand.SetLocked(false)))
        assertEquals(callsBefore, r.gatt.calls)
        noWrites(r.gatt)
    }

    @Test fun realTransportOffersNoUnconfirmedSpeedModeNames() = runTest {
        assertTrue(Rig(this).transport.speedModes.options().isEmpty())
    }

    @Test fun observationWindowIsSeparateAndCountsOnlyWhatArrivesDuringIt() = runTest {
        val r = Rig(this)
        at(10) { r.gatt.emit(meter1) }
        r.transport.connect()
        val start = currentTime
        for (t in listOf(10_000L, 20_000L, 30_000L)) at(t) { r.gatt.emit(meter2) }
        val stats = r.transport.observe()
        assertEquals(start + 60_000, currentTime)
        assertEquals(RxStats(valid = 3, telemetry = 3, invalid = 0), stats)
    }

    @Test fun observationEndsEarlyIfTheLinkIsLost() = runTest {
        val r = Rig(this)
        at(10) { r.gatt.emit(meter1) }
        r.transport.connect()
        val start = currentTime
        at(5000) { r.gatt.dropLink() }
        r.transport.observe()
        assertEquals(start + 5000, currentTime)
    }

    private companion object {
        const val ADDRESS = "AA:BB:CC:DD:EE:FF"
    }
}

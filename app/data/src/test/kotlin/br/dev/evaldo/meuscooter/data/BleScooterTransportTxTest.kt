package br.dev.evaldo.meuscooter.data

import br.dev.evaldo.meuscooter.protocol.Ack
import br.dev.evaldo.meuscooter.protocol.Clock
import br.dev.evaldo.meuscooter.protocol.ConnectionStatus
import br.dev.evaldo.meuscooter.protocol.ScooterCommand
import br.dev.evaldo.meuscooter.protocol.Telemetry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
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
 * Modo TX opcional do `BleScooterTransport` (etapa 4.3a, plano Fase 4 §7.2) sobre `FakeGattClient` e tempo virtual.
 * NENHUM BLE real. Todos os quadros são LITERAIS de `research/captures/s4-3/ix7tap.txt` (app original, 2026-11-05):
 * TX CONNECT/heartbeat, os ACKs reais (CRC de resposta inválido) e o Meter1 bloqueado com senha (`dado[1]` = 0x30).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BleScooterTransportTxTest {
    private val connectTx = "1aa1300201016eb41ff1"
    private val heartbeatTx = "1aa110010071951ff1"
    private val connectAck = "1aa130050101d5b31ff1"
    private val heartbeatAck = "1aa1100500be8c1ff1"
    private val meter1Unlocked = hex("1aa1200312000000640000003c000000002323230203081f981ff1")
    private val meter1LockedWithPin = hex("1aa1200312003000640000003c000000002323230203082f8c1ff1")
    private val meter2 = hex("1aa12103120000ae1500000000170000000000160f03085d8b1ff1")

    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun str(b: List<Byte>) = b.joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    private inner class Rig(val scope: TestScope, config: BleConfig = BleConfig(txEnabled = true), transportScope: CoroutineScope = scope.backgroundScope) {
        val gatt = FakeGattClient()
        val transport = BleScooterTransport(transportScope, Clock { scope.currentTime }, gatt, ADDRESS, config)
        val seen = mutableListOf<Telemetry>()

        /** Instante (virtual) de cada escrita, com os bytes em hex. */
        val log = mutableListOf<Pair<Long, String>>()
        var ackConnect = true
        var ackHeartbeat: (Int) -> Boolean = { true }
        private var heartbeats = 0

        init {
            scope.backgroundScope.launch { transport.telemetry.collect { seen += it } }
            gatt.reply = { bytes ->
                val h = str(bytes.toList())
                log += scope.currentTime to h
                when (h) {
                    connectTx -> if (ackConnect) hex(connectAck) else null
                    heartbeatTx -> if (ackHeartbeat(++heartbeats)) hex(heartbeatAck) else null
                    else -> null
                }
            }
        }

        val written get() = gatt.writes.map { str(it.third) }
    }

    private fun TestScope.at(timeMs: Long, block: () -> Unit) {
        backgroundScope.launch { delay(timeMs); block() }
    }

    @Test fun txIsOffByDefaultAndNothingIsEverWritten() = runTest {
        assertEquals(false, BleConfig().txEnabled)
        val r = Rig(this, BleConfig())
        at(10) { r.gatt.emit(meter1Unlocked) }
        r.transport.connect()
        advanceTimeBy(30_000)
        assertEquals(ConnectionStatus.Connected, r.transport.connection.value)
        assertTrue(r.gatt.writes.isEmpty())
        assertTrue(r.gatt.calls.none { it.startsWith("write") })
    }

    @Test fun approvedDefaults() {
        val c = BleConfig()
        assertEquals(2_000L, c.ackTimeoutMs) // §7.4: ACK ausente > 2 s = abortar
        assertEquals(1_000L, c.heartbeatPeriodMs) // original: ~1,0 s (mediana 1,01 s, s4-3)
        assertEquals(3, c.heartbeatMissLimit) // §7.2: 3 heartbeats seguidos sem ACK
    }

    @Test fun connectIsWrittenOnceAfterTheFirstTelemetryThenHeartbeatsEverySecond() = runTest {
        val r = Rig(this)
        r.gatt.writeDelayMs = 100 // ACK do CONNECT em ~110 ms no original
        at(10) { r.gatt.emit(meter1Unlocked) }
        r.transport.connect()
        assertEquals(10L, currentTime) // connect() volta ao ficar Conectado, como antes
        advanceTimeBy(3_500)
        assertEquals(listOf(connectTx, heartbeatTx, heartbeatTx, heartbeatTx), r.written)
        // CONNECT logo após a 1ª telemetria; heartbeat 1 s depois do ACK e a cada 1 s.
        assertEquals(listOf(110L, 1_210L, 2_210L, 3_210L), r.log.map { it.first })
        assertTrue(r.gatt.writes.all { it.first == ScooterGatt.SERVICE && it.second == ScooterGatt.WR })
        assertEquals(ConnectionStatus.Connected, r.transport.connection.value)
        assertNull(r.transport.failure.value)
        assertEquals(1, r.gatt.maxWritesInFlight)
    }

    @Test fun noConnectAckWithinTheDeadlineIsATypedFailureAndClosesTheGatt() = runTest {
        val r = Rig(this, BleConfig(txEnabled = true, ackTimeoutMs = 2_000))
        r.ackConnect = false
        at(10) { r.gatt.emit(meter1Unlocked) }
        r.transport.connect()
        advanceTimeBy(1_999)
        assertEquals(ConnectionStatus.Connected, r.transport.connection.value)
        advanceTimeBy(2)
        assertEquals(ConnectFailure.ConnectNotAcknowledged, r.transport.failure.value)
        assertEquals(ConnectionStatus.Disconnected, r.transport.connection.value)
        assertEquals("close", r.gatt.calls.last())
        advanceTimeBy(10_000)
        assertEquals(listOf(connectTx), r.written) // nunca reenvia nem começa heartbeat
    }

    @Test fun threeConsecutiveHeartbeatsWithoutAckAreALostLink() = runTest {
        val r = Rig(this)
        r.ackHeartbeat = { false }
        at(10) { r.gatt.emit(meter1Unlocked) }
        r.transport.connect()
        advanceTimeBy(3_999)
        assertEquals(ConnectionStatus.Connected, r.transport.connection.value)
        advanceTimeBy(2)
        // heartbeats em 1010, 2010, 3010; o 3º expira em 4010 (≥ 3 s sem nenhum ACK).
        assertEquals(listOf(10L, 1_010L, 2_010L, 3_010L), r.log.map { it.first })
        assertEquals(ConnectFailure.HeartbeatLost, r.transport.failure.value)
        assertEquals(ConnectionStatus.Disconnected, r.transport.connection.value)
        assertEquals("close", r.gatt.calls.last())
        advanceTimeBy(10_000)
        assertEquals(4, r.gatt.writes.size)
    }

    @Test fun anAckResetsTheMissCounter() = runTest {
        val r = Rig(this)
        r.ackHeartbeat = { n -> n % 3 == 0 } // 2 perdidos, 1 ok, 2 perdidos, 1 ok…
        at(10) { r.gatt.emit(meter1Unlocked) }
        r.transport.connect()
        advanceTimeBy(12_000)
        assertEquals(ConnectionStatus.Connected, r.transport.connection.value)
        assertNull(r.transport.failure.value)
    }

    @Test fun anAckThatArrivesBeforeTheWriteCompletesStillCounts() = runTest {
        val r = Rig(this)
        r.ackConnect = false
        r.gatt.writeDelayMs = 500 // a notificação do ACK chega antes de `onCharacteristicWrite`
        at(10) { r.gatt.emit(meter1Unlocked) }
        at(200) { r.gatt.emit(hex(connectAck)) }
        r.transport.connect()
        advanceTimeBy(5_000)
        assertNull(r.transport.failure.value)
        assertEquals(ConnectionStatus.Connected, r.transport.connection.value)
        assertTrue(r.written.count { it == heartbeatTx } >= 2)
    }

    @Test fun heartbeatWriteFailureEndsTheLinkTyped() = runTest {
        val r = Rig(this)
        at(10) { r.gatt.emit(meter1Unlocked) }
        r.transport.connect()
        advanceTimeBy(500)
        r.gatt.writeError = GattException(GattError.WriteFailed)
        advanceTimeBy(1_000)
        assertEquals(ConnectFailure.Gatt(GattError.WriteFailed), r.transport.failure.value)
        assertEquals(ConnectionStatus.Disconnected, r.transport.connection.value)
    }

    @Test fun connectWriteFailureEndsTheLinkTyped() = runTest {
        val r = Rig(this)
        r.gatt.writeError = GattException(GattError.WriteFailed)
        at(10) { r.gatt.emit(meter1Unlocked) }
        r.transport.connect()
        runCurrent()
        assertEquals(ConnectFailure.Gatt(GattError.WriteFailed), r.transport.failure.value)
        assertEquals(ConnectionStatus.Disconnected, r.transport.connection.value)
        assertEquals(1, r.gatt.writes.size)
    }

    @Test fun aHangingWriteCountsAsAMissingAckAndNeverOverlaps() = runTest {
        val r = Rig(this)
        at(10) { r.gatt.emit(meter1Unlocked) }
        r.transport.connect()
        advanceTimeBy(500)
        r.gatt.writeDelayMs = 60_000 // a escrita nunca conclui
        advanceTimeBy(4_000)
        assertEquals(ConnectFailure.HeartbeatLost, r.transport.failure.value)
        assertEquals(1, r.gatt.maxWritesInFlight)
    }

    @Test fun disconnectStopsTheHeartbeat() = runTest {
        val r = Rig(this)
        at(10) { r.gatt.emit(meter1Unlocked) }
        r.transport.connect()
        advanceTimeBy(2_500)
        val before = r.gatt.writes.size
        r.transport.disconnect()
        advanceTimeBy(30_000)
        assertEquals(before, r.gatt.writes.size)
        assertNull(r.transport.failure.value)
    }

    @Test fun cancellingTheTransportScopeStopsTheHeartbeat() = runTest {
        val transportScope = CoroutineScope(backgroundScope.coroutineContext + Job())
        val r = Rig(this, transportScope = transportScope)
        at(10) { r.gatt.emit(meter1Unlocked) }
        r.transport.connect()
        advanceTimeBy(2_500)
        val before = r.gatt.writes.size
        transportScope.cancel()
        advanceTimeBy(30_000)
        assertEquals(before, r.gatt.writes.size)
        assertEquals(ConnectionStatus.Disconnected, r.transport.connection.value)
    }

    @Test fun onlyConnectAndHeartbeatBytesAreEverWrittenAndSendStaysRejected() = runTest {
        val r = Rig(this)
        at(10) { r.gatt.emit(meter1LockedWithPin) }
        r.transport.connect()
        val all = listOf(
            ScooterCommand.SetLocked(true), ScooterCommand.SetLocked(false), ScooterCommand.SetHeadlight(true),
            ScooterCommand.SetDualMotor(true), ScooterCommand.SetSpeedMode(1), ScooterCommand.SetCruise(true),
            ScooterCommand.SetZeroStart(true), ScooterCommand.SetSpeedLimit(25),
        )
        for (c in all) assertEquals(Ack.Rejected(BleScooterTransport.COMMANDS_DISABLED), r.transport.send(c))
        advanceTimeBy(20_000)
        assertTrue(r.written.size > 10)
        assertEquals(setOf(connectTx, heartbeatTx), r.written.toSet())
        assertEquals(1, r.written.count { it == connectTx })
        val cmds = r.gatt.writes.map { it.third[2].toInt() and 0xFF }
        assertTrue("CMD 0x32..0x39 nunca", cmds.none { it in 0x32..0x39 })
        // Nenhum quadro LOCK (0x32) com modo 1 (definir PIN) ou 3 (limpar): nem existe quadro 0x32.
        assertTrue(r.gatt.writes.none { it.third.size > 5 && (it.third[5].toInt() == 1 || it.third[5].toInt() == 3) && it.third[2].toInt() == 0x32 })
    }

    @Test fun aLostLinkAssertsNoLockStateAndOnlyAFreshMeter1DecidesAfterReconnecting() = runTest {
        val r = Rig(this)
        at(10) { r.gatt.emit(meter1Unlocked) }
        r.transport.connect()
        advanceTimeBy(1_500)
        assertEquals(false, r.seen.last().locked)
        val seenBefore = r.seen.size
        r.gatt.dropLink()
        runCurrent()
        assertEquals(ConnectFailure.LinkLost, r.transport.failure.value)
        assertEquals(ConnectionStatus.Disconnected, r.transport.connection.value)
        r.gatt.emit(meter1LockedWithPin) // tardio, de um enlace que caiu: ignorado
        val writesAtDrop = r.gatt.writes.size
        advanceTimeBy(10_000)
        assertEquals("nenhuma telemetria nova nem bloqueio deduzido", seenBefore, r.seen.size)
        assertEquals("heartbeat parou", writesAtDrop, r.gatt.writes.size)

        // Reconexão: o Meter2 sozinho não diz nada sobre o bloqueio (não herda o valor velho).
        at(100) { r.gatt.emit(meter2) }
        r.transport.connect()
        assertNull(r.seen.last().locked)
        r.gatt.emit(meter1LockedWithPin)
        runCurrent()
        assertEquals(true, r.seen.last().locked) // dado[1] = 0x30 → bit5 = bloqueado
        assertEquals(100, r.seen.last().batteryPercent)
        assertEquals(60, r.seen.last().speedLimitKmh) // 0x3c
    }

    private companion object {
        const val ADDRESS = "AA:BB:CC:DD:EE:FF"
    }
}

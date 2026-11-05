package br.dev.evaldo.meuscooter.data

import br.dev.evaldo.meuscooter.protocol.AgedTelemetry
import br.dev.evaldo.meuscooter.protocol.Ack
import br.dev.evaldo.meuscooter.protocol.Clock
import br.dev.evaldo.meuscooter.protocol.ConnectionStatus
import br.dev.evaldo.meuscooter.protocol.LockRefusal
import br.dev.evaldo.meuscooter.protocol.LockResult
import br.dev.evaldo.meuscooter.protocol.ScooterCommand
import br.dev.evaldo.meuscooter.protocol.UnconfirmedCause
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Bloqueio temporário (LOCK modo 2, sem PIN; S20) no `BleScooterTransport`, sobre `FakeGattClient` e tempo virtual.
 * NENHUM BLE real. Quadro esperado `1aa1320201022f0d1ff1` (CRC calculado fora do código; ver `TxFrameTest`). Meter1/ACKs
 * de CONNECT/heartbeat literais de `research/captures/s4-3/ix7tap.txt`; ACK/erro do LOCK como em S13 (`1aa1 32 05 00` /
 * `1aa1 32 89 00`; CRC de resposta desconhecido e não exigido). O erro 0x89 para o modo 2 é `[DESCONHECIDO]`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BleScooterTransportLockTest {
    private val connectTx = "1aa1300201016eb41ff1"
    private val heartbeatTx = "1aa110010071951ff1"
    private val lockTx = "1aa1320201022f0d1ff1"
    private val connectAck = hex("1aa130050101d5b31ff1")
    private val heartbeatAck = hex("1aa1100500be8c1ff1")
    private val lockAck = hex("1aa13205000000" + "1ff1")
    private val lockError = hex("1aa13289000000" + "1ff1")
    private val meter1Unlocked = hex("1aa1200312000000640000003c000000002323230203081f981ff1")
    private val meter1Locked = hex("1aa1200312003000640000003c000000002323230203082f8c1ff1")
    private val meter2 = hex("1aa12103120000ae1500000000170000000000160f03085d8b1ff1")

    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun str(b: List<Byte>) = b.joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    private inner class Rig(
        val scope: TestScope,
        config: BleConfig = BleConfig(txEnabled = true),
        speed: ((AgedTelemetry) -> Float?)? = { 0f },
    ) {
        val gatt = FakeGattClient(Clock { scope.currentTime })
        var deliveryGate: CompletableDeferred<Unit>? = null
        var lockFrame: ByteArray? = null
        private val deliveredGatt = object : GattClient by gatt {
            override val notifications = gatt.notifications.onEach { deliveryGate?.await() }
            override suspend fun write(service: java.util.UUID, characteristic: java.util.UUID, value: ByteArray) {
                if (value[2] == 0x32.toByte()) {
                    assertEquals(lockTx, str(value.toList())) // verificado ANTES de o transporte apagar o frame
                    lockFrame = value
                }
                gatt.write(service, characteristic, value)
            }
        }
        val transport = if (speed == null) {
            BleScooterTransport(scope.backgroundScope, Clock { scope.currentTime }, deliveredGatt, ADDRESS, config)
        } else {
            BleScooterTransport(scope.backgroundScope, Clock { scope.currentTime }, deliveredGatt, ADDRESS, config, speedKmh = speed)
        }
        var meter1 = meter1Unlocked
        var emitMeter1 = true
        var ackHeartbeat = true
        var lockReply: ByteArray? = lockAck
        var lockFlipsAfterMs: Long? = 660

        init {
            scope.backgroundScope.launch {
                delay(10)
                while (true) {
                    if (emitMeter1) gatt.emit(meter1)
                    gatt.emit(meter2)
                    delay(300)
                }
            }
            gatt.reply = { bytes ->
                when (str(bytes.toList())) {
                    connectTx -> connectAck
                    heartbeatTx -> if (ackHeartbeat) heartbeatAck else null
                    lockTx -> {
                        lockFlipsAfterMs?.let { ms -> scope.backgroundScope.launch { delay(ms); meter1 = meter1Locked } }
                        lockReply
                    }
                    else -> null
                }
            }
        }

        val written get() = gatt.writes.map { str(it.third) }
        val lockWrites get() = written.count { it.startsWith("1aa132") }
    }

    private fun TestScope.lockAsync(r: Rig): Deferred<LockResult> = async { r.transport.lock() }

    private suspend fun TestScope.connected(r: Rig, settleMs: Long = 1_490) {
        r.transport.connect()
        advanceTimeBy(settleMs)
        assertEquals(ConnectionStatus.Connected, r.transport.connection.value)
    }

    // ---------- Pré-condições: cada uma recusa SEM escrever nada ----------

    @Test fun txDisabledRefusesAndWritesNothing() = runTest {
        val r = Rig(this, BleConfig())
        connected(r)
        assertFalse(r.transport.lockAvailable)
        assertEquals(LockResult.Refused(LockRefusal.TxDisabled), r.transport.lock())
        advanceTimeBy(5_000)
        assertTrue(r.gatt.writes.isEmpty())
    }

    @Test fun lockIsAvailableOnlyWithTx() = runTest {
        assertTrue(Rig(this).transport.lockAvailable)
    }

    @Test fun notConnectedRefuses() = runTest {
        val r = Rig(this)
        assertEquals(LockResult.Refused(LockRefusal.LinkNotReady), r.transport.lock())
        assertTrue(r.gatt.writes.isEmpty())
    }

    @Test fun connectNotYetAcknowledgedRefuses() = runTest {
        val r = Rig(this)
        r.gatt.writeDelayMs = 500
        r.transport.connect()
        advanceTimeBy(100)
        assertEquals(LockResult.Refused(LockRefusal.LinkNotReady), r.transport.lock())
        assertEquals(listOf(connectTx), r.written)
    }

    @Test fun heartbeatWithoutRecentAckRefuses() = runTest {
        val r = Rig(this)
        connected(r, settleMs = 1_100)
        r.ackHeartbeat = false
        advanceTimeBy(2_000)
        assertEquals(ConnectionStatus.Connected, r.transport.connection.value)
        assertEquals(LockResult.Refused(LockRefusal.LinkNotReady), r.transport.lock())
        assertEquals(0, r.lockWrites)
    }

    @Test fun staleMeter1RefusesEvenIfMeter2IsFresh() = runTest {
        val r = Rig(this)
        connected(r)
        r.emitMeter1 = false // Meter2 segue chegando; Meter1 envelhece
        advanceTimeBy(2_100)
        assertEquals(LockResult.Refused(LockRefusal.TelemetryStale), r.transport.lock())
        assertEquals(0, r.lockWrites)
    }

    @Test fun neverSeenMeter1RefusesBeforeWriting() = runTest {
        val r = Rig(this)
        r.emitMeter1 = false
        r.transport.connect()
        advanceTimeBy(1_490)
        // Sem Meter1 o enlace nem chega a Conectado só com Meter2? Conectou: Meter2 basta. Recusa por dado velho.
        assertEquals(LockResult.Refused(LockRefusal.TelemetryStale), r.transport.lock())
        assertEquals(0, r.lockWrites)
    }

    @Test fun unknownSpeedRefuses() = runTest {
        val r = Rig(this, speed = { null })
        connected(r)
        assertEquals(LockResult.Refused(LockRefusal.SpeedUnknown), r.transport.lock())
        assertEquals(0, r.lockWrites)
    }

    @Test fun movingRefusesBothBySpeedAndByWheelMotion() = runTest {
        val r = Rig(this, speed = { 3f })
        connected(r)
        assertEquals(LockResult.Refused(LockRefusal.Moving), r.transport.lock())
        // S19: uma unidade de movimento da roda recusa mesmo com a velocidade injetada em 0.
        val r2 = Rig(this, speed = { 0f })
        connected(r2)
        r2.meter1 = hex("1aa1200312000000640100003200000000232323020308ae3b1ff1")
        advanceTimeBy(300)
        assertEquals(LockResult.Refused(LockRefusal.Moving), r2.transport.lock())
        assertEquals(0, r.lockWrites + r2.lockWrites)
    }

    // ---------- Envio e confirmação ----------

    @Test fun lockedOnlyWhenANewMeter1ShowsTheLockBitSet() = runTest {
        val r = Rig(this)
        connected(r) // t=1500
        var doneAt = -1L
        val res = async { r.transport.lock().also { doneAt = currentTime } }
        runCurrent()
        assertEquals(1, r.lockWrites)
        assertEquals(lockTx, r.written.single { it.startsWith("1aa132") })
        advanceTimeBy(800) // ACK já chegou, mas nenhum Meter1 travado: nada concluído (regra 5)
        assertFalse(res.isCompleted)
        advanceTimeBy(200)
        assertEquals(LockResult.Locked, res.await())
        assertEquals(2_410L, doneAt)
        assertEquals(1, r.lockWrites)
        assertEquals(1, r.gatt.maxWritesInFlight)
    }

    @Test fun ackAloneIsNotEnoughAndNoConfirmationAfterThreeSecondsIsUnconfirmed() = runTest {
        val r = Rig(this)
        r.lockFlipsAfterMs = null
        connected(r)
        val res = lockAsync(r)
        advanceTimeBy(2_999)
        assertFalse(res.isCompleted)
        advanceTimeBy(2)
        assertEquals(LockResult.Unconfirmed(UnconfirmedCause.Timeout), res.await())
        advanceTimeBy(10_000)
        assertEquals("sem retry", 1, r.lockWrites)
        assertEquals(ConnectionStatus.Connected, r.transport.connection.value)
    }

    @Test fun errorReplyIsRejectedAndNeverResent() = runTest {
        val r = Rig(this)
        r.lockReply = lockError
        r.lockFlipsAfterMs = null
        connected(r)
        val res = lockAsync(r)
        advanceTimeBy(500)
        assertEquals(LockResult.Rejected, res.await())
        advanceTimeBy(10_000)
        assertEquals(1, r.lockWrites)
    }

    @Test fun aLockedMeter1ReceivedBeforeTheWriteCannotConfirm() = runTest {
        val r = Rig(this)
        r.lockFlipsAfterMs = null
        connected(r)
        // Meter1 travado já no buffer (chegou antes da escrita) e retido antes do consumo: não pertence a esta tentativa.
        val gate = CompletableDeferred<Unit>()
        r.deliveryGate = gate
        r.gatt.emit(meter1Locked)
        val res = async(start = CoroutineStart.UNDISPATCHED) { r.transport.lock() }
        assertEquals(1, r.lockWrites)
        runCurrent()
        gate.complete(Unit)
        runCurrent()
        advanceTimeBy(3_001)
        assertEquals(LockResult.Unconfirmed(UnconfirmedCause.Timeout), res.await())
    }

    @Test fun aMeter1ArrivingAfterThreeSecondsDoesNotCount() = runTest {
        val r = Rig(this)
        r.lockFlipsAfterMs = 3_100
        connected(r)
        val res = lockAsync(r)
        advanceTimeBy(4_000)
        assertEquals(LockResult.Unconfirmed(UnconfirmedCause.Timeout), res.await())
    }

    @Test fun aSecondCallWhileOneIsInFlightIsRefused() = runTest {
        val r = Rig(this)
        connected(r)
        val first = lockAsync(r)
        runCurrent()
        assertEquals(LockResult.Refused(LockRefusal.Busy), r.transport.lock())
        advanceTimeBy(1_500)
        assertEquals(LockResult.Locked, first.await())
        assertEquals(1, r.lockWrites)
    }

    @Test fun aDoubleTapBeforeTheFirstWriteStartsIsRefused() = runTest {
        val r = Rig(this)
        connected(r)
        val first = async(start = CoroutineStart.UNDISPATCHED) { r.transport.lock() }
        val second = async(start = CoroutineStart.UNDISPATCHED) { r.transport.lock() }
        assertEquals(LockResult.Refused(LockRefusal.Busy), second.await())
        advanceTimeBy(1_500)
        assertEquals(LockResult.Locked, first.await())
        assertEquals(1, r.lockWrites)
    }

    @Test fun disconnectWhileWaitingEndsWithoutConfirmation() = runTest {
        val r = Rig(this)
        r.lockFlipsAfterMs = null
        connected(r)
        val res = lockAsync(r)
        advanceTimeBy(500)
        r.transport.disconnect()
        assertEquals(LockResult.Unconfirmed(UnconfirmedCause.LinkLost), res.await())
    }

    @Test fun aHangingLockWriteIsIndeterminateClosesTheGattAndNeverWritesAgain() = runTest {
        val r = Rig(this)
        connected(r)
        r.gatt.writeDelayMs = 60_000
        val res = lockAsync(r)
        advanceTimeBy(2_001)
        assertEquals(LockResult.Unconfirmed(UnconfirmedCause.WriteTimedOut), res.await())
        assertEquals(ConnectFailure.WriteTimedOut, r.transport.failure.value)
        assertEquals(ConnectionStatus.Disconnected, r.transport.connection.value)
        val count = r.gatt.writes.size
        advanceTimeBy(10_000)
        assertEquals(lockTx, r.written.last())
        assertEquals(count, r.gatt.writes.size)
    }

    @Test fun aFailedLockWriteEndsTheLinkWithoutConfirmation() = runTest {
        val r = Rig(this)
        connected(r)
        r.gatt.writeError = GattException(GattError.WriteFailed)
        assertEquals(LockResult.Unconfirmed(UnconfirmedCause.WriteFailed), r.transport.lock())
        assertEquals(ConnectFailure.Gatt(GattError.WriteFailed), r.transport.failure.value)
    }

    @Test fun theFrameGivenToGattIsWipedAfterTheWrite() = runTest {
        val r = Rig(this)
        connected(r)
        val res = lockAsync(r)
        advanceTimeBy(1_500)
        assertEquals(LockResult.Locked, res.await())
        assertTrue(checkNotNull(r.lockFrame).all { it == 0.toByte() })
    }

    // ---------- send() segue rejeitando tudo ----------

    @Test fun genericSendStillRejectsEverythingAndNeverWrites() = runTest {
        val r = Rig(this)
        connected(r)
        val before = r.gatt.writes.size
        val ack = r.transport.send(ScooterCommand.SetLocked(true))
        assertEquals(Ack.Rejected(BleScooterTransport.COMMANDS_DISABLED), ack)
        assertEquals(before, r.gatt.writes.size)
        assertEquals(0, r.lockWrites)
    }

    private companion object {
        const val ADDRESS = "AA:BB:CC:DD:EE:FF"
    }
}

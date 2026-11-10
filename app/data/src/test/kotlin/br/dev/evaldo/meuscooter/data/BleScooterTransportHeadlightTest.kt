package br.dev.evaldo.meuscooter.data

import br.dev.evaldo.meuscooter.protocol.Ack
import br.dev.evaldo.meuscooter.protocol.AgedTelemetry
import br.dev.evaldo.meuscooter.protocol.Clock
import br.dev.evaldo.meuscooter.protocol.ConnectionStatus
import br.dev.evaldo.meuscooter.protocol.HeadlightAckCodes
import br.dev.evaldo.meuscooter.protocol.HeadlightRefusal
import br.dev.evaldo.meuscooter.protocol.HeadlightResult
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
 * Farol real (S31) no `BleScooterTransport`, sobre `FakeGattClient` e tempo virtual. NENHUM BLE real.
 * TX esperado: bytes literais de `docs/research/parts/S31-farol.md`. ACK `1aa1 33 05 00 ff55 1ff1` do original.
 * Meter1 com dado[0] = 0x00 (apagado) / 0x18 (farol + traseira) / 0x08 (só traseira) / 0x10 (só farol), CRC RX não exigido.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BleScooterTransportHeadlightTest {
    private val connectTx = "1aa1300201016eb41ff1"
    private val heartbeatTx = "1aa110010071951ff1"
    private val lightOnTx = "1aa133020600fa0000000075cc1ff1"
    private val lightOffTx = "1aa1330206000000000000add81ff1"
    private val connectAck = hex("1aa130050101d5b31ff1")
    private val heartbeatAck = hex("1aa1100500be8c1ff1")
    private val lightAck = hex("1aa1330500ff551ff1")
    private val lightError = hex("1aa13389000000" + "1ff1")
    private val meterOff = hex("1aa1200312000000640000003c0000000023232302030808ac1ff1")
    private val meterOn = hex("1aa1200312180000640000003c000000002323230203088e0b1ff1")
    private val meterRearOnly = hex("1aa1200312080000640000003c000000002323230203088ace1ff1")
    private val meterOffLocked = hex("1aa1200312002000640000003c0000000023232302030811661ff1")
    private val meterOnMoving = hex("1aa1200312180000640100003c000000002323230203088e0b1ff1")
    private val meter2 = hex("1aa12103120000ae1500000000170000000000160f03085d8b1ff1")

    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun str(b: List<Byte>) = b.joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    private inner class Rig(
        val scope: TestScope,
        config: BleConfig = BleConfig(txEnabled = true),
        var meter1: ByteArray = meterOff,
        speed: (AgedTelemetry) -> Float? = { 0f },
    ) {
        val gatt = FakeGattClient(Clock { scope.currentTime })
        val lines = mutableListOf<String>()
        var deliveryGate: CompletableDeferred<Unit>? = null
        var lightFrame: ByteArray? = null
        private val deliveredGatt = object : GattClient by gatt {
            override val notifications = gatt.notifications.onEach { deliveryGate?.await() }
            override suspend fun write(service: java.util.UUID, characteristic: java.util.UUID, value: ByteArray) {
                if (value[2] == 0x33.toByte()) lightFrame = value
                gatt.write(service, characteristic, value)
            }
        }
        val transport = BleScooterTransport(
            scope.backgroundScope, Clock { scope.currentTime }, deliveredGatt, ADDRESS, config,
            speedKmh = speed, audit = TxAuditLog { lines += it.line() },
        )
        var emitMeter1 = true
        var lightReply: ByteArray? = lightAck

        /** Após o TX do farol, o Meter1 passa a [target] depois de [flipAfterMs] (null = nunca). */
        var flipAfterMs: Long? = 660
        var target: ByteArray = meterOn

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
                    heartbeatTx -> heartbeatAck
                    lightOnTx, lightOffTx -> {
                        flipAfterMs?.let { ms -> scope.backgroundScope.launch { delay(ms); meter1 = target } }
                        lightReply
                    }
                    else -> null
                }
            }
        }

        val written get() = gatt.writes.map { str(it.third) }
        val lightWrites get() = written.count { it.startsWith("1aa133") }
    }

    private suspend fun TestScope.connected(r: Rig, settleMs: Long = 1_490) {
        r.transport.connect()
        advanceTimeBy(settleMs)
        assertEquals(ConnectionStatus.Connected, r.transport.connection.value)
    }

    private fun TestScope.lightAsync(r: Rig, on: Boolean): Deferred<HeadlightResult> = async { r.transport.setHeadlight(on) }

    // ---------- Disponibilidade e pré-condições: cada recusa NÃO escreve nada ----------

    @Test fun availableOnlyWithTxAndGenericCommandsStayOff() = runTest {
        assertTrue(Rig(this).transport.headlightAvailable)
        val off = Rig(this, BleConfig())
        assertFalse(off.transport.headlightAvailable)
        assertFalse(off.transport.genericCommandsAvailable)
        connected(off)
        assertEquals(HeadlightResult.Refused(HeadlightRefusal.TxDisabled), off.transport.setHeadlight(true))
        advanceTimeBy(5_000)
        assertTrue(off.gatt.writes.isEmpty())
    }

    @Test fun notConnectedRefuses() = runTest {
        val r = Rig(this)
        assertEquals(HeadlightResult.Refused(HeadlightRefusal.LinkNotReady), r.transport.setHeadlight(true))
        assertTrue(r.gatt.writes.isEmpty())
    }

    @Test fun staleMeter1RefusesEvenIfMeter2IsFresh() = runTest {
        val r = Rig(this)
        connected(r)
        r.emitMeter1 = false
        advanceTimeBy(2_100)
        assertEquals(HeadlightResult.Refused(HeadlightRefusal.TelemetryStale), r.transport.setHeadlight(true))
        assertEquals(0, r.lightWrites)
    }

    @Test fun lockedScooterRefusesBothDirectionsAndWritesNothing() = runTest {
        val r = Rig(this, meter1 = meterOffLocked)
        connected(r)
        assertEquals(HeadlightResult.Refused(HeadlightRefusal.Locked), r.transport.setHeadlight(true))
        assertEquals(HeadlightResult.Refused(HeadlightRefusal.Locked), r.transport.setHeadlight(false))
        assertEquals(0, r.lightWrites)
    }

    @Test fun wheelMotionRefusesEvenWithTheHeadlightOn() = runTest {
        val r = Rig(this, meter1 = meterOnMoving)
        connected(r)
        assertEquals(HeadlightResult.Refused(HeadlightRefusal.Moving), r.transport.setHeadlight(false))
        assertEquals(0, r.lightWrites)
    }

    @Test fun unknownSpeedRefuses() = runTest {
        val r = Rig(this, speed = { null })
        connected(r)
        assertEquals(HeadlightResult.Refused(HeadlightRefusal.SpeedUnknown), r.transport.setHeadlight(true))
        assertEquals(0, r.lightWrites)
    }

    @Test fun pendingReceptionRefusesWithoutWriting() = runTest {
        val r = Rig(this)
        connected(r)
        val gate = CompletableDeferred<Unit>()
        r.deliveryGate = gate
        r.meter1 = meterOnMoving
        r.gatt.emit(meterOnMoving)
        runCurrent()
        val pending = async(start = CoroutineStart.UNDISPATCHED) { r.transport.setHeadlight(true) }
        runCurrent()
        assertFalse("S35: espera UM reenvio antes de recusar", pending.isCompleted)
        advanceTimeBy(150)
        runCurrent()
        assertTrue(pending.isCompleted)
        assertEquals(HeadlightResult.Refused(HeadlightRefusal.PendingReception), pending.await())
        assertEquals(0, r.lightWrites)
        gate.complete(Unit)
        runCurrent()
        assertEquals(HeadlightResult.Refused(HeadlightRefusal.Moving), r.transport.setHeadlight(true))
    }

    @Test fun anotherCommandInFlightRefusesAsBusy() = runTest {
        val r = Rig(this)
        connected(r)
        val first = lightAsync(r, true)
        runCurrent()
        assertEquals(HeadlightResult.Refused(HeadlightRefusal.Busy), r.transport.setHeadlight(false))
        advanceTimeBy(1_000)
        assertEquals(HeadlightResult.Confirmed, first.await())
        assertEquals(1, r.lightWrites)
    }

    // ---------- Envio e confirmação ----------

    @Test fun turnOnIsConfirmedOnlyByANewMeter1WithBit4Set() = runTest {
        val r = Rig(this)
        connected(r)
        val res = lightAsync(r, true)
        runCurrent()
        assertEquals(listOf(lightOnTx), r.written.filter { it.startsWith("1aa133") })
        advanceTimeBy(500) // ACK já chegou, Meter1 ainda apagado: nada concluído (regra 5)
        assertFalse(res.isCompleted)
        advanceTimeBy(500)
        assertEquals(HeadlightResult.Confirmed, res.await())
        assertEquals(1, r.lightWrites)
        assertTrue(checkNotNull(r.lightFrame).all { it == 0.toByte() })
    }

    @Test fun turnOffIsConfirmedByBit4ClearedAndSendsTheZeroFrame() = runTest {
        val r = Rig(this, meter1 = meterOn)
        r.target = meterOff
        connected(r)
        val res = lightAsync(r, false)
        advanceTimeBy(1_000)
        assertEquals(HeadlightResult.Confirmed, res.await())
        assertEquals(listOf(lightOffTx), r.written.filter { it.startsWith("1aa133") })
    }

    @Test fun rearBitAloneDoesNotConfirmTurningOn() = runTest {
        val r = Rig(this)
        r.target = meterRearOnly
        connected(r)
        val res = lightAsync(r, true)
        advanceTimeBy(3_100)
        assertEquals(HeadlightResult.Unconfirmed(UnconfirmedCause.Timeout), res.await())
        assertEquals(1, r.lightWrites)
    }

    @Test fun ackAloneIsUnconfirmedAfterThreeSecondsWithASingleWriteAndNoRetry() = runTest {
        val r = Rig(this)
        r.flipAfterMs = null
        connected(r)
        val res = lightAsync(r, true)
        advanceTimeBy(2_999)
        assertFalse(res.isCompleted)
        advanceTimeBy(2)
        assertEquals(HeadlightResult.Unconfirmed(UnconfirmedCause.Timeout), res.await())
        advanceTimeBy(10_000)
        assertEquals(1, r.lightWrites)
    }

    @Test fun aMeter1AfterTheDeadlineDoesNotConfirm() = runTest {
        val r = Rig(this)
        r.flipAfterMs = 3_400
        connected(r)
        val res = lightAsync(r, true)
        advanceTimeBy(3_100)
        assertEquals(HeadlightResult.Unconfirmed(UnconfirmedCause.Timeout), res.await())
    }

    @Test fun errorReplyIsRejectedAndNeverResent() = runTest {
        val r = Rig(this)
        r.lightReply = lightError
        r.flipAfterMs = null
        connected(r)
        val res = lightAsync(r, true)
        advanceTimeBy(500)
        assertEquals(HeadlightResult.Rejected, res.await())
        advanceTimeBy(5_000)
        assertEquals(1, r.lightWrites)
    }

    @Test fun txIsLoggedInFullWithoutMasking() = runTest {
        val r = Rig(this)
        connected(r)
        val res = lightAsync(r, true)
        advanceTimeBy(1_000)
        res.await()
        assertTrue(r.lines.any { it.contains(" TX #") && it.contains(" LIGHT ") && it.endsWith(lightOnTx) })
        assertTrue(r.lines.any { it.contains(" LIGHT requested") })
        assertTrue(r.lines.any { it.contains(" LIGHT result=Confirmed") })
    }

    // ---------- send() só atende o farol ----------

    @Test fun sendHeadlightMapsResultsAndEveryOtherCommandStaysRejected() = runTest {
        val r = Rig(this)
        connected(r)
        val before = r.gatt.writes.size
        for (c in listOf(
            ScooterCommand.SetLocked(true), ScooterCommand.SetLocked(false), ScooterCommand.SetDualMotor(true),
            ScooterCommand.SetSpeedMode(1), ScooterCommand.SetCruise(true), ScooterCommand.SetZeroStart(true),
            ScooterCommand.SetSpeedLimit(25),
        )) assertEquals(Ack.Rejected(BleScooterTransport.COMMANDS_DISABLED), r.transport.send(c))
        assertEquals(before, r.gatt.writes.size)
        val ack = async { r.transport.send(ScooterCommand.SetHeadlight(true)) }
        advanceTimeBy(1_000)
        assertEquals(Ack.Accepted, ack.await())
    }

    @Test fun sendHeadlightCarriesRefusalAndUnconfirmedAsCodes() = runTest {
        val locked = Rig(this, meter1 = meterOffLocked)
        connected(locked)
        val refused = locked.transport.send(ScooterCommand.SetHeadlight(true)) as Ack.Rejected
        assertEquals(HeadlightRefusal.Locked, HeadlightAckCodes.parseRefusal(refused.reason))

        val silent = Rig(this)
        silent.flipAfterMs = null
        connected(silent)
        val ack = async { silent.transport.send(ScooterCommand.SetHeadlight(true)) }
        advanceTimeBy(3_100)
        assertTrue(HeadlightAckCodes.isUnconfirmed((ack.await() as Ack.Rejected).reason))
    }

    private companion object {
        const val ADDRESS = "AA:BB:CC:DD:EE:FF"
    }
}

package br.dev.evaldo.meuscooter.data

import br.dev.evaldo.meuscooter.protocol.AirLockResult
import br.dev.evaldo.meuscooter.protocol.Clock
import br.dev.evaldo.meuscooter.protocol.CommandRefusal
import br.dev.evaldo.meuscooter.protocol.ConnectionStatus
import br.dev.evaldo.meuscooter.protocol.UnconfirmedCause
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** S34 / ADR 0011: Air Lock (CMD 0x41) no BLE real simulado (FakeGattClient, tempo virtual; nada vai ao patinete). */
@OptIn(ExperimentalCoroutinesApi::class)
class BleScooterTransportAirLockTest {
    private fun bytes(hex: String) = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun hex(bytes: List<Byte>) = bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    private fun rx(cmd: Int, type: Int, payload: List<Int> = emptyList()): ByteArray =
        byteArrayOf(0x1a, 0xa1.toByte(), cmd.toByte(), type.toByte(), payload.size.toByte()) +
            payload.map(Int::toByte).toByteArray() + byteArrayOf(0, 0, 0x1f, 0xf1.toByte())

    private inner class Rig(val scope: TestScope, val locked: Boolean = false, val moving: Boolean = false, txEnabled: Boolean = true) {
        val gatt = FakeGattClient(Clock { scope.currentTime })
        /** Respostas ao 0x41, em ordem; `null` = sem resposta. Esgotada = sem resposta. */
        var airLockReplies = ArrayDeque<ByteArray?>()
        val transport = BleScooterTransport(
            scope.backgroundScope, Clock { scope.currentTime }, gatt, "00:11:22:33:44:55", BleConfig(txEnabled = txEnabled),
            speedKmh = { 0f },
        )

        fun meter1() = rx(0x20, 0x03, List(18) { i -> when (i) {
            1 -> if (locked) 0x20 else 0
            3 -> 80
            4 -> if (moving) 1 else 0
            7 -> 50
            else -> 0
        } })

        init {
            scope.backgroundScope.launch {
                delay(10)
                while (true) { gatt.emit(meter1()); delay(300) }
            }
            gatt.reply = { frame ->
                when (hex(frame.toList())) {
                    "1aa1300201016eb41ff1" -> bytes("1aa130050101d5b31ff1")
                    "1aa110010071951ff1" -> bytes("1aa1100500be8c1ff1")
                    else -> if ((frame[2].toInt() and 0xff) == 0x41) airLockReplies.removeFirstOrNull() else null
                }
            }
        }

        fun frames() = gatt.writes.map { hex(it.third) }.filter { it.startsWith("1aa141") }
    }

    private suspend fun TestScope.connected(r: Rig) {
        r.transport.connect()
        advanceTimeBy(1_490)
        assertEquals(ConnectionStatus.Connected, r.transport.connection.value)
    }

    @Test fun ackIsAcceptedNotConfirmedAndSendsTheFramesOnce() = runTest {
        val r = Rig(this); connected(r)
        assertTrue(r.transport.airLockAvailable)
        r.airLockReplies += rx(0x41, 0x05)
        assertEquals(AirLockResult.Accepted, r.transport.setAirLock(false))
        r.airLockReplies += rx(0x41, 0x05)
        assertEquals(AirLockResult.Accepted, r.transport.setAirLock(true))
        val f = r.frames()
        assertEquals(2, f.size)
        assertEquals("1aa141020100b4481ff1", f[0])
        assertTrue(f[1].startsWith("1aa141020101") && f[1].endsWith("1ff1") && f[1].length == 20)
    }

    @Test fun errorResponseIsRejectedWithoutRetry() = runTest {
        val r = Rig(this); connected(r)
        r.airLockReplies += rx(0x41, 0x89)
        assertEquals(AirLockResult.Rejected, r.transport.setAirLock(true))
        assertEquals(1, r.frames().size)
    }

    @Test fun noAckResendsExactlyOnceThenIsUnconfirmed() = runTest {
        val r = Rig(this); connected(r)
        val pending = async { r.transport.setAirLock(true) }
        advanceTimeBy(1_900)
        assertEquals(1, r.frames().size)
        advanceTimeBy(300)
        assertEquals(2, r.frames().size) // reenvio unico apos ackTimeoutMs
        advanceTimeBy(2_100)
        assertEquals(AirLockResult.Unconfirmed(UnconfirmedCause.Timeout), pending.await())
        advanceTimeBy(10_000)
        assertEquals(2, r.frames().size)
    }

    @Test fun ackOnTheResendIsAccepted() = runTest {
        val r = Rig(this); connected(r)
        r.airLockReplies += null
        r.airLockReplies += rx(0x41, 0x05)
        assertEquals(AirLockResult.Accepted, r.transport.setAirLock(false))
        assertEquals(2, r.frames().size)
    }

    @Test fun lockedIsAllowedButMovingTxOffAndNoLinkAreRefusedWithoutWriting() = runTest {
        val locked = Rig(this, locked = true); connected(locked)
        locked.airLockReplies += rx(0x41, 0x05)
        assertEquals(AirLockResult.Accepted, locked.transport.setAirLock(true))

        val moving = Rig(this, moving = true); connected(moving)
        assertEquals(AirLockResult.Refused(CommandRefusal.Moving), moving.transport.setAirLock(true))
        assertEquals(0, moving.frames().size)

        val off = Rig(this, txEnabled = false)
        assertTrue(!off.transport.airLockAvailable)
        assertEquals(AirLockResult.Refused(CommandRefusal.TxDisabled), off.transport.setAirLock(true))

        val down = Rig(this)
        assertEquals(AirLockResult.Refused(CommandRefusal.LinkNotReady), down.transport.setAirLock(true))
        assertEquals(0, down.frames().size)
    }

    @Test fun motionArrivingBeforeResendAbortsSecondWriteAndRefusesMoving() = runTest {
        val r = Rig(this); connected(r)
        val pending = async { r.transport.setAirLock(true) }
        advanceTimeBy(1_900)
        assertEquals(1, r.frames().size)
        // P1-b: Chega Meter1 com movimento (d[4]=1) antes da 2ª tentativa
        r.gatt.emit(rx(0x20, 0x03, List(18) { if (it == 4) 1 else 0 }))
        advanceTimeBy(300)
        // A 2ª escrita NÃO deve sair (segurança: roda em movimento)
        assertEquals("segunda escrita não deve ocorrer se a roda começou a mover", 1, r.frames().size)
        assertEquals(AirLockResult.Refused(CommandRefusal.Moving), pending.await())
    }
}

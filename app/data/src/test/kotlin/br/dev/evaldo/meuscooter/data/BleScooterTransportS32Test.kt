package br.dev.evaldo.meuscooter.data

import br.dev.evaldo.meuscooter.protocol.Clock
import br.dev.evaldo.meuscooter.protocol.CommandRefusal
import br.dev.evaldo.meuscooter.protocol.ConnectionStatus
import br.dev.evaldo.meuscooter.protocol.ScooterSettingResult
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

/** S32: cruise (0x38) e partida do zero (0x39) no BLE real simulado (FakeGattClient, tempo virtual, nada vai ao patinete). */
@OptIn(ExperimentalCoroutinesApi::class)
class BleScooterTransportS32Test {
    private fun bytes(hex: String) = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun hex(bytes: List<Byte>) = bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    private fun rx(cmd: Int, type: Int, payload: List<Int> = emptyList()): ByteArray =
        byteArrayOf(0x1a, 0xa1.toByte(), cmd.toByte(), type.toByte(), payload.size.toByte()) +
            payload.map(Int::toByte).toByteArray() + byteArrayOf(0, 0, 0x1f, 0xf1.toByte())

    private inner class Rig(val scope: TestScope, var locked: Boolean = false, var moving: Boolean = false) {
        val gatt = FakeGattClient(Clock { scope.currentTime })
        var flags = 0x80.inv() and 0 // dado[11]: cruise 0x40, partida do zero DESABILITADA 0x80 (comeca ligada)
        var autoTelemetry = true
        val transport = BleScooterTransport(
            scope.backgroundScope, Clock { scope.currentTime }, gatt, "00:11:22:33:44:55", BleConfig(txEnabled = true),
            speedKmh = { 0f },
        )

        fun meter1() = rx(0x20, 0x03, List(18) { i -> when (i) {
            1 -> if (locked) 0x20 else 0
            3 -> 80
            4 -> if (moving) 1 else 0
            7 -> 50
            11 -> flags
            else -> 0
        } })

        init {
            scope.backgroundScope.launch {
                delay(10)
                while (true) { if (autoTelemetry) gatt.emit(meter1()); delay(300) }
            }
            gatt.reply = { frame ->
                when (hex(frame.toList())) {
                    "1aa1300201016eb41ff1" -> bytes("1aa130050101d5b31ff1")
                    "1aa110010071951ff1" -> bytes("1aa1100500be8c1ff1")
                    else -> {
                        val cmd = frame[2].toInt() and 0xff
                        val d0 = frame[5].toInt() and 0xff
                        when (cmd) {
                            0x38 -> flags = (flags and 0x40.inv()) or if (d0 == 5) 0x40 else 0
                            0x39 -> flags = (flags and 0x80.inv()) or if (d0 == 1) 0x80 else 0
                        }
                        scope.backgroundScope.launch { delay(150); if (autoTelemetry) gatt.emit(meter1()) }
                        rx(cmd, 0x05)
                    }
                }
            }
        }

        fun frames() = gatt.writes.map { hex(it.third) }.filter { it.startsWith("1aa1") }
    }

    private suspend fun TestScope.connected(r: Rig) {
        r.transport.connect()
        advanceTimeBy(1_490)
        assertEquals(ConnectionStatus.Connected, r.transport.connection.value)
    }

    @Test fun cruiseAndZeroStartSendCapturedFramesAndConfirmOnlyByFreshMeter1() = runTest {
        val r = Rig(this); connected(r)
        assertTrue(r.transport.cruiseAvailable && r.transport.zeroStartAvailable)
        assertEquals(ScooterSettingResult.Confirmed, r.transport.setCruise(true))
        assertEquals(ScooterSettingResult.Confirmed, r.transport.setCruise(false))
        assertEquals(ScooterSettingResult.Confirmed, r.transport.setZeroStart(false))
        assertEquals(ScooterSettingResult.Confirmed, r.transport.setZeroStart(true))
        assertEquals(
            listOf("1aa1380202050366ec1ff1", "1aa1380202000365bc1ff1", "1aa1390202010019ed1ff1", "1aa13902020000187d1ff1"),
            r.frames().filter { it.startsWith("1aa138") || it.startsWith("1aa139") },
        )
    }

    @Test fun refusesWhenLockedMovingOrFieldNeverSeenAndSendsNothing() = runTest {
        val locked = Rig(this, locked = true); connected(locked)
        assertEquals(ScooterSettingResult.Refused(CommandRefusal.Locked), locked.transport.setCruise(true))
        assertEquals(ScooterSettingResult.Refused(CommandRefusal.Locked), locked.transport.setZeroStart(false))
        assertEquals(0, locked.frames().count { it.startsWith("1aa138") || it.startsWith("1aa139") })
    }

    @Test fun movingWheelIsRefused() = runTest {
        val r = Rig(this, moving = true); connected(r)
        assertEquals(ScooterSettingResult.Refused(CommandRefusal.Moving), r.transport.setCruise(true))
        assertEquals(ScooterSettingResult.Refused(CommandRefusal.Moving), r.transport.setZeroStart(false))
        assertEquals(0, r.frames().count { it.startsWith("1aa138") || it.startsWith("1aa139") })
    }

    @Test fun ackAloneIsUnconfirmedAndNeverRetried() = runTest {
        val r = Rig(this); connected(r)
        r.gatt.reply = { frame ->
            when (hex(frame.toList())) {
                "1aa110010071951ff1" -> bytes("1aa1100500be8c1ff1")
                else -> if ((frame[2].toInt() and 0xff) == 0x38) rx(0x38, 0x05) else null
            }
        }
        val pending = async { r.transport.setCruise(true) }
        advanceTimeBy(1_000)
        assertTrue(!pending.isCompleted)
        advanceTimeBy(2_100)
        assertEquals(ScooterSettingResult.Unconfirmed(UnconfirmedCause.Timeout), pending.await())
        assertEquals(1, r.frames().count { it.startsWith("1aa138") })
    }

    @Test fun errorResponseIsRejectedWithoutRetry() = runTest {
        val r = Rig(this); connected(r)
        r.gatt.reply = { frame ->
            when (hex(frame.toList())) {
                "1aa110010071951ff1" -> bytes("1aa1100500be8c1ff1")
                else -> if ((frame[2].toInt() and 0xff) == 0x39) rx(0x39, 0x89) else null
            }
        }
        assertEquals(ScooterSettingResult.Rejected, r.transport.setZeroStart(false))
        assertEquals(1, r.frames().count { it.startsWith("1aa139") })
    }
}

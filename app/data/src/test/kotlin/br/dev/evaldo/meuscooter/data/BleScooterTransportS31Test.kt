package br.dev.evaldo.meuscooter.data

import br.dev.evaldo.meuscooter.protocol.Clock
import br.dev.evaldo.meuscooter.protocol.CommandRefusal
import br.dev.evaldo.meuscooter.protocol.ConnectionStatus
import br.dev.evaldo.meuscooter.protocol.ScooterSettingResult
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** S31 transport tests use only FakeGattClient and virtual time; literal TX values come from the approved capture spec. */
@OptIn(ExperimentalCoroutinesApi::class)
class BleScooterTransportS31Test {
    private fun bytes(hex: String) = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun hex(bytes: List<Byte>) = bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    private fun rx(cmd: Int, type: Int, payload: List<Int> = emptyList()): ByteArray =
        byteArrayOf(0x1a, 0xa1.toByte(), cmd.toByte(), type.toByte(), payload.size.toByte()) +
            payload.map(Int::toByte).toByteArray() + byteArrayOf(0, 0, 0x1f, 0xf1.toByte())

    private inner class Rig(val scope: TestScope, modes: Int = 0x0f) {
        val gatt = FakeGattClient(Clock { scope.currentTime })
        private var status = 0x00
        private var limit = 50
        private var availableModes = modes
        private var range = 20 to 50
        val transport = BleScooterTransport(
            scope.backgroundScope, Clock { scope.currentTime }, gatt, "00:11:22:33:44:55", BleConfig(txEnabled = true),
            speedKmh = { 0f },
        )

        private fun meter1() = rx(0x20, 0x03, List(18) { i -> when (i) {
            1 -> 0
            3 -> 80
            7 -> limit
            10 -> status
            else -> 0
        } })

        init {
            scope.backgroundScope.launch {
                delay(10)
                while (true) {
                    gatt.emit(meter1())
                    gatt.emit(rx(0x21, 0x03, List(18) { i -> if (i == 15) availableModes else 0 }))
                    delay(300)
                }
            }
            gatt.reply = { frame ->
                when (hex(frame.toList())) {
                    "1aa1300201016eb41ff1" -> bytes("1aa130050101d5b31ff1")
                    "1aa110010071951ff1" -> bytes("1aa1100500be8c1ff1")
                    "1aa1620100d18e1ff1" -> rx(0x62, 0x04, listOf(range.first, range.second))
                    else -> {
                        when (frame[2].toInt() and 0xff) {
                            0x3c -> status = (status and 0x08.inv()) or ((frame[5].toInt() and 1) shl 3)
                            0x35 -> status = (status and 0x07.inv()) or (frame[5].toInt() and 0x07)
                            0x36 -> limit = frame[5].toInt() and 0xff
                            0x3b -> {
                                status = (status and 0x10.inv()) or ((frame[5].toInt() and 1) shl 4)
                                range = if (status and 0x10 != 0) 15 to 60 else 20 to 50
                            }
                        }
                        scope.backgroundScope.launch { delay(150); gatt.emit(meter1()) }
                        val cmd = frame[2].toInt() and 0xff
                        rx(cmd, 0x05)
                    }
                }
            }
        }

        fun commandFrames() = gatt.writes.map { hex(it.third) }.filter { it.startsWith("1aa1") }
    }

    private suspend fun TestScope.connected(r: Rig) {
        r.transport.connect()
        advanceTimeBy(1_490)
        assertEquals(ConnectionStatus.Connected, r.transport.connection.value)
    }

    @Test fun allFourSettingsUseCapturedFramesAndFreshMeter1Confirmation() = runTest {
        val r = Rig(this)
        connected(r)
        assertEquals(ScooterSettingResult.Confirmed, r.transport.setUnit(mph = true))
        assertEquals(ScooterSettingResult.Confirmed, r.transport.setDriveMode(2))
        val unitBack = r.transport.setUnit(mph = false)
        assertEquals("writes=${r.commandFrames()}", ScooterSettingResult.Confirmed, unitBack)
        assertEquals(ScooterSettingResult.Confirmed, r.transport.setSpeedLimit(39))
        assertEquals(ScooterSettingResult.Confirmed, r.transport.setDrive(twoMotors = true))
        assertEquals(2, r.commandFrames().count { it.startsWith("1aa162") })
        assertEquals(ScooterSettingResult.Confirmed, r.transport.setSpeedLimit(60))
        // Drive confirmation invalidates and re-queries the limit range (15..60 in dual motor mode).
        assertEquals(ScooterSettingResult.Confirmed, r.transport.setSpeedLimit(60))
        assertTrue(r.commandFrames().containsAll(listOf(
            "1aa13c0201016de41ff1", "1aa13502020200091c1ff1", "1aa136020127efe61ff1", "1aa13b0201016c901ff1",
        )))
    }

    @Test fun unavailableModeAndInvalidValuesRefuseWithoutSettingFrame() = runTest {
        val r = Rig(this, modes = 0b0011)
        connected(r)
        val before = r.commandFrames().size
        assertEquals(ScooterSettingResult.Refused(CommandRefusal.ModeUnavailable), r.transport.setDriveMode(2))
        assertEquals(ScooterSettingResult.Refused(CommandRefusal.InvalidValue), r.transport.setDriveMode(4))
        assertEquals(ScooterSettingResult.Refused(CommandRefusal.InvalidValue), r.transport.setSpeedLimit(61))
        assertEquals(before, r.commandFrames().size)
    }

    @Test fun speedLimitRequiresKnownKmhAndCurrentQueriedRange() = runTest {
        val r = Rig(this)
        connected(r)
        val before = r.commandFrames().size
        assertEquals(ScooterSettingResult.Confirmed, r.transport.setSpeedLimit(39))
        assertTrue(r.commandFrames().drop(before).contains("1aa1620100d18e1ff1"))
        val limitWrites = r.commandFrames().count { it.startsWith("1aa136") }
        assertEquals(ScooterSettingResult.Refused(CommandRefusal.OutOfRange), r.transport.setSpeedLimit(51))
        assertEquals(limitWrites, r.commandFrames().count { it.startsWith("1aa136") })
        assertEquals(ScooterSettingResult.Confirmed, r.transport.setUnit(mph = true))
        val queries = r.commandFrames().count { it.startsWith("1aa162") }
        assertEquals(ScooterSettingResult.Refused(CommandRefusal.UnitMustBeKmh), r.transport.setSpeedLimit(39))
        assertEquals(queries, r.commandFrames().count { it.startsWith("1aa162") })
    }

    @Test fun acknowledgementAloneDoesNotConfirmAndTheCommandIsNeverRetried() = runTest {
        val r = Rig(this)
        connected(r)
        r.gatt.reply = { frame ->
            val cmd = frame[2].toInt() and 0xff
            when (cmd) {
                0x3c -> rx(cmd, 0x05)
                else -> when (hex(frame.toList())) {
                    "1aa110010071951ff1" -> bytes("1aa1100500be8c1ff1")
                    else -> null
                }
            }
        }
        val pending = async { r.transport.setUnit(mph = true) }
        advanceTimeBy(1_000)
        assertTrue("ACK alone must not complete the setting", !pending.isCompleted)
        advanceTimeBy(2_100)
        assertEquals(ScooterSettingResult.Unconfirmed(br.dev.evaldo.meuscooter.protocol.UnconfirmedCause.Timeout), pending.await())
        assertEquals(1, r.commandFrames().count { it.startsWith("1aa13c") })
    }

    @Test fun commandSpecificErrorReturnsRejectedWithoutRetry() = runTest {
        val r = Rig(this)
        connected(r)
        r.gatt.reply = { frame ->
            val cmd = frame[2].toInt() and 0xff
            when (cmd) {
                0x3c -> rx(cmd, 0x89)
                else -> when (hex(frame.toList())) {
                    "1aa110010071951ff1" -> bytes("1aa1100500be8c1ff1")
                    else -> null
                }
            }
        }
        assertEquals(ScooterSettingResult.Rejected, r.transport.setUnit(mph = true))
        assertEquals(1, r.commandFrames().count { it.startsWith("1aa13c") })
    }
}

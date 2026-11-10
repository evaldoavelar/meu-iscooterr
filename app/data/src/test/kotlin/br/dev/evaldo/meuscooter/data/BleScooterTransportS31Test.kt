package br.dev.evaldo.meuscooter.data

import br.dev.evaldo.meuscooter.protocol.Clock
import br.dev.evaldo.meuscooter.protocol.CommandRefusal
import br.dev.evaldo.meuscooter.protocol.ConnectionStatus
import br.dev.evaldo.meuscooter.protocol.ScooterSettingResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
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
        var autoTelemetry = true
        /** S35: retém as notificações a partir da escrita da consulta 0x62 (a escrita já aconteceu, a resposta não chega). */
        var gateOnQuery = false
        private var deliveryGate: CompletableDeferred<Unit>? = null
        val auditLines = mutableListOf<String>()
        private val deliveredGatt = object : GattClient by gatt {
            override val notifications = gatt.notifications.onEach { deliveryGate?.await() }
            override suspend fun write(service: java.util.UUID, characteristic: java.util.UUID, value: ByteArray) {
                if (gateOnQuery && hex(value.toList()).startsWith("1aa162")) deliveryGate = CompletableDeferred()
                gatt.write(service, characteristic, value)
            }
        }
        val transport = BleScooterTransport(
            scope.backgroundScope, Clock { scope.currentTime }, deliveredGatt, "00:11:22:33:44:55", BleConfig(txEnabled = true),
            speedKmh = { 0f }, audit = TxAuditLog { auditLines += it.line() },
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
                    if (autoTelemetry) {
                        gatt.emit(meter1())
                        gatt.emit(rx(0x21, 0x03, List(18) { i -> if (i == 15) availableModes else 0 }))
                    }
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
                        scope.backgroundScope.launch { delay(150); if (autoTelemetry) gatt.emit(meter1()) }
                        val cmd = frame[2].toInt() and 0xff
                        rx(cmd, 0x05)
                    }
                }
            }
        }

        fun commandFrames() = gatt.writes.map { hex(it.third) }.filter { it.startsWith("1aa1") }
        fun emitMeter1() {
            gatt.emit(meter1())
            gatt.emit(rx(0x21, 0x03, List(18) { i -> if (i == 15) availableModes else 0 }))
        }
        fun setMode2() { status = 2 }
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
        assertEquals(2, r.commandFrames().count { it.startsWith("1aa162") }) // 1st by the limit, 2nd right after Drive
        assertEquals(ScooterSettingResult.Confirmed, r.transport.setSpeedLimit(60))
        assertEquals(2, r.commandFrames().count { it.startsWith("1aa162") })
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

    @Test fun confirmedDriveQueriesRangeOnceSoTheNextLimitIsSentImmediately() = runTest {
        val r = Rig(this)
        connected(r)
        assertEquals(0, r.commandFrames().count { it.startsWith("1aa162") })
        assertEquals(ScooterSettingResult.Confirmed, r.transport.setDrive(twoMotors = true))
        assertEquals(1, r.commandFrames().count { it.startsWith("1aa162") })
        assertEquals(ScooterSettingResult.Confirmed, r.transport.setSpeedLimit(60))
        assertEquals(1, r.commandFrames().count { it.startsWith("1aa162") })
        assertEquals(1, r.commandFrames().count { it.startsWith("1aa136") })
    }

    @Test fun unconfirmedDriveDoesNotQueryRangeAfterwards() = runTest {
        val r = Rig(this)
        connected(r)
        r.autoTelemetry = false
        val drive = async { r.transport.setDrive(twoMotors = true) }
        advanceTimeBy(3_100)
        assertEquals(ScooterSettingResult.Unconfirmed(br.dev.evaldo.meuscooter.protocol.UnconfirmedCause.Timeout), drive.await())
        assertEquals(0, r.commandFrames().count { it.startsWith("1aa162") })
    }

    @Test fun unconfirmedDriveInvalidatesRangeBeforeNextSpeedLimitAttempt() = runTest {
        val r = Rig(this)
        connected(r)
        assertEquals(ScooterSettingResult.Confirmed, r.transport.setDrive(twoMotors = true))
        assertEquals(ScooterSettingResult.Confirmed, r.transport.setSpeedLimit(60))

        r.autoTelemetry = false
        val drive = async { r.transport.setDrive(twoMotors = false) }
        advanceTimeBy(3_100)
        assertEquals(ScooterSettingResult.Unconfirmed(br.dev.evaldo.meuscooter.protocol.UnconfirmedCause.Timeout), drive.await())

        r.autoTelemetry = true
        advanceTimeBy(700) // periodic Meter1/Meter2 resume; fresh Meter1 now reports one motor (valid limit range 20..50).
        val queriesBefore = r.commandFrames().count { it.startsWith("1aa162") }
        assertEquals(ScooterSettingResult.Refused(CommandRefusal.OutOfRange), r.transport.setSpeedLimit(60))
        assertEquals(queriesBefore + 1, r.commandFrames().count { it.startsWith("1aa162") })
        assertEquals(1, r.commandFrames().count { it.startsWith("1aa136") })
    }

    @Test fun delayedRejectionForPreviousCommandDoesNotRejectCurrentSetting() = runTest {
        val r = Rig(this)
        connected(r)
        r.gatt.reply = { frame ->
            when (frame[2].toInt() and 0xff) {
                0x3c -> null // Unit times out; its rejection arrives during the next command.
                else -> when (hex(frame.toList())) {
                    "1aa110010071951ff1" -> bytes("1aa1100500be8c1ff1")
                    else -> {
                        val cmd = frame[2].toInt() and 0xff
                        if (cmd == 0x35) {
                            r.setMode2()
                            backgroundScope.launch { delay(150); r.emitMeter1() }
                            rx(cmd, 0x05)
                        } else null
                    }
                }
            }
        }
        val unit = async { r.transport.setUnit(mph = true) }
        advanceTimeBy(3_100)
        assertEquals(ScooterSettingResult.Unconfirmed(br.dev.evaldo.meuscooter.protocol.UnconfirmedCause.Timeout), unit.await())

        val mode = async { r.transport.setDriveMode(2) }
        runCurrent()
        r.gatt.emit(rx(0x3c, 0x89))
        runCurrent()
        assertTrue("rejection for Unit must not finish DriveMode", !mode.isCompleted)
        advanceTimeBy(300)
        assertEquals(ScooterSettingResult.Confirmed, mode.await())
    }

    @Test fun speedLimitQueryAlreadyWrittenThenPendingReceptionIsNotRetried() = runTest {
        val r = Rig(this)
        connected(r)
        assertEquals(0, r.commandFrames().count { it.startsWith("1aa162") })
        r.gateOnQuery = true
        val res = async { r.transport.setSpeedLimit(39) }
        assertEquals(ScooterSettingResult.Refused(CommandRefusal.PendingReception), res.await())
        assertEquals("a consulta 0x62 foi escrita", 1, r.commandFrames().count { it.startsWith("1aa162") })
        assertEquals(0, r.commandFrames().count { it.startsWith("1aa136") })
        assertTrue("houve escrita: sem reenvio S35", r.auditLines.none { it.contains("reenvio") })
    }
}

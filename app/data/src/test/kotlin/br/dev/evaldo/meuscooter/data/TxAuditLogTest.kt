package br.dev.evaldo.meuscooter.data

import br.dev.evaldo.meuscooter.protocol.Clock
import br.dev.evaldo.meuscooter.protocol.ConnectionStatus
import br.dev.evaldo.meuscooter.protocol.Pin
import br.dev.evaldo.meuscooter.protocol.TxCommand
import br.dev.evaldo.meuscooter.protocol.TxFrameBuilder
import br.dev.evaldo.meuscooter.protocol.UnlockResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
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

/** Log de auditoria (S29) sobre `FakeGattClient`. Quadros literais de `BleScooterTransportUnlockTest` (s4-3/S13). */
@OptIn(ExperimentalCoroutinesApi::class)
class TxAuditLogTest {
    private val connectTx = "1aa1300201016eb41ff1"
    private val heartbeatTx = "1aa110010071951ff1"
    private val connectAck = "1aa130050101d5b31ff1"
    private val heartbeatAck = "1aa1100500be8c1ff1"
    private val meter1Locked = "1aa1200312003000640000003c000000002323230203082f8c1ff1"
    private val meter2 = "1aa12103120000ae1500000000170000000000160f03085d8b1ff1"
    private val pinRejected = "1aa13289000000" + "1ff1"

    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun str(b: ByteArray) = b.joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    private class Rig(val scope: TestScope, hex: (String) -> ByteArray, str: (ByteArray) -> String,
                      connectTx: String, heartbeatTx: String, connectAck: String, heartbeatAck: String,
                      meter1: String, meter2: String, var unlockReply: String? = null) {
        val lines = mutableListOf<String>()
        val gatt = FakeGattClient(Clock { scope.currentTime })
        val transport = BleScooterTransport(
            scope.backgroundScope, Clock { scope.currentTime }, gatt, "AA:BB:CC:DD:EE:FF",
            BleConfig(txEnabled = true), speedKmh = { 0f },
            audit = TxAuditLog { lines += it.line() },
        )

        init {
            scope.backgroundScope.launch {
                delay(10)
                while (true) {
                    gatt.emit(hex(meter1))
                    gatt.emit(hex(meter2))
                    delay(300)
                }
            }
            gatt.reply = { bytes ->
                when (val s = str(bytes)) {
                    connectTx -> hex(connectAck)
                    heartbeatTx -> hex(heartbeatAck)
                    else -> if (s.startsWith("1aa132")) unlockReply?.let(hex) else null
                }
            }
        }
    }

    private fun TestScope.rig(unlockReply: String? = null) =
        Rig(this, ::hex, ::str, connectTx, heartbeatTx, connectAck, heartbeatAck, meter1Locked, meter2, unlockReply)

    private suspend fun TestScope.connected(r: Rig) {
        r.transport.connect()
        advanceTimeBy(1_490)
        assertEquals(ConnectionStatus.Connected, r.transport.connection.value)
    }

    @Test fun eventLineFormatIsFixed() {
        assertEquals("t=42 UNLOCK sent", TxAuditEvent(42, "UNLOCK", "sent").line())
        assertEquals("t=7 UNLOCK result=PinRejected", TxAuditEvent(7, "UNLOCK", "result=PinRejected").line())
    }

    @Test fun nullLogIsTheDefaultAndSwallowsEvents() {
        TxAuditLog.None.record(TxAuditEvent(0, "X", "y"))
    }

    @Test fun unlockFrameMasksPinAndCrcForAnyPin() {
        for (pin in listOf("1234", "9876")) {
            val frame = TxFrameBuilder.build(TxCommand.Unlock(Pin.of(pin)))
            val full = str(frame)
            val masked = TxAuditFormat.txFrame(TxCommand.Unlock(Pin.of(pin)), frame)
            assertEquals("1aa132020500" + "XX".repeat(6) + "1ff1", masked)
            assertFalse(masked.contains(full.substring(12, 20))) // bytes do PIN
            assertFalse(masked.contains(full.substring(20, 24))) // CRC
        }
    }

    @Test fun connectHeartbeatLockAndRxAppearCompleteInHex() = runTest {
        val r = rig()
        connected(r)
        advanceTimeBy(1_000) // mais heartbeats
        val text = r.lines.joinToString("\n")
        assertTrue(text, r.lines.any { it.contains("TX #1 CONNECT $connectTx") })
        assertTrue(r.lines.any { it.contains("TX #2 HEARTBEAT $heartbeatTx") })
        // cada heartbeat individualmente
        assertTrue(r.lines.count { it.contains("HEARTBEAT $heartbeatTx") } >= 2)
        assertTrue(r.lines.any { it.contains(" RX #") && it.endsWith(meter1Locked) })
        assertTrue(r.lines.any { it.contains(" RX #") && it.endsWith(connectAck) })
        assertTrue(r.lines.any { it.contains(" RX #") && it.endsWith(heartbeatAck) })
        assertTrue(r.lines.any { it == "t=0 SESSION start attempt=1 ep=1" })
        assertTrue(r.lines.any { it.contains("GATT connected") })
        assertTrue(r.lines.any { it.contains("GATT mtu=") })
        assertTrue(r.lines.any { it.contains("GATT cccd enabled") })
        assertTrue(r.lines.any { it.contains("SESSION connected") })
        assertTrue(r.lines.all { it.startsWith("t=") })
        r.transport.disconnect()
        assertTrue(r.lines.any { it.contains("SESSION end reason=UserDisconnect") })
    }

    @Test fun lockWriteIsLoggedInFullWhenThePreconditionsAllowIt() = runTest {
        // Meter1 desbloqueado: LOCK é permitido e o quadro completo vai ao log.
        val r = Rig(this, ::hex, ::str, connectTx, heartbeatTx, connectAck, heartbeatAck,
            "1aa1200312000000640000003c000000002323230203081f981ff1", meter2)
        connected(r)
        val lockHex = str(TxFrameBuilder.build(TxCommand.Lock))
        val lock = async { r.transport.lock() }
        runCurrent()
        assertTrue(r.lines.joinToString("\n"), r.lines.any { it.contains(" LOCK ") && it.endsWith(lockHex) })
        assertTrue(r.lines.any { it.endsWith("LOCK sent") })
        advanceTimeBy(4_000)
        lock.await()
        assertTrue(r.lines.any { it.contains("LOCK result=Unconfirmed:Timeout") })
    }

    @Test fun neitherPinNorUnlockCrcAppearsInAnyLine() = runTest {
        for (pin in listOf("1234", "9876")) {
            val r = rig(unlockReply = null)
            connected(r)
            val full = str(TxFrameBuilder.build(TxCommand.Unlock(Pin.of(pin))))
            val pinHex = full.substring(12, 20)
            val crcHex = full.substring(20, 24)
            val res = async { r.transport.unlock(Pin.of(pin)) }
            runCurrent()
            advanceTimeBy(4_000)
            val result = res.await()
            r.transport.disconnect()
            assertTrue(result.toString(), result is UnlockResult.Unconfirmed)
            val all = r.lines.joinToString("\n")
            assertTrue(all, r.lines.any { it.contains(" TX #") && it.contains(" UNLOCK 1aa132020500" + "XX".repeat(6) + "1ff1") })
            assertTrue(r.lines.any { it.endsWith("UNLOCK sent") })
            assertTrue(r.lines.any { it.endsWith("UNLOCK result=Unconfirmed:Timeout") })
            assertFalse(all.contains(pinHex))
            assertFalse(all.contains(crcHex))
            assertFalse(all.contains(full))
            assertFalse(all.contains(pin))
        }
    }

    @Test fun pinRejectionAndRefusalAreLogged() = runTest {
        val r = rig(unlockReply = pinRejected)
        connected(r)
        val res = async { r.transport.unlock(Pin.of("1234")) }
        runCurrent()
        advanceTimeBy(500)
        assertEquals(UnlockResult.PinRejected, res.await())
        assertTrue(r.lines.any { it.contains(" RX #") && it.endsWith(pinRejected) })
        assertTrue(r.lines.any { it.endsWith("UNLOCK result=PinRejected") })
        r.transport.disconnect()
        val refused = r.transport.unlock(Pin.of("1234"))
        assertEquals("UNLOCK result=Refused:" + (refused as UnlockResult.Refused).reason, r.lines.last().substringAfter(" "))
    }

    @Test fun reconnectionIncrementsTheAttemptNumber() = runTest {
        val r = rig()
        connected(r)
        r.transport.disconnect()
        r.transport.connect()
        advanceTimeBy(100)
        assertTrue(r.lines.any { it.contains("SESSION start attempt=2") })
    }
}

package br.dev.evaldo.meuscooter.data

import br.dev.evaldo.meuscooter.protocol.Clock
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.withTimeoutOrNull
import br.dev.evaldo.meuscooter.protocol.RxError
import br.dev.evaldo.meuscooter.protocol.RxFrameParser
import br.dev.evaldo.meuscooter.protocol.RxParseResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FakeGattClientTest {
    @Test fun uuidsFollowTheBluetoothBaseUuid() {
        // Base padrão do Bluetooth 0000xxxx-0000-1000-8000-00805F9B34FB; xxxx = FF60/FF61/FF62 (T3-T7 `[ESTÁTICO]`, log S2 `[CONFIRMADO]` para FF61/FF62).
        assertEquals("0000ff60-0000-1000-8000-00805f9b34fb", ScooterGatt.SERVICE.toString())
        assertEquals("0000ff61-0000-1000-8000-00805f9b34fb", ScooterGatt.RX.toString())
        assertEquals("0000ff62-0000-1000-8000-00805f9b34fb", ScooterGatt.WR.toString())
    }

    @Test fun connectDiscoverSubscribeAndReceive() = runTest {
        val g = FakeGattClient(Clock { currentTime })
        assertEquals(GattState.Disconnected, g.state.value)
        g.connect("AA:BB")
        assertEquals(GattState.Connected, g.state.value)
        val services = g.discoverServices()
        assertEquals(setOf(ScooterGatt.RX, ScooterGatt.WR), services.single { it.uuid == ScooterGatt.SERVICE }.characteristics)
        val got = async { g.notifications.toList() }
        advanceTimeBy(1)
        g.enableNotifications(ScooterGatt.SERVICE, ScooterGatt.RX)
        g.emit(byteArrayOf(1, 2))
        g.close()
        assertEquals(listOf("connect:AA:BB", "discover", "subscribe:${ScooterGatt.SERVICE}/${ScooterGatt.RX}", "close"), g.calls)
        got.cancel()
    }

    @Test fun operationsHonourConfiguredLatency() = runTest {
        val g = FakeGattClient(Clock { currentTime }).apply { connectDelayMs = 300 }
        val d = async { g.connect("X") }
        advanceTimeBy(299)
        assertFalse(d.isCompleted)
        advanceTimeBy(2)
        assertTrue(d.isCompleted)
    }

    @Test fun dropLinkFailsThePendingOperationImmediately() = runTest {
        val g = FakeGattClient(Clock { currentTime }).apply { discoverDelayMs = 10_000 }
        g.connect("X")
        val d = async { runCatching { g.discoverServices() } }
        advanceTimeBy(100)
        g.dropLink()
        advanceTimeBy(1)
        val e = d.await().exceptionOrNull()
        assertEquals(GattError.Disconnected, (e as GattException).error)
        assertEquals(GattState.Disconnected, g.state.value)
    }

    @Test fun configuredErrorsAreThrown() = runTest {
        val g = FakeGattClient(Clock { currentTime }).apply { connectError = GattException(GattError.ConnectFailed) }
        try { g.connect("X"); fail() } catch (e: GattException) { assertEquals(GattError.ConnectFailed, e.error) }
    }

    @Test fun closeIsIdempotentAndWritesAreRecorded() = runTest {
        val g = FakeGattClient(Clock { currentTime })
        g.connect("X")
        g.write(ScooterGatt.SERVICE, ScooterGatt.WR, byteArrayOf(9))
        assertEquals(1, g.writes.size)
        g.close(); g.close()
        assertEquals(GattState.Disconnected, g.state.value)
        assertEquals(2, g.closeCalls)
    }

    @Test fun writeTakesItsLatencyRecordsTheBytesAndEmitsTheConfiguredReply() = runTest {
        val g = FakeGattClient(Clock { currentTime }).apply { writeDelayMs = 40; reply = { if (it[2] == 0x10.toByte()) byteArrayOf(7, 7) else null } }
        g.connect("X")
        val got = mutableListOf<ByteArray>()
        val job = launch { g.notifications.collect { got += it.value } }
        advanceTimeBy(1)
        val w = async { g.write(ScooterGatt.SERVICE, ScooterGatt.WR, byteArrayOf(0x1a, 0xa1.toByte(), 0x10)) }
        advanceTimeBy(39)
        assertFalse(w.isCompleted)
        advanceTimeBy(2)
        assertTrue(w.isCompleted)
        g.write(ScooterGatt.SERVICE, ScooterGatt.WR, byteArrayOf(0x1a, 0xa1.toByte(), 0x30)) // sem resposta configurada
        advanceTimeBy(100)
        job.cancel()
        assertEquals(listOf(listOf<Byte>(7, 7)), got.map { it.toList() })
        assertEquals(2, g.writes.size)
        assertEquals(1, g.maxWritesInFlight)
    }

    @Test fun writeFailsWithTheConfiguredErrorOrWhenTheLinkDrops() = runTest {
        val g = FakeGattClient(Clock { currentTime }).apply { writeError = GattException(GattError.WriteFailed) }
        g.connect("X")
        try { g.write(ScooterGatt.SERVICE, ScooterGatt.WR, byteArrayOf(1)); fail() } catch (e: GattException) { assertEquals(GattError.WriteFailed, e.error) }
        g.writeError = null
        g.writeDelayMs = 5_000
        val d = async { runCatching { g.write(ScooterGatt.SERVICE, ScooterGatt.WR, byteArrayOf(2)) } }
        advanceTimeBy(100)
        g.dropLink()
        advanceTimeBy(1)
        assertEquals(GattError.Disconnected, (d.await().exceptionOrNull() as GattException).error)
    }

    @Test fun anAbandonedWriteStaysInFlightUntilTheGattIsClosed() = runTest {
        val g = FakeGattClient(Clock { currentTime }).apply { writeDelayMs = 60_000 }
        g.connect("X")
        val w = async { withTimeoutOrNull(1_000) { g.write(ScooterGatt.SERVICE, ScooterGatt.WR, byteArrayOf(1)) } }
        advanceTimeBy(1_001)
        assertTrue(w.isCompleted) // o chamador desistiu…
        assertEquals(1, g.writesInFlight) // …mas o stack ainda tem a escrita
        g.writeDelayMs = 0
        g.write(ScooterGatt.SERVICE, ScooterGatt.WR, byteArrayOf(2))
        assertEquals(2, g.maxWritesInFlight) // escrever agora sobrepõe a pendente
        g.close()
        assertEquals(0, g.writesInFlight)
    }

    @Test fun overlappingWritesAreCounted() = runTest {
        val g = FakeGattClient(Clock { currentTime }).apply { writeDelayMs = 100 }
        g.connect("X")
        val a = async { g.write(ScooterGatt.SERVICE, ScooterGatt.WR, byteArrayOf(1)) }
        val b = async { g.write(ScooterGatt.SERVICE, ScooterGatt.WR, byteArrayOf(2)) }
        a.await(); b.await()
        assertEquals(2, g.maxWritesInFlight)
    }

    // Vetor real da S2 (Meter1, 27 B). Com ATT_MTU 23 o payload da notificação é 23-3 = 20 B (spec do ATT).
    private val meter1 = "1aa120031200000064000000320000000023232302030853f81ff1".chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test fun defaultMtuTruncatesA27ByteFrameTo20Bytes() = runTest {
        val g = FakeGattClient(Clock { currentTime })
        g.simulateTruncation = true
        val got = mutableListOf<ByteArray>()
        val job = launch { g.notifications.collect { got += it.value } }
        advanceTimeBy(1)
        g.emit(meter1)
        advanceTimeBy(1)
        job.cancel()
        assertEquals(23, g.mtu)
        assertEquals(20, got.single().size)
        assertEquals(RxParseResult.Invalid(RxError.Truncated), RxFrameParser.parse(got.single()))
    }

    @Test fun requestMtuReturnsWhatThePeerGrantsAndFramesFitAfterwards() = runTest {
        val g = FakeGattClient(Clock { currentTime })
        g.simulateTruncation = true
        g.peerMaxMtu = 100
        assertEquals(100, g.requestMtu(247))
        assertEquals(100, g.mtu)
        val got = mutableListOf<ByteArray>()
        val job = launch { g.notifications.collect { got += it.value } }
        advanceTimeBy(1)
        g.emit(meter1)
        advanceTimeBy(1)
        job.cancel()
        assertEquals(27, got.single().size)
        assertEquals(20, FakeGattClient(Clock { currentTime }).also { it.peerMaxMtu = 20 }.requestMtu(20)) // pedido menor que o teto: vale o pedido
    }
}

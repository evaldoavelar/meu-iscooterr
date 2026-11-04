package br.dev.evaldo.meuscooter.data

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
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
        val g = FakeGattClient()
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
        val g = FakeGattClient().apply { connectDelayMs = 300 }
        val d = async { g.connect("X") }
        advanceTimeBy(299)
        assertFalse(d.isCompleted)
        advanceTimeBy(2)
        assertTrue(d.isCompleted)
    }

    @Test fun dropLinkFailsThePendingOperationImmediately() = runTest {
        val g = FakeGattClient().apply { discoverDelayMs = 10_000 }
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
        val g = FakeGattClient().apply { connectError = GattException(GattError.ConnectFailed) }
        try { g.connect("X"); fail() } catch (e: GattException) { assertEquals(GattError.ConnectFailed, e.error) }
    }

    @Test fun closeIsIdempotentAndWritesAreRecorded() = runTest {
        val g = FakeGattClient()
        g.connect("X")
        g.write(ScooterGatt.SERVICE, ScooterGatt.WR, byteArrayOf(9))
        assertEquals(1, g.writes.size)
        g.close(); g.close()
        assertEquals(GattState.Disconnected, g.state.value)
        assertEquals(2, g.closeCalls)
    }
}

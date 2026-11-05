package br.dev.evaldo.meuscooter.ui

import br.dev.evaldo.meuscooter.data.BleScooterTransport
import br.dev.evaldo.meuscooter.data.FakeGattClient
import br.dev.evaldo.meuscooter.domain.Availability
import br.dev.evaldo.meuscooter.domain.DisabledReason
import br.dev.evaldo.meuscooter.domain.HomeReducer
import br.dev.evaldo.meuscooter.domain.HomeState
import br.dev.evaldo.meuscooter.protocol.Clock
import br.dev.evaldo.meuscooter.protocol.ConnectionStatus
import br.dev.evaldo.meuscooter.protocol.ScooterCommand
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** S19: expectativas de movimento vêm da captura passiva, sem converter a escala hipotética para km/h. */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeUiMapperWheelMotionTest {
    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test fun s19RealFramesDisableStationaryActionsWithoutDisplayingUnconfirmedKmh() = runTest {
        val gatt = FakeGattClient(Clock { currentTime })
        val transport = BleScooterTransport(backgroundScope, Clock { currentTime }, gatt, "AA:BB:CC:DD:EE:FF")
        backgroundScope.launch { transport.connect() }
        advanceTimeBy(50); runCurrent()
        val mapper = HomeUiMapper(transport.speedModes.options(), null) { "14:32" }
        // ix7tap-speed.txt:6, :561, :544, :950, :1063, :6 (volta a parar).
        val frames = listOf(
            "1aa120031200000064000000320000000023232302030853f81ff1" to false,
            "1aa120031200000064c60000320000000023232302030858b11ff1" to true,
            "1aa1200312000000642d000032000000002323230203083f941ff1" to true,
            "1aa1200312000000642500003200000000232323020308de4b1ff1" to true,
            "1aa1200312000000640100003200000000232323020308ae3b1ff1" to true,
            "1aa120031200000064000000320000000023232302030853f81ff1" to false,
        )
        for ((frame, moving) in frames) {
            gatt.emit(hex(frame))
            gatt.emit(hex("1aa121031200004a1500000000180000000000240f0308ec4f1ff1"))
            runCurrent()
            val aged = transport.aged.value!!
            val state = HomeState(ConnectionStatus.Connected, aged.telemetry, currentTime,
                meter1AtMs = aged.meter1AtMs, meter2AtMs = aged.meter2AtMs)
            val ui = mapper.map(state)
            assertEquals(moving, aged.telemetry.wheelMoving)
            assertEquals(if (moving) "--" else "0 km/h", ui.speed)
            val stationaryControls = listOf(ui.motors.control, ui.mode.control, ui.cruise.control, ui.zeroStart.control, ui.limit.control)
            assertTrue(stationaryControls.all { it.enabled == !moving })
            assertEquals(!moving, ui.mainButton.enabled)
            assertTrue(ui.headlight.control.enabled)
            if (moving) {
                assertNull(aged.telemetry.speedKmh)
                assertEquals("Pare o patinete para alterar", ui.notice)
                val disabled = Availability.Disabled(DisabledReason.Moving)
                assertEquals(disabled, HomeReducer.availability(state, ScooterCommand.SetLocked(false)))
                assertEquals(disabled, HomeReducer.pinUnlockAvailability(state.copy(pinUnlock = true)))
                assertFalse(mapper.map(state.copy(pinUnlock = true)).mainButton.enabled)
            }
        }
    }
}

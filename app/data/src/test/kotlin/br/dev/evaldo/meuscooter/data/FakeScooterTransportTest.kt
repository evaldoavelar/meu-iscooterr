package br.dev.evaldo.meuscooter.data

import br.dev.evaldo.meuscooter.protocol.Ack
import br.dev.evaldo.meuscooter.protocol.Clock
import br.dev.evaldo.meuscooter.protocol.ConnectionStatus
import br.dev.evaldo.meuscooter.protocol.CommandRefusal
import br.dev.evaldo.meuscooter.protocol.ScooterSettingResult
import br.dev.evaldo.meuscooter.protocol.ScooterCommand
import br.dev.evaldo.meuscooter.protocol.Telemetry
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fonte dos tempos: docs/research/parts/S2-captura-resultados.md [CONFIRMADO]:
 * ACK mediana ~51 ms, estado confirmado ~419 ms (p95 ~681 ms), telemetria ~300 ms.
 * Tempo virtual (kotlinx-coroutines-test): nenhum sleep real.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FakeScooterTransportTest {
    private fun TestScope.newFake(config: FakeConfig = FakeConfig()) =
        FakeScooterTransport(backgroundScope, Clock { currentTime }, config)

    private fun TestScope.collect(fake: FakeScooterTransport): MutableList<Telemetry> {
        val seen = mutableListOf<Telemetry>()
        backgroundScope.launch { fake.telemetry.collect { seen += it } }
        runCurrent()
        return seen
    }

    private suspend fun TestScope.connected(fake: FakeScooterTransport) {
        fake.connect()
        runCurrent()
    }

    @Test
    fun connectGoesThroughConnectingThenConnected() = runTest {
        val fake = newFake()
        assertEquals(ConnectionStatus.Disconnected, fake.connection.value)
        backgroundScope.launch { fake.connect() }
        runCurrent()
        assertEquals(ConnectionStatus.Connecting, fake.connection.value)
        advanceTimeBy(FakeConfig().connectDelayMs - 1); runCurrent()
        assertEquals(ConnectionStatus.Connecting, fake.connection.value)
        advanceTimeBy(1); runCurrent()
        assertEquals(ConnectionStatus.Connected, fake.connection.value)
    }

    @Test
    fun ackArrivesAfter50msAndStateAfter420ms() = runTest {
        val fake = newFake(); val seen = collect(fake); connected(fake)
        val t0 = currentTime
        var ackAt = -1L; var ack: Ack? = null
        backgroundScope.launch { ack = fake.send(ScooterCommand.SetLocked(false)); ackAt = currentTime }
        runCurrent(); advanceTimeBy(49); runCurrent()
        assertEquals(null, ack)
        advanceTimeBy(1); runCurrent()
        assertEquals(Ack.Accepted, ack); assertEquals(t0 + 50, ackAt)
        // antes de 420 ms nenhuma telemetria mostra o novo estado
        advanceTimeBy(369); runCurrent()
        assertTrue(seen.none { it.receivedAtMs >= t0 && it.locked == false })
        advanceTimeBy(1); runCurrent()
        val confirmed = seen.last { it.locked == false }
        assertEquals(t0 + 420, confirmed.receivedAtMs)
    }

    @Test
    fun injectedRejectionKeepsStateUnchanged() = runTest {
        val fake = newFake(); val seen = collect(fake); connected(fake)
        fake.failNextCommand("recusado")
        var ack: Ack? = null
        backgroundScope.launch { ack = fake.send(ScooterCommand.SetLocked(false)) }
        advanceTimeBy(60); runCurrent()
        assertEquals(Ack.Rejected("recusado"), ack)
        advanceTimeBy(2_000); runCurrent()
        assertTrue(seen.all { it.locked == true })
    }

    @Test
    fun ackedButNeverReflectedWhenInjected() = runTest {
        val fake = newFake(); val seen = collect(fake); connected(fake)
        fake.ignoreNextCommand()
        var ack: Ack? = null
        backgroundScope.launch { ack = fake.send(ScooterCommand.SetLocked(false)) }
        advanceTimeBy(5_000); runCurrent()
        assertEquals(Ack.Accepted, ack)
        assertTrue(seen.all { it.locked == true })
    }

    @Test
    fun frozenTelemetryStopsAndResumes() = runTest {
        val fake = newFake(); val seen = collect(fake); connected(fake)
        advanceTimeBy(1_000); runCurrent()
        fake.freezeTelemetry(true)
        val before = seen.size
        advanceTimeBy(5_000); runCurrent()
        assertEquals(before, seen.size)
        fake.freezeTelemetry(false)
        advanceTimeBy(FakeConfig().telemetryPeriodMs); runCurrent()
        assertTrue(seen.size > before)
    }

    @Test
    fun movingSimulationShowsSpeed() = runTest {
        val fake = newFake(); val seen = collect(fake); connected(fake)
        fake.setSpeed(12f)
        advanceTimeBy(FakeConfig().telemetryPeriodMs); runCurrent()
        assertEquals(12f, seen.last().speedKmh)
    }

    @Test
    fun scooterSpeedFollowsS44ContractUpTo51_1() = runTest {
        val fake = newFake(); val seen = collect(fake); connected(fake)
        fake.setSpeed(50f)
        advanceTimeBy(FakeConfig().telemetryPeriodMs); runCurrent()
        assertEquals(50f, seen.last().scooterSpeedKmh)   // S44: 50 km/h do cavalete é exibido
        fake.setSpeed(51.2f)
        advanceTimeBy(FakeConfig().telemetryPeriodMs); runCurrent()
        assertEquals(null, seen.last().scooterSpeedKmh)  // d[8]>=2 = desconhecida
    }

    @Test
    fun everyCommandUpdatesItsOwnField() = runTest {
        val fake = newFake(); val seen = collect(fake); connected(fake)
        val cases = listOf<Pair<ScooterCommand, (Telemetry) -> Boolean>>(
            ScooterCommand.SetLocked(false) to { it.locked == false },
            ScooterCommand.SetHeadlight(true) to { it.headlightOn == true },
            ScooterCommand.SetDualMotor(true) to { it.dualMotor == true },
            ScooterCommand.SetSpeedMode(3) to { it.speedModeRaw == 3 },
            ScooterCommand.SetCruise(true) to { it.cruiseOn == true },
            ScooterCommand.SetZeroStart(true) to { it.zeroStartOn == true },
            ScooterCommand.SetSpeedLimit(35) to { it.speedLimitKmh == 35 },
        )
        cases.forEach { (cmd, check) ->
            backgroundScope.launch { fake.send(cmd) }
            advanceTimeBy(500); runCurrent()
            assertTrue("$cmd", check(seen.last()))
        }
    }

    @Test fun s31SettingsKeepFakeStateAndValidationBehavior() = runTest {
        val fake = newFake(); connected(fake)
        fake.send(ScooterCommand.SetLocked(false))
        advanceTimeBy(FakeConfig().stateDelayMs); runCurrent()
        assertEquals(ScooterSettingResult.Confirmed, fake.setUnit(mph = true))
        assertEquals(ScooterSettingResult.Refused(CommandRefusal.UnitMustBeKmh), fake.setSpeedLimit(39))
        assertEquals(ScooterSettingResult.Confirmed, fake.setUnit(mph = false))
        assertEquals(ScooterSettingResult.Confirmed, fake.setDriveMode(3))
        assertEquals(ScooterSettingResult.Refused(CommandRefusal.InvalidValue), fake.setDriveMode(4))
        assertEquals(ScooterSettingResult.Confirmed, fake.setDrive(twoMotors = true))
        assertEquals(ScooterSettingResult.Confirmed, fake.setSpeedLimit(55))
    }

    @Test fun s32CruiseAndZeroStartUseTheNarrowApi() = runTest {
        val fake = newFake(); val seen = collect(fake); connected(fake)
        fake.send(ScooterCommand.SetLocked(false))
        advanceTimeBy(FakeConfig().stateDelayMs); runCurrent()
        assertEquals(ScooterSettingResult.Confirmed, fake.setCruise(true))
        advanceTimeBy(FakeConfig().telemetryPeriodMs); runCurrent()
        assertEquals(true, seen.last().cruiseOn)
        assertEquals(ScooterSettingResult.Confirmed, fake.setZeroStart(false))
        advanceTimeBy(FakeConfig().telemetryPeriodMs); runCurrent()
        assertEquals(false, seen.last().zeroStartOn)
        assertEquals(ScooterSettingResult.Confirmed, fake.setZeroStart(true))
        advanceTimeBy(FakeConfig().telemetryPeriodMs); runCurrent()
        assertEquals(true, seen.last().zeroStartOn)
    }

    @Test
    fun disconnectStopsTelemetryAndSendIsRejected() = runTest {
        val fake = newFake(); val seen = collect(fake); connected(fake)
        fake.disconnect(); runCurrent()
        assertEquals(ConnectionStatus.Disconnected, fake.connection.value)
        val before = seen.size
        advanceTimeBy(3_000); runCurrent()
        assertEquals(before, seen.size)
        var ack: Ack? = null
        backgroundScope.launch { ack = fake.send(ScooterCommand.SetHeadlight(true)) }
        runCurrent()
        assertEquals(Ack.Rejected("not-connected"), ack)
    }

    @Test
    fun speedModeCatalogHasFourProvisionalOptions() {
        val fake = FakeScooterTransport(kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined), Clock { 0 })
        val opts = fake.speedModes.options()
        assertEquals(listOf(0, 1, 2, 3), opts.map { it.raw })
        assertEquals(4, opts.map { it.label }.toSet().size)
        assertNotNull(FakeScooterTransport.PROVISIONAL_MODE_NOTE)
        assertFalse(FakeScooterTransport.PROVISIONAL_MODE_NOTE.isBlank())
    }

    /** Campos extras `[HIPÓTESE]` (só Fake): 54 V, 38/40 °C; corrente = 0,4 A por km/h (25 km/h → 10 A). */
    @Test
    fun telemetryCarriesSimulatedPowerFieldsAndCurrentFollowsSpeed() = runTest {
        val fake = newFake()
        val seen = collect(fake)
        connected(fake)
        advanceTimeBy(FakeConfig().connectDelayMs + 1); runCurrent()
        val idle = seen.last()
        assertEquals(54f, idle.voltageV!!, 0.001f)
        assertEquals(0f, idle.currentA!!, 0.001f)
        assertEquals(38, idle.motor1TempC)
        assertEquals(40, idle.motor2TempC)
        fake.setSpeed(25f)
        advanceTimeBy(FakeConfig().telemetryPeriodMs + 1); runCurrent()
        assertEquals(10f, seen.last().currentA!!, 0.001f)
    }

    /** S34: Air Lock simulado aceita (sem exigir desbloqueio), recusa desconectado/em movimento e reflete falha injetada. */
    @Test
    fun airLockIsAcceptedRefusedWhenDownOrMovingAndFailsOnInjection() = runTest {
        val fake = newFake()
        assertEquals(br.dev.evaldo.meuscooter.protocol.AirLockResult.Refused(CommandRefusal.LinkNotReady), fake.setAirLock(true))
        connected(fake); advanceTimeBy(FakeConfig().connectDelayMs + 1); runCurrent()
        assertEquals(br.dev.evaldo.meuscooter.protocol.AirLockResult.Accepted, fake.setAirLock(true)) // o simulado nasce bloqueado
        assertEquals(listOf(true), fake.airLockSent)
        fake.failNextCommand("x")
        assertEquals(br.dev.evaldo.meuscooter.protocol.AirLockResult.Rejected, fake.setAirLock(false))
        fake.ignoreNextCommand()
        assertEquals(
            br.dev.evaldo.meuscooter.protocol.AirLockResult.Unconfirmed(br.dev.evaldo.meuscooter.protocol.UnconfirmedCause.Timeout),
            fake.setAirLock(false),
        )
        fake.setSpeed(10f)
        assertEquals(br.dev.evaldo.meuscooter.protocol.AirLockResult.Refused(CommandRefusal.Moving), fake.setAirLock(false))
        assertEquals(listOf(true), fake.airLockSent)
    }
}

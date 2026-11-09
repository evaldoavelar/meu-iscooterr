package br.dev.evaldo.meuscooter.domain

import br.dev.evaldo.meuscooter.data.FakeScooterTransport
import br.dev.evaldo.meuscooter.protocol.Ack
import br.dev.evaldo.meuscooter.protocol.AgedTelemetry
import br.dev.evaldo.meuscooter.protocol.Clock
import br.dev.evaldo.meuscooter.protocol.CommandRefusal
import br.dev.evaldo.meuscooter.protocol.ConnectionStatus
import br.dev.evaldo.meuscooter.protocol.Control
import br.dev.evaldo.meuscooter.protocol.ScooterCommand
import br.dev.evaldo.meuscooter.protocol.ScooterSettingResult
import br.dev.evaldo.meuscooter.protocol.ScooterTransport
import br.dev.evaldo.meuscooter.protocol.Telemetry
import br.dev.evaldo.meuscooter.protocol.UnconfirmedCause
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S31 (ADR 0009): unidade, modo, limite e tracao com o BLE real (`genericCommandsAvailable = false`). Mesmo padrao do
 * farol: so o resultado `Confirmed` do transporte conclui, a pendencia nao expira por Tick, um comando por vez.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeSettingsTransportTest {
    private val t = Telemetry(
        receivedAtMs = 1_000, speedKmh = 0f, locked = false, dualMotor = false, speedUnitMph = false,
        availableModes = 0b0111, speedModeRaw = 1, speedLimitKmh = 25, headlightOn = false,
    )
    private val real = HomeState(
        connection = ConnectionStatus.Connected, telemetry = t, nowMs = 1_000, meter1AtMs = 1_000, meter2AtMs = 1_000,
        commandsAvailable = false, headlightAvailable = true,
        unitAvailable = true, driveModeAvailable = true, speedLimitAvailable = true, driveAvailable = true,
    )
    private val unit = ScooterCommand.SetSpeedUnit(true)
    private val mode = ScooterCommand.SetSpeedMode(2)
    private val limit = ScooterCommand.SetSpeedLimit(30)
    private val drive = ScooterCommand.SetDualMotor(true)
    private val all = listOf(unit, mode, limit, drive)

    private fun HomeState.request(c: ScooterCommand) = HomeReducer.reduce(this, HomeEvent.UserRequested(c, nowMs)).state
    private fun disabled(s: HomeState, c: ScooterCommand, reason: DisabledReason) {
        assertEquals("$c", Availability.Disabled(reason), HomeReducer.availability(s, c))
        val r = HomeReducer.reduce(s, HomeEvent.UserRequested(c, s.nowMs))
        assertTrue(r.effects.isEmpty()); assertTrue(r.state.pending.isEmpty())
    }

    @Test fun `os quatro habilitam desbloqueado parado fresco e sem outro comando`() {
        all.forEach { assertEquals("$it", Availability.Enabled, HomeReducer.availability(real, it)) }
    }

    @Test fun `recusam bloqueado em movimento velocidade desconhecida dados velhos e desconectado`() {
        all.forEach { c ->
            disabled(real.copy(telemetry = t.copy(locked = true)), c, DisabledReason.Locked)
            disabled(real.copy(telemetry = t.copy(locked = null)), c, DisabledReason.Locked)
            disabled(real.copy(telemetry = t.copy(speedKmh = 3f)), c, DisabledReason.Moving)
            disabled(real.copy(telemetry = t.copy(wheelMoving = true)), c, DisabledReason.Moving)
            disabled(real.copy(telemetry = t.copy(speedKmh = null)), c, DisabledReason.SpeedUnknown)
            disabled(real.copy(nowMs = 9_000), c, DisabledReason.TelemetryStale)
            disabled(real.copy(meter1AtMs = null), c, DisabledReason.TelemetryStale)
            disabled(real.copy(connection = ConnectionStatus.Disconnected), c, DisabledReason.NotConnected)
        }
    }

    @Test fun `modo so os da mascara e sem mascara desabilitado`() {
        assertEquals(Availability.Enabled, HomeReducer.availability(real, ScooterCommand.SetSpeedMode(2)))
        disabled(real, ScooterCommand.SetSpeedMode(3), DisabledReason.ModeUnavailable)
        disabled(real, ScooterCommand.SetSpeedMode(4), DisabledReason.ModeUnavailable)
        disabled(real.copy(telemetry = t.copy(availableModes = null)), mode, DisabledReason.ModeUnavailable)
    }

    @Test fun `limite so em km por hora e dentro da faixa do numero de motores`() {
        disabled(real.copy(telemetry = t.copy(speedUnitMph = true)), limit, DisabledReason.UnitMustBeKmh)
        disabled(real.copy(telemetry = t.copy(speedUnitMph = null)), limit, DisabledReason.UnitUnknown)
        disabled(real.copy(telemetry = t.copy(dualMotor = null)), limit, DisabledReason.RangeUnknown)
        assertEquals(Availability.Enabled, HomeReducer.availability(real, ScooterCommand.SetSpeedLimit(20)))
        assertEquals(Availability.Enabled, HomeReducer.availability(real, ScooterCommand.SetSpeedLimit(50)))
        disabled(real, ScooterCommand.SetSpeedLimit(19), DisabledReason.LimitOutOfRange)
        disabled(real, ScooterCommand.SetSpeedLimit(55), DisabledReason.LimitOutOfRange)
        val two = real.copy(telemetry = t.copy(dualMotor = true))
        assertEquals(Availability.Enabled, HomeReducer.availability(two, ScooterCommand.SetSpeedLimit(15)))
        assertEquals(Availability.Enabled, HomeReducer.availability(two, ScooterCommand.SetSpeedLimit(60)))
        disabled(two, ScooterCommand.SetSpeedLimit(61), DisabledReason.LimitOutOfRange)
        assertEquals(15..60, HomeRules.limitRange(true)); assertEquals(20..50, HomeRules.limitRange(false)); assertEquals(null, HomeRules.limitRange(null))
    }

    @Test fun `unidade nunca fica indisponivel por estar em mph (volta para km por hora)`() {
        assertEquals(Availability.Enabled, HomeReducer.availability(real.copy(telemetry = t.copy(speedUnitMph = true)), ScooterCommand.SetSpeedUnit(false)))
    }

    @Test fun `tracao e unidade so pelo transporte mesmo com comandos genericos, modo e limite seguem o fluxo antigo`() {
        val gen = real.copy(commandsAvailable = true)
        assertTrue(gen.sendsViaTransportApi(unit)); assertTrue(gen.sendsViaTransportApi(ScooterCommand.SetSpeedUnit(false)))
        assertTrue(!gen.sendsViaTransportApi(mode)); assertTrue(!gen.sendsViaTransportApi(limit)); assertTrue(!gen.sendsViaTransportApi(drive))
        assertTrue(real.sendsViaTransportApi(mode)); assertTrue(real.sendsViaTransportApi(limit)); assertTrue(real.sendsViaTransportApi(drive))
        assertTrue(!real.sendsViaTransportApi(ScooterCommand.SetCruise(true)))
    }

    @Test fun `transporte sem a capacidade mantem o comando desabilitado`() {
        disabled(real.copy(driveModeAvailable = false), mode, DisabledReason.CommandsUnavailable)
        disabled(real.copy(speedLimitAvailable = false), limit, DisabledReason.CommandsUnavailable)
        disabled(real.copy(driveAvailable = false), drive, DisabledReason.CommandsUnavailable)
        disabled(real, ScooterCommand.SetCruise(true), DisabledReason.CommandsUnavailable)
        disabled(real, ScooterCommand.SetZeroStart(true), DisabledReason.CommandsUnavailable)
    }

    // ---- S32: cruise e partida do zero ----
    private val s32 = real.copy(cruiseAvailable = true, zeroStartAvailable = true, telemetry = t.copy(cruiseOn = false, zeroStartOn = true))
    private val s32Cmds = listOf(ScooterCommand.SetCruise(true), ScooterCommand.SetZeroStart(false))

    @Test fun `S32 cruise e partida do zero seguem a mesma porta dos outros e exigem o campo conhecido`() {
        s32Cmds.forEach { assertEquals("$it", Availability.Enabled, HomeReducer.availability(s32, it)) }
        s32Cmds.forEach { c ->
            disabled(s32.copy(telemetry = s32.telemetry!!.copy(locked = true)), c, DisabledReason.Locked)
            disabled(s32.copy(telemetry = s32.telemetry!!.copy(speedKmh = 3f)), c, DisabledReason.Moving)
            disabled(s32.copy(telemetry = s32.telemetry!!.copy(wheelMoving = true)), c, DisabledReason.Moving)
            disabled(s32.copy(nowMs = 9_000), c, DisabledReason.TelemetryStale)
            disabled(s32.copy(connection = ConnectionStatus.Disconnected), c, DisabledReason.NotConnected)
        }
        disabled(s32.copy(telemetry = s32.telemetry!!.copy(cruiseOn = null)), s32Cmds[0], DisabledReason.StateUnknown)
        disabled(s32.copy(telemetry = s32.telemetry!!.copy(zeroStartOn = null)), s32Cmds[1], DisabledReason.StateUnknown)
        disabled(real.copy(cruiseAvailable = false), s32Cmds[0], DisabledReason.CommandsUnavailable)
    }

    @Test fun `S32 so concluem pelo transporte e excluem os demais`() {
        s32Cmds.forEach { c ->
            val s = s32.request(c)
            assertTrue(s.transportConfirmed(c))
            val reflected = HomeReducer.reduce(s, HomeEvent.TelemetryReceived(s32.telemetry!!.copy(receivedAtMs = 1_500, cruiseOn = true, zeroStartOn = false))).state
            assertTrue(c.control in reflected.pending)
            assertEquals(Availability.Disabled(DisabledReason.Pending), HomeReducer.availability(s, unit))
            assertEquals(Availability.Disabled(DisabledReason.Pending), HomeReducer.availability(s, ScooterCommand.SetHeadlight(true)))
        }
        assertEquals(Availability.Disabled(DisabledReason.Pending), HomeReducer.availability(s32.request(unit), s32Cmds[0]))
    }

    @Test fun `S32 com comandos genericos segue o fluxo antigo`() {
        val gen = s32.copy(commandsAvailable = true)
        s32Cmds.forEach { assertTrue(!gen.sendsViaTransportApi(it)); assertTrue(s32.sendsViaTransportApi(it)) }
    }

    // ---- exclusao mutua nos dois sentidos ----
    @Test fun `um pendente desabilita todos os outros, farol, Lock e Unlock`() {
        all.forEach { first ->
            val s = real.request(first).copy(lockMode2 = true, pinUnlock = true)
            assertTrue(s.pending.containsKey(first.control))
            (all - first).forEach { other -> assertEquals(Availability.Disabled(DisabledReason.Pending), HomeReducer.availability(s, other)) }
            assertEquals(Availability.Disabled(DisabledReason.Pending), HomeReducer.availability(s, ScooterCommand.SetHeadlight(true)))
            assertEquals(Availability.Disabled(DisabledReason.Pending), HomeReducer.lockAvailability(s))
            assertEquals(Availability.Disabled(DisabledReason.Pending), HomeReducer.pinUnlockAvailability(s.copy(telemetry = t.copy(locked = true))))
        }
    }

    @Test fun `farol, Lock e Unlock pendentes desabilitam os quatro`() {
        val light = real.request(ScooterCommand.SetHeadlight(true))
        all.forEach { assertEquals(Availability.Disabled(DisabledReason.Pending), HomeReducer.availability(light, it)) }
        all.forEach { assertEquals(Availability.Disabled(DisabledReason.Pending), HomeReducer.availability(real.copy(lock = LockStatus.Waiting(900)), it)) }
        all.forEach { assertEquals(Availability.Disabled(DisabledReason.Pending), HomeReducer.availability(real.copy(unlock = UnlockStatus.Waiting(900)), it)) }
    }

    // ---- conclusao so pelo transporte ----
    @Test fun `telemetria acumulada igual ao pedido nao confirma e Accepted conclui`() {
        all.forEach { c ->
            val s = real.request(c)
            val reflected = t.copy(receivedAtMs = 1_500, speedUnitMph = true, speedModeRaw = 2, speedLimitKmh = 30, dualMotor = true)
            val r = HomeReducer.reduce(s, HomeEvent.TelemetryReceived(reflected)).state
            assertTrue("$c", c.control in r.pending); assertTrue(c.control !in r.outcomes)
            val done = HomeReducer.reduce(r, HomeEvent.AckReceived(c.control, Ack.Accepted, 2_000)).state
            assertTrue(c.control !in done.pending)
            assertEquals(Outcome.Confirmed(2_000, c), done.outcomes[c.control])
        }
    }

    @Test fun `pendencia nao expira por Tick, falha por Rejected e por perda de conexao`() {
        all.forEach { c ->
            val s = real.request(c)
            val ticked = HomeReducer.reduce(s, HomeEvent.Tick(1_000 + HomeRules.PENDING_TIMEOUT_MS * 5)).state
            assertTrue(c.control in ticked.pending); assertTrue(c.control !in ticked.outcomes)
            val rej = HomeReducer.reduce(ticked, HomeEvent.AckReceived(c.control, Ack.Rejected(SettingAckCodes.REJECTED), 9_000)).state
            assertTrue(c.control !in rej.pending); assertTrue(rej.outcomes[c.control] is Outcome.Failed)
            val lost = HomeReducer.reduce(s, HomeEvent.ConnectionChanged(ConnectionStatus.Disconnected, 2_000)).state
            assertTrue(lost.pending.isEmpty()); assertTrue(lost.outcomes[c.control] is Outcome.Failed)
        }
    }

    @Test fun `recusa do transporte some em 6 s e codigos fazem ida e volta`() {
        val code = SettingAckCodes.refused(CommandRefusal.Moving)
        assertEquals(CommandRefusal.Moving, SettingAckCodes.parseRefusal(code))
        assertEquals(null, SettingAckCodes.parseRefusal("outra coisa"))
        assertTrue(SettingAckCodes.isUnconfirmed(SettingAckCodes.unconfirmed(UnconfirmedCause.Timeout)))
        val s = real.request(limit)
        val failed = HomeReducer.reduce(s, HomeEvent.AckReceived(Control.Limit, Ack.Rejected(code), 1_100)).state
        assertTrue(HomeReducer.reduce(failed, HomeEvent.Tick(1_100 + HomeRules.REFUSAL_VISIBLE_MS - 1)).state.outcomes.containsKey(Control.Limit))
        assertTrue(!HomeReducer.reduce(failed, HomeEvent.Tick(1_100 + HomeRules.REFUSAL_VISIBLE_MS)).state.outcomes.containsKey(Control.Limit))
    }

    // ---- controlador: roteia pela API estreita e traduz o resultado ----
    private class Rig(val calls: MutableList<String>, val controller: HomeController)

    /** Transporte com telemetria fresca, desbloqueada e parada; `generic` = `genericCommandsAvailable`. */
    private fun kotlinx.coroutines.test.TestScope.rig(generic: Boolean, result: () -> ScooterSettingResult): Rig {
        val clock = Clock { currentTime }
        val fake = FakeScooterTransport(backgroundScope, clock)
        val calls = mutableListOf<String>()
        val aged = MutableSharedFlow<AgedTelemetry>(replay = 1)
        aged.tryEmit(AgedTelemetry(t.copy(receivedAtMs = 0, cruiseOn = false, zeroStartOn = true), 0, 0))
        val transport = object : ScooterTransport by fake {
            override val connection = MutableStateFlow(ConnectionStatus.Connected)
            override val agedTelemetry = aged
            override val genericCommandsAvailable get() = generic
            override suspend fun send(command: ScooterCommand): Ack { calls += "send:$command"; return Ack.Accepted }
            override suspend fun setUnit(mph: Boolean): ScooterSettingResult { calls += "unit:$mph"; return result() }
            override suspend fun setDriveMode(mode: Int): ScooterSettingResult { calls += "mode:$mode"; return result() }
            override suspend fun setSpeedLimit(kmh: Int): ScooterSettingResult { calls += "limit:$kmh"; return result() }
            override suspend fun setDrive(twoMotors: Boolean): ScooterSettingResult { calls += "drive:$twoMotors"; return result() }
            override val cruiseAvailable get() = true
            override val zeroStartAvailable get() = true
            override suspend fun setCruise(on: Boolean): ScooterSettingResult { calls += "cruise:$on"; return result() }
            override suspend fun setZeroStart(on: Boolean): ScooterSettingResult { calls += "zero:$on"; return result() }
        }
        val controller = HomeController(transport, backgroundScope, clock)
        return Rig(calls, controller)
    }

    @Test fun `resultado do transporte vira Ack com codigos especificos e nunca repete`() = runTest {
        val results = ArrayDeque(listOf<ScooterSettingResult>(
            ScooterSettingResult.Refused(CommandRefusal.Moving), ScooterSettingResult.Rejected,
            ScooterSettingResult.Unconfirmed(UnconfirmedCause.Timeout), ScooterSettingResult.Confirmed,
            ScooterSettingResult.Confirmed, ScooterSettingResult.Confirmed,
        ))
        val r = rig(generic = false) { results.removeFirst() }
        runCurrent()
        val state = { r.controller.state.value }
        r.controller.request(mode); runCurrent()
        assertEquals(listOf("mode:2"), r.calls)
        assertEquals(CommandRefusal.Moving, SettingAckCodes.parseRefusal((state().outcomes[Control.Mode] as Outcome.Failed).reason))
        r.controller.request(ScooterCommand.SetSpeedMode(1)); runCurrent()
        assertEquals(SettingAckCodes.REJECTED, (state().outcomes[Control.Mode] as Outcome.Failed).reason)
        r.controller.request(ScooterCommand.SetSpeedMode(0)); runCurrent()
        assertTrue(SettingAckCodes.isUnconfirmed((state().outcomes[Control.Mode] as Outcome.Failed).reason))
        r.controller.request(unit); runCurrent()
        assertTrue(state().outcomes[Control.Unit] is Outcome.Confirmed)
        r.controller.request(limit); runCurrent()
        r.controller.request(ScooterCommand.SetDualMotor(true)); runCurrent()
        assertEquals(listOf("mode:2", "mode:1", "mode:0", "unit:true", "limit:30", "drive:true"), r.calls)   // um envio por pedido, nunca send
    }

    @Test fun `com comandos genericos modo limite e tracao seguem o fluxo antigo e a unidade vai pela API estreita`() = runTest {
        val r = rig(generic = true) { ScooterSettingResult.Confirmed }
        runCurrent()
        r.controller.request(mode); runCurrent()
        r.controller.request(unit); runCurrent()
        assertEquals(listOf("send:SetSpeedMode(raw=2)", "unit:true"), r.calls)
    }

    @Test fun `S32 cruise e partida do zero reais vao pela API estreita e traduzem o resultado`() = runTest {
        val results = ArrayDeque(listOf<ScooterSettingResult>(ScooterSettingResult.Confirmed, ScooterSettingResult.Unconfirmed(UnconfirmedCause.Timeout)))
        val r = rig(generic = false) { results.removeFirst() }
        runCurrent()
        r.controller.request(ScooterCommand.SetCruise(true)); runCurrent()
        assertTrue(r.controller.state.value.outcomes[Control.Cruise] is Outcome.Confirmed)
        r.controller.request(ScooterCommand.SetZeroStart(false)); runCurrent()
        assertTrue(SettingAckCodes.isUnconfirmed((r.controller.state.value.outcomes[Control.ZeroStart] as Outcome.Failed).reason))
        assertEquals(listOf("cruise:true", "zero:false"), r.calls)
    }
}

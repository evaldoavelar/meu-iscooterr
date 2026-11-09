package br.dev.evaldo.meuscooter.ui

import br.dev.evaldo.meuscooter.domain.HomeState
import br.dev.evaldo.meuscooter.domain.Outcome
import br.dev.evaldo.meuscooter.domain.PendingOp
import br.dev.evaldo.meuscooter.domain.SettingAckCodes
import br.dev.evaldo.meuscooter.domain.Units
import br.dev.evaldo.meuscooter.protocol.CommandRefusal
import br.dev.evaldo.meuscooter.protocol.ConnectionStatus
import br.dev.evaldo.meuscooter.protocol.Control
import br.dev.evaldo.meuscooter.protocol.ScooterCommand
import br.dev.evaldo.meuscooter.protocol.Telemetry
import br.dev.evaldo.meuscooter.protocol.UnconfirmedCause
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** S31 (ADR 0009): unidade, modo, limite e tracao na Home com o BLE real. */
class HomeSettingsUiMapperTest {
    private val mapper = HomeUiMapper(emptyList(), "iX7 Pro") { "14:32" }
    private val t = Telemetry(
        receivedAtMs = 1_000, speedKmh = 0f, locked = false, dualMotor = false, speedUnitMph = false,
        availableModes = 0b0111, speedModeRaw = 1, speedLimitKmh = 25, batteryPercent = 80, totalKm = 100f,
    )

    private fun real(tel: Telemetry = t, pending: Map<Control, PendingOp> = emptyMap(), outcomes: Map<Control, Outcome> = emptyMap(), units: Units = Units.Metric) =
        mapper.map(
            HomeState(
                ConnectionStatus.Connected, tel, 1_000, pending, outcomes, meter1AtMs = 1_000, meter2AtMs = 1_000,
                commandsAvailable = false, headlightAvailable = true,
                unitAvailable = true, driveModeAvailable = true, speedLimitAvailable = true, driveAvailable = true,
            ),
            units,
        )

    @Test fun `unidade so aparece quando o transporte a oferece e segue o Meter1`() {
        assertNull(mapper.map(HomeState(ConnectionStatus.Connected, t, 1_000, meter1AtMs = 1_000, meter2AtMs = 1_000)).unit)
        val u = real().unit!!
        assertEquals(listOf("km/h", "mph"), u.options.map { it.label })
        assertEquals(0, u.selectedRaw); assertTrue(u.control.enabled)
        assertEquals(1, real(t.copy(speedUnitMph = true)).unit!!.selectedRaw)
        assertNull(real(t.copy(speedUnitMph = null)).unit!!.selectedRaw)
    }

    @Test fun `alvo pendente aparece selecionado e todos os controles ficam desabilitados sem texto de falha`() {
        val ui = real(pending = mapOf(Control.Unit to PendingOp(ScooterCommand.SetSpeedUnit(true), 1_000)))
        assertEquals(1, ui.unit!!.selectedRaw)
        assertEquals(ControlStatus.Pending, ui.unit!!.control.status)
        listOf(ui.mode.control, ui.limit.control, ui.motors.control, ui.headlight.control).forEach { assertFalse(it.enabled) }
        assertEquals(ControlStatus.Idle, ui.limit.control.status)
    }

    @Test fun `modo mostra so os da mascara com nomes e sem mascara fica sem opcoes`() {
        val ui = real()
        assertEquals(listOf(0 to "Caminhada", 1 to "Eco", 2 to "Equilibrado"), ui.mode.options.map { it.raw to it.label })
        assertTrue(ui.mode.control.enabled)
        assertEquals(1, ui.mode.selectedRaw)
        assertTrue(real(t.copy(availableModes = null)).mode.options.isEmpty())
        assertTrue(real(t.copy(availableModes = 0x0f)).mode.options.map { it.raw } == listOf(0, 1, 2, 3))
    }

    @Test fun `limite com 1 motor vai de 20 a 50 com teto 60 e dica so com 2 motores`() {
        val l = real().limit
        assertEquals(20, l.minKmh); assertEquals(50, l.maxKmh); assertEquals(60, LimitUi.CEILING)
        assertEquals("Acima de 50 km/h só com 2 motores", l.hint)
        assertTrue(l.control.enabled)
    }

    @Test fun `limite com 2 motores vai de 15 a 60 sem dica`() {
        val l = real(t.copy(dualMotor = true)).limit
        assertEquals(15, l.minKmh); assertEquals(60, l.maxKmh); assertNull(l.hint)
    }

    @Test fun `limite desabilitado em mph com motivo, valor cru e sem faixa`() {
        val ui = real(t.copy(speedUnitMph = true, speedLimitKmh = 31))
        assertFalse(ui.limit.control.enabled)
        assertEquals("Troque a unidade para km/h para ajustar o limite", ui.limit.control.disabledReason)
        assertEquals("31 mph", ui.limit.valueText(ui.limit.kmh))
        assertEquals("", ui.limit.minText)
        assertEquals("Unidade do patinete desconhecida", real(t.copy(speedUnitMph = null)).limit.control.disabledReason)
        assertFalse(real(t.copy(dualMotor = null)).limit.control.enabled)
    }

    @Test fun `velocidade e odometro seguem a unidade do patinete sem converter (HIPOTESE de campos em mph)`() {
        val km = real(t.copy(speedKmh = 0f))
        assertEquals("0 km/h", km.speed)
        val mph = real(t.copy(speedUnitMph = true, speedKmh = 12.4f, totalKm = 1234f))
        assertEquals("12 mph", mph.speed)
        assertEquals("1.234 mi", mph.total)
        // A preferencia imperial so converte quando o patinete esta em km/h.
        assertEquals("12 mph", real(t.copy(speedUnitMph = true, speedKmh = 12.4f), units = Units.Imperial).speed)
        assertEquals("19 mph", real(t.copy(speedKmh = 30f), units = Units.Imperial).speed)
    }

    @Test fun `tracao pede confirmacao com aviso de potencia e teto de 60 so com o transporte real`() {
        val m = real().motors
        assertTrue(m.confirmMessages.getValue(1).contains("60 km/h")); assertTrue(m.confirmMessages.getValue(1).contains("potência"))
        assertNotNull(m.confirmMessages[0])
        val fake = mapper.map(HomeState(ConnectionStatus.Connected, t, 1_000, meter1AtMs = 1_000, meter2AtMs = 1_000))
        assertTrue(fake.motors.confirmMessages.isEmpty())
    }

    @Test fun `motivos de desabilitado nao falam de farol nos controles de configuracao`() {
        val locked = real(t.copy(locked = true))
        assertEquals("Desbloqueie o patinete para alterar", locked.mode.control.disabledReason)
        assertEquals("Desbloqueie o patinete para usar o farol", locked.headlight.control.disabledReason)
        assertEquals("Pare o patinete para alterar", real(t.copy(speedKmh = 4f)).unit!!.control.disabledReason)
    }

    @Test fun `falhas tem mensagem especifica por controle`() {
        fun msg(c: Control, cmd: ScooterCommand, reason: String) =
            real(outcomes = mapOf(c to Outcome.Failed(reason, 1_000, cmd))).let {
                when (c) { Control.Unit -> it.unit!!.control; Control.Mode -> it.mode.control; Control.Limit -> it.limit.control; else -> it.motors.control }
            }.message
        assertEquals("Pare o patinete para alterar o modo", msg(Control.Mode, ScooterCommand.SetSpeedMode(0), SettingAckCodes.refused(CommandRefusal.Moving)))
        assertEquals("Esse modo não está disponível neste patinete", msg(Control.Mode, ScooterCommand.SetSpeedMode(3), SettingAckCodes.refused(CommandRefusal.ModeUnavailable)))
        assertEquals("Troque a unidade para km/h para ajustar o limite", msg(Control.Limit, ScooterCommand.SetSpeedLimit(30), SettingAckCodes.refused(CommandRefusal.UnitMustBeKmh)))
        assertEquals("Valor fora da faixa do patinete (só com 2 motores acima de 50 km/h)", msg(Control.Limit, ScooterCommand.SetSpeedLimit(55), SettingAckCodes.refused(CommandRefusal.OutOfRange)))
        assertEquals("Desbloqueie o patinete para alterar a tração", msg(Control.Motors, ScooterCommand.SetDualMotor(true), SettingAckCodes.refused(CommandRefusal.Locked)))
        assertEquals("O patinete recusou a mudança de unidade", msg(Control.Unit, ScooterCommand.SetSpeedUnit(true), SettingAckCodes.REJECTED))
        assertEquals(
            "Sem confirmação do patinete — a tração pode não ter mudado. Confira o estado antes de tentar de novo",
            msg(Control.Motors, ScooterCommand.SetDualMotor(true), SettingAckCodes.unconfirmed(UnconfirmedCause.Timeout)),
        )
    }
}

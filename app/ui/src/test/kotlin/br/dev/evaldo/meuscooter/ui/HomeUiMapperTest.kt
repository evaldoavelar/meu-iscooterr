package br.dev.evaldo.meuscooter.ui

import br.dev.evaldo.meuscooter.domain.HomeState
import br.dev.evaldo.meuscooter.domain.Outcome
import br.dev.evaldo.meuscooter.domain.PendingOp
import br.dev.evaldo.meuscooter.protocol.ConnectionStatus
import br.dev.evaldo.meuscooter.protocol.Control
import br.dev.evaldo.meuscooter.protocol.ScooterCommand
import br.dev.evaldo.meuscooter.protocol.SpeedModeOption
import br.dev.evaldo.meuscooter.protocol.Telemetry
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Valores esperados vêm do FigCraft aprovado (design/home.py, linhas 35–96) e do plano da Fase 3,
 * nunca do código testado.
 */
class HomeUiMapperTest {
    private val modes = listOf(SpeedModeOption(0, "Pedestre"), SpeedModeOption(1, "Eco"), SpeedModeOption(2, "Race"), SpeedModeOption(3, "Sport"))
    private val mapper = HomeUiMapper(modes, null) { "14:32" }

    private fun tel(
        at: Long = 1_000, locked: Boolean? = false, speed: Float? = 0f,
    ) = Telemetry(receivedAtMs = at, locked = locked, speedKmh = speed, batteryPercent = 82, headlightOn = true,
        dualMotor = false, speedModeRaw = 1, cruiseOn = false, zeroStartOn = true, speedLimitKmh = 25, totalKm = 1248.4f)

    private fun connected(t: Telemetry? = tel(), now: Long = 1_000, pending: Map<Control, PendingOp> = emptyMap(),
                          outcomes: Map<Control, Outcome> = emptyMap()) =
        HomeState(ConnectionStatus.Connected, t, now, pending, outcomes)

    // ---- ciclo 1: botão principal (textos do FigCraft) ----

    @Test fun `desconectado oferece procurar patinete`() {
        val b = mapper.map(HomeState()).mainButton
        assertEquals(MainButtonKind.Search, b.kind); assertEquals("Procurar patinete", b.label); assertEquals(true, b.enabled)
    }

    @Test fun `conectando mostra Conectando desabilitado`() {
        val b = mapper.map(HomeState(connection = ConnectionStatus.Connecting)).mainButton
        assertEquals(MainButtonKind.Connecting, b.kind); assertEquals("Conectando…", b.label); assertEquals(false, b.enabled)
    }

    @Test fun `bloqueado e parado oferece Desbloquear`() {
        val b = mapper.map(connected(tel(locked = true))).mainButton
        assertEquals(MainButtonKind.Unlock, b.kind); assertEquals("Desbloquear", b.label); assertEquals(true, b.enabled)
    }

    @Test fun `desbloqueado e parado oferece Bloquear`() {
        val b = mapper.map(connected()).mainButton
        assertEquals(MainButtonKind.Lock, b.kind); assertEquals("Bloquear", b.label); assertEquals(true, b.enabled)
    }

    @Test fun `em movimento mostra Pare para bloquear desabilitado`() {
        val b = mapper.map(connected(tel(speed = 12f))).mainButton
        assertEquals(MainButtonKind.Moving, b.kind); assertEquals("Pare para bloquear", b.label); assertEquals(false, b.enabled)
    }

    @Test fun `bloqueio pendente mostra Aguardando o patinete e nao reenvia`() {
        val s = connected(pending = mapOf(Control.Lock to PendingOp(ScooterCommand.SetLocked(true), 900)))
        val b = mapper.map(s).mainButton
        assertEquals(MainButtonKind.Pending, b.kind); assertEquals("Aguardando o patinete…", b.label); assertEquals(false, b.enabled)
    }

    @Test fun `confirmado so aparece com Outcome Confirmed e mostra a hora`() {
        val s = connected(tel(locked = true), outcomes = mapOf(Control.Lock to Outcome.Confirmed(1_000)))
        val b = mapper.map(s).mainButton
        assertEquals(MainButtonKind.Confirmed, b.kind); assertEquals("Bloqueado às 14:32", b.label)
        val u = connected(tel(locked = false), outcomes = mapOf(Control.Lock to Outcome.Confirmed(1_000)))
        assertEquals("Desbloqueado às 14:32", mapper.map(u).mainButton.label)
    }

    @Test fun `falha do bloqueio oferece Tentar de novo`() {
        val s = connected(outcomes = mapOf(Control.Lock to Outcome.Failed("x", 1_000)))
        val b = mapper.map(s).mainButton
        assertEquals(MainButtonKind.Retry, b.kind); assertEquals("Tentar de novo", b.label); assertEquals(true, b.enabled)
    }

    @Test fun `timeout do bloqueio tambem oferece Tentar de novo`() {
        val s = connected(outcomes = mapOf(Control.Lock to Outcome.TimedOut(1_000)))
        assertEquals(MainButtonKind.Retry, mapper.map(s).mainButton.kind)
    }

    @Test fun `comando enviado sem confirmacao nunca vira concluido`() {
        val s = connected(pending = mapOf(Control.Lock to PendingOp(ScooterCommand.SetLocked(true), 900)))
        assertEquals(false, mapper.map(s).mainButton.label.contains("às"))
    }

    // ---- ciclo 2: habilitação dos controles e motivo sem jargão ----

    @Test fun `parado e fresco habilita todos os controles`() {
        val u = mapper.map(connected())
        listOf(u.headlight.control, u.motors.control, u.mode.control, u.cruise.control, u.zeroStart.control, u.limit.control)
            .forEach { assertEquals(true, it.enabled); assertEquals(null, it.disabledReason) }
    }

    @Test fun `em movimento desabilita tudo menos o farol`() {
        val u = mapper.map(connected(tel(speed = 15f)))
        assertEquals(true, u.headlight.control.enabled)
        listOf(u.motors.control, u.mode.control, u.cruise.control, u.zeroStart.control, u.limit.control).forEach {
            assertEquals(false, it.enabled); assertEquals("Pare o patinete para alterar", it.disabledReason)
        }
    }

    @Test fun `velocidade desconhecida desabilita com motivo proprio`() {
        val u = mapper.map(connected(tel(speed = null)))
        assertEquals(false, u.mode.control.enabled)
        assertEquals("Velocidade desconhecida — aguarde o patinete", u.mode.control.disabledReason)
    }

    @Test fun `telemetria velha desabilita e sinaliza dados desatualizados`() {
        val u = mapper.map(connected(tel(at = 1_000), now = 3_500))
        assertEquals(false, u.limit.control.enabled)
        assertEquals("Dados desatualizados — aguardando o patinete", u.limit.control.disabledReason)
        assertEquals(true, u.telemetryStale)
        assertEquals(false, mapper.map(connected(tel(at = 1_000), now = 2_000)).telemetryStale)
    }

    @Test fun `desconectado desabilita tudo inclusive o farol`() {
        val u = mapper.map(HomeState())
        assertEquals(false, u.headlight.control.enabled)
        assertEquals("Conecte ao patinete para usar", u.headlight.control.disabledReason)
    }

    @Test fun `bloqueio com telemetria velha fica desabilitado com motivo`() {
        val b = mapper.map(connected(tel(at = 1_000), now = 3_500)).mainButton
        assertEquals(false, b.enabled)
        assertEquals("Dados desatualizados — aguardando o patinete", b.disabledReason)
    }

    @Test fun `desbloquear tolera telemetria velha mas nao movimento`() {
        assertEquals(true, mapper.map(connected(tel(at = 1_000, locked = true), now = 9_000)).mainButton.enabled)
        assertEquals(false, mapper.map(connected(tel(locked = true, speed = 3f))).mainButton.enabled)
    }

    @Test fun `controle pendente fica desabilitado sem motivo textual`() {
        val s = connected(pending = mapOf(Control.Cruise to PendingOp(ScooterCommand.SetCruise(true), 900)))
        val c = mapper.map(s).cruise.control
        assertEquals(false, c.enabled); assertEquals(null, c.disabledReason); assertEquals(ControlStatus.Pending, c.status)
        assertEquals(true, mapper.map(s).headlight.control.enabled)
    }

    // ---- ciclo 3: valores exibidos, rótulos, valor otimista e mensagens ----

    @Test fun `valores principais seguem o formato do FigCraft`() {
        val u = mapper.map(connected())
        assertEquals("82%", u.battery); assertEquals("0 km/h", u.speed); assertEquals("1.248 km", u.total)
        assertEquals(25, u.limit.kmh)
    }

    @Test fun `campos desconhecidos viram traco`() {
        val u = mapper.map(connected(Telemetry(receivedAtMs = 1_000)))
        assertEquals("--", u.battery); assertEquals("--", u.speed); assertEquals("--", u.total)
    }

    @Test fun `velocidade e arredondada para inteiro`() {
        assertEquals("13 km/h", mapper.map(connected(tel(speed = 12.6f))).speed)
    }

    @Test fun `modo expoe as quatro opcoes do catalogo e o selecionado`() {
        val m = mapper.map(connected()).mode
        assertEquals(listOf("Pedestre", "Eco", "Race", "Sport"), m.options.map { it.label })
        assertEquals(1, m.selectedRaw)
    }

    @Test fun `tracao mostra 1 motor ou 2 motores`() {
        val m = mapper.map(connected()).motors
        assertEquals(listOf("1 motor", "2 motores"), m.options.map { it.label })
        assertEquals(0, m.selectedRaw)
    }

    @Test fun `alvo pendente aparece ate a confirmacao`() {
        val s = connected(pending = mapOf(Control.Headlight to PendingOp(ScooterCommand.SetHeadlight(false), 900)))
        val h = mapper.map(s).headlight
        assertEquals(false, h.checked); assertEquals(ControlStatus.Pending, h.control.status)
    }

    @Test fun `chip de conexao com e sem nome do dispositivo`() {
        val named = HomeUiMapper(modes, "iX7 Pro") { "14:32" }
        assertEquals("Conectado · iX7 Pro", named.map(connected()).connection.label)
        assertEquals(ConnectionKind.Connected, named.map(connected()).connection.kind)
        assertEquals("Conectando ao iX7 Pro…", named.map(HomeState(connection = ConnectionStatus.Connecting)).connection.label)
        assertEquals("Conectado", mapper.map(connected()).connection.label)
        assertEquals("Conectando…", mapper.map(HomeState(connection = ConnectionStatus.Connecting)).connection.label)
        assertEquals("Desconectado", mapper.map(HomeState()).connection.label)
        assertEquals(ConnectionKind.Disconnected, mapper.map(HomeState()).connection.kind)
    }

    @Test fun `chip mostra Sem resposta do patinete apos timeout ou falha do bloqueio`() {
        val s = connected(outcomes = mapOf(Control.Lock to Outcome.TimedOut(1_000)))
        assertEquals("Sem resposta do patinete", mapper.map(s).connection.label)
        assertEquals(ConnectionKind.Error, mapper.map(s).connection.kind)
    }

    @Test fun `mensagens de resultado por controle sem jargao`() {
        val failed = mapper.map(connected(outcomes = mapOf(Control.Cruise to Outcome.Failed("rejected", 1_000)))).cruise.control
        assertEquals(ControlStatus.Failed, failed.status); assertEquals("O patinete recusou o comando", failed.message)
        val lost = mapper.map(connected(outcomes = mapOf(Control.Cruise to Outcome.Failed("connection-lost", 1_000)))).cruise.control
        assertEquals("Conexão perdida", lost.message)
        val timeout = mapper.map(connected(outcomes = mapOf(Control.Mode to Outcome.TimedOut(1_000)))).mode.control
        assertEquals("Sem resposta do patinete", timeout.message)
        val ok = mapper.map(connected(outcomes = mapOf(Control.Mode to Outcome.Confirmed(1_000)))).mode.control
        assertEquals(ControlStatus.Confirmed, ok.status); assertEquals(null, ok.message)
    }

    // ---- decisão do proprietário: desconectado mantém o último estado dos controles; leituras viram "--" ----

    @Test fun `desconectado mantem ultimo estado dos controles e apaga leituras`() {
        val u = mapper.map(HomeState(ConnectionStatus.Disconnected, tel(), 1_000))
        assertEquals("--", u.battery); assertEquals("--", u.speed); assertEquals("--", u.total)
        assertEquals(true, u.headlight.checked); assertEquals(1, u.mode.selectedRaw); assertEquals(0, u.motors.selectedRaw)
        assertEquals(true, u.zeroStart.checked); assertEquals(25, u.limit.kmh)
        assertEquals(false, u.mode.control.enabled)
        assertEquals(MainButtonKind.Search, u.mainButton.kind); assertEquals(true, u.mainButton.enabled)
    }

    // ---- revisão Sonnet r1, item 2: Tentar de novo só habilitado se o reducer aceitaria o reenvio ----

    @Test fun `Tentar de novo fica desabilitado em movimento e com telemetria velha`() {
        val failed = mapOf(Control.Lock to Outcome.TimedOut(1_000))
        val moving = mapper.map(connected(tel(speed = 9f), outcomes = failed)).mainButton
        assertEquals(MainButtonKind.Retry, moving.kind); assertEquals(false, moving.enabled)
        assertEquals("Pare o patinete para alterar", moving.disabledReason)
        val stale = mapper.map(connected(tel(at = 1_000), now = 4_000, outcomes = failed)).mainButton
        assertEquals(false, stale.enabled)
        assertEquals(true, mapper.map(connected(outcomes = failed)).mainButton.enabled)
    }
}

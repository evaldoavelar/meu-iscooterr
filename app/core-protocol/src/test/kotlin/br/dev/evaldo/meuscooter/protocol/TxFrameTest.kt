package br.dev.evaldo.meuscooter.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Bytes esperados = linhas `TX`/`RX` LITERAIS de `research/captures/s4-3/ix7tap.txt` (app original, 2026-11-05;
 * `docs/research/parts/S17-4-2-tentativa1.md`), copiadas à mão; nunca calculadas pelo construtor testado.
 */
class TxFrameTest {
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    private fun bytes(hex: String) = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun rx(hex: String) = (RxFrameParser.parse(bytes(hex)) as RxParseResult.Frame).frame

    @Test fun connectIsTheLiteralFrameSentByTheOriginalApp() {
        assertEquals("1aa1300201016eb41ff1", hex(TxFrameBuilder.build(TxCommand.Connect)))
    }

    @Test fun heartbeatIsTheLiteralFrameSentByTheOriginalApp() {
        assertEquals("1aa110010071951ff1", hex(TxFrameBuilder.build(TxCommand.Heartbeat)))
    }

    @Test fun eachBuildReturnsAFreshArray() {
        val a = TxFrameBuilder.build(TxCommand.Heartbeat)
        a[0] = 0
        assertEquals("1aa110010071951ff1", hex(TxFrameBuilder.build(TxCommand.Heartbeat)))
    }

    /** Lista fechada da etapa 4.3a: só CONNECT 0x30 e heartbeat 0x10 (plano Fase 4 §7.2). Unlock entra só na 4.4. */
    @Test fun txCommandIsAClosedListOfConnectAndHeartbeat() {
        assertEquals(setOf(TxCommand.Connect, TxCommand.Heartbeat), TxCommand.ALL.toSet())
        val produced = TxCommand.ALL.map { TxFrameBuilder.build(it) }
        val cmds = produced.map { it[2].toInt() and 0xFF }.toSet()
        assertEquals(setOf(0x30, 0x10), cmds)
        assertTrue("0x32..0x39 nunca", cmds.none { it in 0x32..0x39 })
    }

    @Test fun realAcksAreRecognisedEvenWithAnInvalidResponseCrc() {
        val hb = rx("1aa1100500be8c1ff1") // ACK real do heartbeat (CRC de resposta não confere, S2)
        val conn = rx("1aa130050101d5b31ff1") // ACK real do CONNECT
        assertFalse(hb.crcMatches)
        assertFalse(conn.crcMatches)
        assertTrue(hb.isAckOf(TxCommand.Heartbeat))
        assertTrue(conn.isAckOf(TxCommand.Connect))
        assertFalse(hb.isAckOf(TxCommand.Connect))
        assertFalse(conn.isAckOf(TxCommand.Heartbeat))
    }

    @Test fun telemetryIsNotAnAck() {
        // Meter1 real (bloqueado com senha) da mesma captura: tipo 03, não 05.
        val meter1 = rx("1aa1200312003000640000003c000000002323230203082f8c1ff1")
        assertFalse(meter1.isAckOf(TxCommand.Connect))
        assertFalse(meter1.isAckOf(TxCommand.Heartbeat))
        // Mesmo CMD do heartbeat mas tipo 01 (o próprio pedido ecoado) não é ACK.
        assertFalse(rx("1aa110010071951ff1").isAckOf(TxCommand.Heartbeat))
    }
}

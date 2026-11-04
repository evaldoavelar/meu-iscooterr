package br.dev.evaldo.meuscooter.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Vetores = linhas `RX` reais de `research/captures/s2d/ix7tap-roteiro2.dedup.txt` (captura S2, 2026-11-03).
 * Os valores esperados foram escritos À MÃO a partir de `docs/research/parts/S2-captura-resultados.md`
 * (cmd/tipo/LEN/dados lidos do quadro; bateria 0x64 e limite 0x32 citados na S2), não calculados pelo parser.
 */
class RxFrameParserTest {
    private fun bytes(hex: String) = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun ok(hex: String): RxFrame {
        val r = RxFrameParser.parse(bytes(hex))
        assertTrue("esperava quadro válido, veio $r", r is RxParseResult.Frame)
        return (r as RxParseResult.Frame).frame
    }

    private fun err(hex: String): RxError {
        val r = RxFrameParser.parse(bytes(hex))
        assertTrue("esperava erro, veio $r", r is RxParseResult.Invalid)
        return (r as RxParseResult.Invalid).error
    }

    @Test fun meter1RealFrame() {
        val f = ok("1aa120031200000064000000320000000023232302030853f81ff1")
        assertEquals(0x20, f.cmd)
        assertEquals(0x03, f.type)
        assertEquals(18, f.payload.size)
        assertEquals(0x64, f.payload[3]) // bateria 100 % (S2)
        assertEquals(0x32, f.payload[7]) // limite 50 km/h (S2)
    }

    @Test fun meter2RealFrame() {
        val f = ok("1aa12103120000ae1500000000170000000000160f03085d8b1ff1")
        assertEquals(0x21, f.cmd)
        assertEquals(0x03, f.type)
        assertEquals(18, f.payload.size)
        assertEquals(0xAE, f.payload[2])
    }

    @Test fun ackFrameWithOneByteOfData() {
        val f = ok("1aa130050101d5b31ff1") // ACK do CONNECT 0x30 (S2)
        assertEquals(0x30, f.cmd)
        assertEquals(0x05, f.type)
        assertEquals(listOf(0x01), f.payload)
    }

    @Test fun minimalFrameWithoutData() {
        val f = ok("1aa1100500be8c1ff1") // ACK do heartbeat 0x10, LEN 0 (menor quadro observado: 9 bytes)
        assertEquals(0x10, f.cmd)
        assertTrue(f.payload.isEmpty())
    }

    @Test fun queryResponseWithTwoBytes() {
        val f = ok("1aa16204021432531f1ff1") // resposta tipo 04 da consulta 0x62
        assertEquals(0x62, f.cmd)
        assertEquals(0x04, f.type)
        assertEquals(listOf(0x14, 0x32), f.payload)
    }

    @Test fun responseCrcIsNotRequiredButIsReportedAsMismatch() {
        // S2: o CRC das respostas não confere com nenhum algoritmo testado `[DESCONHECIDO]` → aceita, crcMatches=false.
        assertFalse(ok("1aa120031200000064000000320000000023232302030853f81ff1").crcMatches)
    }

    @Test fun crcMatchesIsReportedWhenValidModbusLowFirst() {
        // Quadro TX real (crc=lohi no log): CRC-16/Modbus de 41 02 01 00 = 0x48B4, byte baixo primeiro → b4 48.
        assertTrue(ok("1aa141020100b4481ff1").crcMatches)
    }

    @Test fun badHeader() = assertEquals(RxError.BadHeader, err("1bb1100500be8c1ff1"))

    @Test fun badFooter() = assertEquals(RxError.BadFooter, err("1aa1100500be8c1ff2"))

    @Test fun tooShort() {
        assertEquals(RxError.TooShort, err(""))
        assertEquals(RxError.TooShort, err("1aa1"))
        assertEquals(RxError.TooShort, err("1aa1100500be1ff1")) // 8 bytes
    }

    @Test fun truncatedFragmentIsTypedError() {
        // Primeiro pedaço de um Meter1: cabeçalho ok, LEN 18, mas só 12 bytes chegaram.
        assertEquals(RxError.Truncated, err("1aa1200312000000640000003200"))
    }

    @Test fun trailingBytesAreRejected() {
        assertEquals(RxError.TrailingBytes, err("1aa130050101d5b31ff100"))
    }

    @Test fun lengthFieldLargerThanFrameIsTruncated() {
        assertEquals(RxError.Truncated, err("1aa130050501d5b31ff1")) // LEN=5 mas só 1 byte de dado
    }

    @Test fun overMaximumSizeIsRejectedWithoutException() {
        val big = ByteArray(RxFrameParser.MAX_FRAME_SIZE + 1) { 0x1A }
        val r = RxFrameParser.parse(big)
        assertEquals(RxParseResult.Invalid(RxError.TooLong), r)
    }

    @Test fun maximumSizeFrameWithLen255IsAccepted() {
        val data = ByteArray(255) { 7 }
        val frame = byteArrayOf(0x1A, 0xA1.toByte(), 0x20, 0x03, 0xFF.toByte()) + data + byteArrayOf(0, 0, 0x1F, 0xF1.toByte())
        assertEquals(RxFrameParser.MAX_FRAME_SIZE, frame.size)
        assertEquals(255, (RxFrameParser.parse(frame) as RxParseResult.Frame).frame.payload.size)
    }

    @Test fun garbageNeverThrows() {
        for (n in 0..40) assertTrue(RxFrameParser.parse(ByteArray(n) { (it * 37).toByte() }) is RxParseResult.Invalid)
    }
}

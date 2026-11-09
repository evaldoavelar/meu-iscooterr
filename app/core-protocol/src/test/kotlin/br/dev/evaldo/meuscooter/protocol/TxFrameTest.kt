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

    /** Lista fechada da etapa 4.3a: CONNECT 0x30 e heartbeat 0x10 sem dados do usuário (plano Fase 4 §7.2). */
    @Test fun fixedCommandsAreConnectAndHeartbeatOnly() {
        assertEquals(setOf(TxCommand.Connect, TxCommand.Heartbeat), TxCommand.FIXED.toSet())
        val cmds = TxCommand.FIXED.map { TxFrameBuilder.build(it)[2].toInt() and 0xFF }.toSet()
        assertEquals(setOf(0x30, 0x10), cmds)
    }

    /**
     * Etapa 4.4 (plano §7.4): `LOCK 0x32` TIPO 02 LEN 5, modo 0 + 4 dígitos, cada dígito = valor 0..9 (`char - '0'`,
     * `q3/a.java:136-147` `[ESTÁTICO]`; os bytes do PIN estão mascarados nas capturas). PIN SINTÉTICO 1357. O esperado
     * foi calculado fora do código testado (CRC-16/Modbus em script à parte, que reproduz os literais de CONNECT e
     * heartbeat acima): corpo `32 02 05 00 01 03 05 07` → CRC 0x3d52.
     */
    @Test fun unlockIsLock0x32Type02Len5Mode0WithTheFourDigitValues() {
        assertEquals("1aa13202050001030507523d1ff1", hex(TxFrameBuilder.build(TxCommand.Unlock(Pin.of("1357")))))
    }

    @Test fun crcInTheUnlockTestIsIndependentlyCheckable() {
        // CRC-16/Modbus escrito aqui, à parte do `Crc16Modbus` de produção.
        var crc = 0xFFFF
        for (b in intArrayOf(0x32, 0x02, 0x05, 0x00, 0x01, 0x03, 0x05, 0x07)) {
            crc = crc xor b
            repeat(8) { crc = if (crc and 1 != 0) (crc ushr 1) xor 0xA001 else crc ushr 1 }
        }
        assertEquals(0x3d52, crc)
    }

    /**
     * Lista fechada (§7.4): o único quadro com dados do usuário é o desbloqueio modo 0. Para TODOS os 10 000 PINs, o
     * quadro tem CMD 0x32, TIPO 02, LEN 5 e modo 0 — nunca modo 1 (bloquear/definir PIN), 2, 3 (limpar PIN) ou outro,
     * nunca CMD 0x33..0x39.
     */
    @Test fun everyPossibleUnlockFrameIsMode0AndNeverAnyOtherCommand() {
        for (n in 0..9999) {
            val f = TxFrameBuilder.build(TxCommand.Unlock(Pin.of("%04d".format(n))))
            assertEquals(14, f.size)
            assertEquals(0x32, f[2].toInt() and 0xFF)
            assertEquals(0x02, f[3].toInt() and 0xFF)
            assertEquals(0x05, f[4].toInt() and 0xFF)
            assertEquals("modo", 0x00, f[5].toInt() and 0xFF)
            assertTrue((6..9).all { (f[it].toInt() and 0xFF) in 0..9 })
        }
        val all = TxCommand.FIXED.map { TxFrameBuilder.build(it) } + TxFrameBuilder.build(TxCommand.Unlock(Pin.of("1357")))
        assertTrue("0x33..0x39 nunca", all.none { (it[2].toInt() and 0xFF) in 0x33..0x39 })
        assertTrue("só modo 0", all.filter { (it[2].toInt() and 0xFF) == 0x32 }.all { it[5].toInt() == 0 })
    }

    /**
     * Bloqueio temporário (LOCK modo 2, S20; `[CONFIRMADO]` 12/12 em S2 e S13): `32 02 01 02` → CRC 0x0d2f. O CRC foi
     * calculado fora do código testado (script à parte que reproduz CONNECT/heartbeat); as capturas mascaram o CRC do
     * LOCK (`1aa132020102****1ff1`), então NÃO há literal de referência `[DESCONHECIDO]`.
     */
    @Test fun lockIsLock0x32Type02Len1Mode2() {
        assertEquals("1aa1320201022f0d1ff1", hex(TxFrameBuilder.build(TxCommand.Lock)))
        var crc = 0xFFFF
        for (b in intArrayOf(0x32, 0x02, 0x01, 0x02)) {
            crc = crc xor b
            repeat(8) { crc = if (crc and 1 != 0) (crc ushr 1) xor 0xA001 else crc ushr 1 }
        }
        assertEquals(0x0d2f, crc)
    }

    /**
     * Só o modo 2 existe como LOCK sem PIN: nenhum quadro construível tem CMD 0x32 com modo 1 ou 3 (nem 0 sem PIN, LEN 1),
     * nenhum CMD 0x34..0x39 (0x33 é o farol, S31); os únicos quadros 0x32 são Unlock (LEN 5, modo 0) e Lock (LEN 1, modo 2).
     */
    @Test fun onlyMode2ExistsAsAPinlessLockFrame() {
        val frames = TxCommand.FIXED.map { TxFrameBuilder.build(it) } +
            TxFrameBuilder.build(TxCommand.Lock) + TxFrameBuilder.build(TxCommand.Unlock(Pin.of("1357")))
        assertTrue("0x34..0x39 nunca", frames.none { (it[2].toInt() and 0xFF) in 0x34..0x39 })
        val locks = frames.filter { (it[2].toInt() and 0xFF) == 0x32 }
        assertEquals(2, locks.size)
        for (f in locks) {
            val len = f[4].toInt()
            val mode = f[5].toInt()
            assertTrue("modo 1/3/outro nunca", (len == 1 && mode == 2) || (len == 5 && mode == 0))
        }
        // Lock não tem construtor público nem parâmetros: não carrega modo nem bytes.
        assertTrue(TxCommand.Lock::class.java.declaredConstructors.none { java.lang.reflect.Modifier.isPublic(it.modifiers) })
        assertTrue(TxCommand.Lock::class.java.declaredFields.none { !java.lang.reflect.Modifier.isStatic(it.modifiers) })
    }

    @Test fun lockReplyAckIsRecognisedAsAnAckOfLock() {
        assertTrue(rx("1aa13205000000" + "1ff1").isAckOf(TxCommand.Lock))
        assertFalse(rx("1aa13005000000" + "1ff1").isAckOf(TxCommand.Lock))
    }

    /**
     * S31 (aprovado pelo proprietário): farol = CMD 0x33 TIPO 02 LEN 6. Bytes literais de
     * `docs/research/parts/S31-farol.md` (capturas s2d/ix7tap-roteiro2.txt e s13pin/ix7tap-pin.txt do app original),
     * copiados à mão; ligar `00 fa 00 00 00 00`, desligar seis zeros.
     */
    @Test fun lightIsCmd0x33Type02Len6WithOnlyTheTwoObservedPayloads() {
        assertEquals("1aa133020600fa0000000075cc1ff1", hex(TxFrameBuilder.build(TxCommand.Light(on = true))))
        assertEquals("1aa1330206000000000000add81ff1", hex(TxFrameBuilder.build(TxCommand.Light(on = false))))
    }

    @Test fun lightAckIsRecognisedAndOtherCmdIsNot() {
        assertTrue(rx("1aa13305" + "00ff55" + "1ff1").isAckOf(TxCommand.Light(true)))
        assertFalse(rx("1aa13205000000" + "1ff1").isAckOf(TxCommand.Light(true)))
    }

    /** Só o brilho 0xfa ou 0 existe; nenhum valor de cor/brilho intermediário é construível (booleano é o único parâmetro). */
    @Test fun lightHasNoBrightnessOrColourParameterAndOnlyTwoFramesExist() {
        val ctors = TxCommand.Light::class.java.constructors.filter { java.lang.reflect.Modifier.isPublic(it.modifiers) }
        assertEquals(listOf(listOf(Boolean::class.javaPrimitiveType)), ctors.map { it.parameterTypes.toList() })
        val frames = listOf(true, false).map { TxFrameBuilder.build(TxCommand.Light(it)) }
        for (f in frames) {
            assertEquals(15, f.size)
            assertEquals(6, f[4].toInt())
            val data = f.slice(5..10).map { it.toInt() and 0xFF }
            assertEquals(0, data[0])
            assertTrue(data[1] == 0xfa || data[1] == 0)
            assertEquals(listOf(0, 0, 0, 0), data.drop(2))
        }
    }

    /** CMDs vistos no original mas SEM aprovação continuam fora de qualquer quadro construível. */
    @Test fun forbiddenCmdsAreNeverBuildable() {
        val frames = TxCommand.FIXED.map { TxFrameBuilder.build(it) } + TxFrameBuilder.build(TxCommand.Lock) +
            TxFrameBuilder.build(TxCommand.Unlock(Pin.of("1357"))) + listOf(true, false).map { TxFrameBuilder.build(TxCommand.Light(it)) }
        val cmds = frames.map { it[2].toInt() and 0xFF }.toSet()
        assertEquals(setOf(0x30, 0x10, 0x32, 0x33), cmds)
        for (forbidden in listOf(0x3b, 0x41, 0x61, 0x62, 0x63)) assertFalse(forbidden in cmds)
    }

    /** Subtipos de `TxCommand`: exatamente Connect, Heartbeat, Unlock, Lock e Light. Nada novo entra sem mudar este teste. */
    @Test fun txCommandSubclassesAreExactlyConnectHeartbeatUnlockLockAndLight() {
        val nested = TxCommand::class.java.declaredClasses.filter { TxCommand::class.java.isAssignableFrom(it) }.map { it.simpleName }.toSet()
        assertEquals(setOf("Connect", "Heartbeat", "Unlock", "Lock", "Light"), nested)
        // Nenhum subtipo fora do arquivo: o construtor base não é acessível a outros pacotes/módulos.
        assertTrue(TxCommand::class.java.declaredConstructors.filter { !it.isSynthetic }.none { java.lang.reflect.Modifier.isPublic(it.modifiers) })
    }

    /** Sem API de bytes livres nem modo parametrizável: o único construtor público de Unlock recebe só um [Pin]. */
    @Test fun unlockOnlyTakesAPinAndNoPublicApiAcceptsRawBytesOrAMode() {
        val ctors = TxCommand.Unlock::class.java.constructors.filter { java.lang.reflect.Modifier.isPublic(it.modifiers) }
        assertEquals(1, ctors.size)
        assertEquals(listOf(Pin::class.java), ctors.single().parameterTypes.toList())
        val suspicious = setOf(ByteArray::class.java, IntArray::class.java, Int::class.javaPrimitiveType, Byte::class.javaPrimitiveType, List::class.java)
        val apis = listOf(TxCommand::class.java, TxCommand.Unlock::class.java, TxFrameBuilder::class.java, Pin::class.java, Pin.Companion::class.java)
        apis.flatMap { it.methods.toList() }
            .filter { java.lang.reflect.Modifier.isPublic(it.modifiers) && it.declaringClass != Any::class.java && it.declaringClass != Object::class.java }
            .forEach { m -> assertTrue("${m.declaringClass.simpleName}.${m.name}", m.parameterTypes.none { it in suspicious }) }
    }

    @Test fun lockRepliesAckAndPinRejection() {
        // ACK e recusa observados em S13 (`1aa1 32 05 00`, `1aa1 32 89 00`); o CRC de resposta é desconhecido e não é
        // exigido pelo parser, então os 2 bytes de CRC aqui são arbitrários.
        val ack = rx("1aa13205000000" + "1ff1")
        val rejected = rx("1aa13289000000" + "1ff1")
        val unlock = TxCommand.Unlock(Pin.of("1357"))
        assertTrue(ack.isAckOf(unlock))
        assertFalse(ack.isPinRejection())
        assertTrue(rejected.isPinRejection())
        assertFalse(rejected.isAckOf(unlock))
        // Tipo 0x89 em outro CMD não é recusa de PIN.
        assertFalse(rx("1aa13089000000" + "1ff1").isPinRejection())
        assertFalse(rx("1aa11005000000" + "1ff1").isPinRejection())
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

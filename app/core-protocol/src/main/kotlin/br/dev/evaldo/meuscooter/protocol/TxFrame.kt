package br.dev.evaldo.meuscooter.protocol

/**
 * Lista FECHADA dos quadros que o app pode escrever em FF62 (etapa 4.3a, plano Fase 4 §7.2). Não existe API genérica
 * de "enviar bytes": só estes objetos viram quadro. Bytes `[CONFIRMADO]` no log do app original
 * (`research/captures/s4-3/ix7tap.txt`). `Unlock` só entra na 4.4, com aprovação própria.
 */
sealed class TxCommand(internal val cmd: Int, internal val type: Int, internal val data: List<Int>) {
    /** CONNECT 0x30, TIPO 02, LEN 1, dado 01 → `1aa1300201016eb41ff1`. */
    data object Connect : TxCommand(cmd = 0x30, type = 0x02, data = listOf(0x01))

    /** Heartbeat 0x10, TIPO 01, LEN 0 → `1aa110010071951ff1` (~1 s no original). */
    data object Heartbeat : TxCommand(cmd = 0x10, type = 0x01, data = emptyList())

    companion object {
        val ALL: List<TxCommand> get() = listOf(Connect, Heartbeat)
    }
}

/** `1A A1 · CMD · TIPO · LEN · DADOS · CRC-16/Modbus (byte baixo primeiro) · 1F F1` `[CONFIRMADO]` para TX (S2). */
object TxFrameBuilder {
    fun build(command: TxCommand): ByteArray {
        val body = byteArrayOf(command.cmd.toByte(), command.type.toByte(), command.data.size.toByte()) +
            ByteArray(command.data.size) { command.data[it].toByte() }
        val crc = Crc16Modbus.compute(body, from = 0, until = body.size)
        return byteArrayOf(RxFrameParser.HEADER_0.toByte(), RxFrameParser.HEADER_1.toByte()) + body +
            byteArrayOf((crc and 0xFF).toByte(), (crc ushr 8 and 0xFF).toByte(), RxFrameParser.FOOTER_0.toByte(), RxFrameParser.FOOTER_1.toByte())
    }
}

/** TIPO das respostas de recebimento (ACK) `[CONFIRMADO]` (S2 e s4-3). */
const val TYPE_ACK = 0x05

/**
 * `true` se este quadro é o ACK de [command]: mesmo CMD e TIPO 05. O CRC NÃO é exigido: o das respostas não confere
 * com nenhum algoritmo testado `[DESCONHECIDO]` (S2). ACK = recebido, nunca "executado" (regra 5).
 */
fun RxFrame.isAckOf(command: TxCommand): Boolean = cmd == command.cmd && type == TYPE_ACK

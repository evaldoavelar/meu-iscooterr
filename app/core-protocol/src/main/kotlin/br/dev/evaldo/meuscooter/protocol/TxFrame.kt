package br.dev.evaldo.meuscooter.protocol

/**
 * Lista FECHADA dos quadros que o app pode escrever em FF62 (plano Fase 4 §7.2 e §7.4). Não existe API genérica
 * de "enviar bytes" nem de modo/CMD livres: só estes tipos viram quadro. CONNECT/heartbeat `[CONFIRMADO]` no log do
 * app original (`research/captures/s4-3/ix7tap.txt`); [Unlock] (4.4) segue o formato observado em S13.
 */
sealed class TxCommand(internal val cmd: Int, internal val type: Int) {
    /** DADOS do quadro (0..255). Cópia nova a cada chamada. */
    internal abstract fun payload(): IntArray

    /** CONNECT 0x30, TIPO 02, LEN 1, dado 01 → `1aa1300201016eb41ff1`. */
    data object Connect : TxCommand(cmd = 0x30, type = 0x02) {
        override fun payload() = intArrayOf(0x01)
    }

    /** Heartbeat 0x10, TIPO 01, LEN 0 → `1aa110010071951ff1` (~1 s no original). */
    data object Heartbeat : TxCommand(cmd = 0x10, type = 0x01) {
        override fun payload() = IntArray(0)
    }

    /**
     * Desbloqueio com PIN: `LOCK 0x32` TIPO 02 LEN 5, DADOS = modo 0 + 4 dígitos (`[CONFIRMADO]` o formato e o efeito,
     * S13/S17; valores 0..9 por dígito `[ESTÁTICO]`, `q3/a.java:136-147` — as capturas mascaram esses bytes).
     * O modo é FIXO em 0: modo 1 (bloquear/definir PIN), 2 (bloqueio temporário), 3 (limpar PIN) e qualquer outro
     * não existem aqui. `toString` mascara o PIN.
     */
    class Unlock(val pin: Pin) : TxCommand(cmd = CMD_LOCK, type = 0x02) {
        override fun payload(): IntArray = intArrayOf(MODE_UNLOCK_WITH_PIN) + pin.digitValues()
        override fun toString() = "Unlock(pin=${Pin.MASK})"
    }

    companion object {
        /** Os comandos sem dados do usuário. */
        val FIXED: List<TxCommand> get() = listOf(Connect, Heartbeat)

        /** CMD do LOCK (`0x32`). */
        const val CMD_LOCK = 0x32
        private const val MODE_UNLOCK_WITH_PIN = 0x00
    }
}

/** `1A A1 · CMD · TIPO · LEN · DADOS · CRC-16/Modbus (byte baixo primeiro) · 1F F1` `[CONFIRMADO]` para TX (S2). */
object TxFrameBuilder {
    fun build(command: TxCommand): ByteArray {
        val data = command.payload()
        val body = byteArrayOf(command.cmd.toByte(), command.type.toByte(), data.size.toByte()) +
            ByteArray(data.size) { data[it].toByte() }
        data.fill(0) // pode conter o PIN
        val crc = Crc16Modbus.compute(body, from = 0, until = body.size)
        val frame = byteArrayOf(RxFrameParser.HEADER_0.toByte(), RxFrameParser.HEADER_1.toByte()) + body +
            byteArrayOf((crc and 0xFF).toByte(), (crc ushr 8 and 0xFF).toByte(), RxFrameParser.FOOTER_0.toByte(), RxFrameParser.FOOTER_1.toByte())
        body.fill(0)
        return frame
    }
}

/** TIPO das respostas de recebimento (ACK) `[CONFIRMADO]` (S2 e s4-3). */
const val TYPE_ACK = 0x05

/**
 * `true` se este quadro é o ACK de [command]: mesmo CMD e TIPO 05. O CRC NÃO é exigido: o das respostas não confere
 * com nenhum algoritmo testado `[DESCONHECIDO]` (S2). ACK = recebido, nunca "executado" (regra 5).
 */
fun RxFrame.isAckOf(command: TxCommand): Boolean = cmd == command.cmd && type == TYPE_ACK

/**
 * TIPO da resposta do LOCK quando o patinete recusa o PIN: `1aa1 32 89 00` `[CONFIRMADO]` (S13, 3ª captura: duas
 * tentativas erradas, Meter1 continuou bloqueado; `[ESTÁTICO]` `DeviceCarControlFrag:3354-3361`). Se 0x89 vale para
 * outros erros além do PIN é `[DESCONHECIDO]`.
 */
const val TYPE_PIN_REJECTED = 0x89

/** `true` se é a recusa de PIN do LOCK (CMD 0x32, tipo 0x89). CRC não exigido (como no ACK). */
fun RxFrame.isPinRejection(): Boolean = cmd == TxCommand.CMD_LOCK && type == TYPE_PIN_REJECTED

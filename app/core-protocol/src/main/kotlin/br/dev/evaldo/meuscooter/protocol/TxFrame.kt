package br.dev.evaldo.meuscooter.protocol

/**
 * Lista FECHADA dos quadros que o app pode escrever em FF62 (plano Fase 4 §7.2 e §7.4). Não existe API genérica
 * de "enviar bytes" nem de modo/CMD livres: só estes tipos viram quadro. CONNECT/heartbeat `[CONFIRMADO]` no log do
 * app original (`research/captures/s4-3/ix7tap.txt`); [Unlock] (4.4) segue o formato observado em S13.
 */
sealed class TxCommand(internal val cmd: Int, internal val type: Int) {
    /** True se [frame] responde a este comando (mesmo CMD); associa respostas à tentativa em voo sem expor o inteiro. */
    fun matchesResponse(frame: RxFrame): Boolean = cmd == frame.cmd

    /** DADOS em um único buffer temporário, apagado pelo construtor do frame em finally. */
    internal abstract fun payload(): ByteArray

    /** CONNECT 0x30, TIPO 02, LEN 1, dado 01 → `1aa1300201016eb41ff1`. */
    data object Connect : TxCommand(cmd = 0x30, type = 0x02) {
        override fun payload() = byteArrayOf(0x01)
    }

    /** Heartbeat 0x10, TIPO 01, LEN 0 → `1aa110010071951ff1` (~1 s no original). */
    data object Heartbeat : TxCommand(cmd = 0x10, type = 0x01) {
        override fun payload() = ByteArray(0)
    }

    /**
     * Desbloqueio com PIN: `LOCK 0x32` TIPO 02 LEN 5, DADOS = modo 0 + 4 dígitos (`[CONFIRMADO]` o formato e o efeito,
     * S13/S17; valores 0..9 por dígito `[ESTÁTICO]`, `q3/a.java:136-147` — as capturas mascaram esses bytes).
     * O modo é FIXO em 0: modo 1 (bloquear/definir PIN), 2 (bloqueio temporário), 3 (limpar PIN) e qualquer outro
     * não existem aqui. `toString` mascara o PIN.
     */
    class Unlock(val pin: Pin) : TxCommand(cmd = CMD_LOCK, type = 0x02) {
        override fun payload() = pin.unlockPayload()
        override fun toString() = "Unlock(pin=${Pin.MASK})"
    }

    /**
     * Bloqueio temporário SEM PIN (S20, aprovado pelo proprietário): `LOCK 0x32` TIPO 02 LEN 1, DADOS = modo 2
     * (`1aa1 32 02 01 02 <CRC> 1ff1`, `[CONFIRMADO]` 12/12 em S2 e S13). Objeto fechado: o modo é FIXO em 2. Os modos
     * 1 (bloquear com PIN) e 3 (limpar PIN) e qualquer outro CMD seguem inexistentes aqui.
     */
    data object Lock : TxCommand(cmd = CMD_LOCK, type = 0x02) {
        override fun payload() = byteArrayOf(0x02)
    }

    /**
     * Farol (S31, aprovado pelo proprietário): `APP_LIGHT 0x33` TIPO 02 LEN 6, DADOS = `00 <brilho> 00 00 00 00` com
     * brilho 0xfa (ligar) ou 0 (desligar) `[CONFIRMADO]` (capturas s2d e s13pin do original). Único parâmetro: ligado ou
     * não. Outros brilhos e os 4 bytes finais (cor/modo da luz ambiente) são `[DESCONHECIDO]` e NÃO existem aqui.
     */
    class Light(val on: Boolean) : TxCommand(cmd = CMD_LIGHT, type = 0x02) {
        override fun payload() = byteArrayOf(0x00, if (on) BRIGHTNESS_ON else 0x00, 0x00, 0x00, 0x00, 0x00)
        override fun toString() = "Light(on=$on)"
    }

    /** Unidade de velocidade: `0` km/h ou `1` mph, CMD 0x3c (S31). */
    class Unit(val mph: Boolean) : TxCommand(cmd = CMD_UNIT, type = 0x02) {
        override fun payload() = byteArrayOf(if (mph) 1 else 0)
        override fun toString() = "Unit(mph=$mph)"
    }

    /** Modo fechado 0..3, CMD 0x35, payload [mode, 0] (S31). */
    class DriveMode(val mode: Int) : TxCommand(cmd = CMD_DRIVE_MODE, type = 0x02) {
        init { require(mode in 0..3) { "drive mode must be 0..3" } }
        override fun payload() = byteArrayOf(mode.toByte(), 0)
        override fun toString() = "DriveMode(mode=$mode)"
    }

    /** Limite em km/h; o teto absoluto do app é 60, CMD 0x36 (S31). */
    class SpeedLimit(val kmh: Int) : TxCommand(cmd = CMD_SPEED_LIMIT, type = 0x02) {
        init { require(kmh in 1..MAX_SPEED_LIMIT_KMH) { "speed limit must be 1..60 km/h" } }
        override fun payload() = byteArrayOf(kmh.toByte())
        override fun toString() = "SpeedLimit(kmh=$kmh)"
    }

    /** Tração: false = 1 motor, true = 2 motores, CMD 0x3b (S31). */
    class Drive(val twoMotors: Boolean) : TxCommand(cmd = CMD_DRIVE, type = 0x02) {
        override fun payload() = byteArrayOf(if (twoMotors) 1 else 0)
        override fun toString() = "Drive(twoMotors=$twoMotors)"
    }

    /**
     * Cruise (S32): CMD 0x38 TIPO 02 LEN 2, DADOS `05 03` (ligar) / `00 03` (desligar) `[CONFIRMADO]` (s2d, 4 transições).
     * Só habilita o recurso; o engate real é físico (acelerador ~5 s) `[ESTÁTICO]`. Sem outros valores.
     */
    class Cruise(val on: Boolean) : TxCommand(cmd = CMD_CRUISE, type = 0x02) {
        override fun payload() = byteArrayOf(if (on) 5 else 0, 3)
        override fun toString() = "Cruise(on=$on)"
    }

    /**
     * Partida do zero (S32): CMD 0x39 TIPO 02 LEN 2, DADOS `00 00` (LIGAR) / `01 00` (DESLIGAR) `[CONFIRMADO]`;
     * semântica invertida em relação ao valor enviado.
     */
    class ZeroStart(val on: Boolean) : TxCommand(cmd = CMD_ZERO_START, type = 0x02) {
        override fun payload() = byteArrayOf(if (on) 0 else 1, 0)
        override fun toString() = "ZeroStart(on=$on)"
    }

    /** Única consulta de configuração autorizada: faixa de velocidade via CMD 0x62. */
    data object SpeedLimitRangeQuery : TxCommand(cmd = CMD_SPEED_LIMIT_RANGE, type = 0x01) {
        override fun payload() = ByteArray(0)
    }

    companion object {
        /** Brilho 250 enviado pelo botão do original ao ligar (`q3/a.java:111-114`). */
        private const val BRIGHTNESS_ON: Byte = 0xFA.toByte()

        /** CMD do farol (`0x33`). */
        const val CMD_LIGHT = 0x33
        const val CMD_DRIVE_MODE = 0x35
        const val CMD_SPEED_LIMIT = 0x36
        const val CMD_DRIVE = 0x3B
        const val CMD_UNIT = 0x3C
        const val CMD_CRUISE = 0x38
        const val CMD_ZERO_START = 0x39
        const val CMD_SPEED_LIMIT_RANGE = 0x62
        const val MAX_SPEED_LIMIT_KMH = 60

        /** Os comandos sem dados do usuário. */
        val FIXED: List<TxCommand> get() = listOf(Connect, Heartbeat)

        /** CMD do LOCK (`0x32`). */
        const val CMD_LOCK = 0x32
    }
}

/** `1A A1 · CMD · TIPO · LEN · DADOS · CRC-16/Modbus (byte baixo primeiro) · 1F F1` `[CONFIRMADO]` para TX (S2). */
object TxFrameBuilder {
    fun build(command: TxCommand): ByteArray {
        val data = command.payload()
        var frame: ByteArray? = null
        var built = false
        try {
            frame = ByteArray(9 + data.size)
            frame[0] = RxFrameParser.HEADER_0.toByte()
            frame[1] = RxFrameParser.HEADER_1.toByte()
            frame[2] = command.cmd.toByte()
            frame[3] = command.type.toByte()
            frame[4] = data.size.toByte()
            data.copyInto(frame, destinationOffset = 5)
            val crcOffset = 5 + data.size
            val crc = Crc16Modbus.compute(frame, from = 2, until = crcOffset)
            frame[crcOffset] = (crc and 0xFF).toByte()
            frame[crcOffset + 1] = (crc ushr 8 and 0xFF).toByte()
            frame[crcOffset + 2] = RxFrameParser.FOOTER_0.toByte()
            frame[crcOffset + 3] = RxFrameParser.FOOTER_1.toByte()
            built = true
            return frame // quem escreve é responsável por apagar em finally
        } finally {
            data.fill(0)
            if (!built) frame?.fill(0)
        }
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

/** `true` se é a resposta de erro do farol (CMD 0x33, tipo 0x89). `[DESCONHECIDO]` se o firmware a usa; não foi vista. */
fun RxFrame.isLightRejection(): Boolean = cmd == TxCommand.CMD_LIGHT && type == TYPE_PIN_REJECTED

/** Erro tipo 0x89 nos comandos S31/S32 (unidade, modo, limite, tração, cruise, partida do zero) (a semântica do código permanece a resposta Rejected). */
fun RxFrame.isS31Rejection(): Boolean = cmd in setOf(
    TxCommand.CMD_UNIT, TxCommand.CMD_DRIVE_MODE, TxCommand.CMD_SPEED_LIMIT, TxCommand.CMD_DRIVE,
    TxCommand.CMD_CRUISE, TxCommand.CMD_ZERO_START,
) && type == TYPE_PIN_REJECTED

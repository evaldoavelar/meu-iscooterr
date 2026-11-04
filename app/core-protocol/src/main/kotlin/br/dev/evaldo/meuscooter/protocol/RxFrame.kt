package br.dev.evaldo.meuscooter.protocol

/**
 * Quadro recebido do patinete (notificação em FF61): `1A A1 · CMD · TIPO · LEN · DADOS[LEN] · CRC16 · 1F F1`
 * `[CONFIRMADO]` em `docs/research/parts/S2-captura-resultados.md` (3559 quadros RX, todos neste formato).
 * [payload] guarda os DADOS como inteiros sem sinal (0..255).
 * [crcMatches] é só informativo: o CRC das respostas não confere com nenhum algoritmo testado `[DESCONHECIDO]` (S2),
 * por isso o parser NUNCA rejeita um quadro por CRC.
 */
data class RxFrame(val cmd: Int, val type: Int, val payload: List<Int>, val crcMatches: Boolean)

/** Motivos de rejeição de um quadro. Sempre erro tipado, nunca exceção. */
enum class RxError { TooShort, TooLong, BadHeader, Truncated, TrailingBytes, BadFooter }

sealed interface RxParseResult {
    data class Frame(val frame: RxFrame) : RxParseResult
    data class Invalid(val error: RxError) : RxParseResult
}

/**
 * Valida estrutura (cabeçalho, rodapé, LEN × tamanho real, mínimo e máximo) de UMA notificação.
 * Reagrupamento de notificações fragmentadas NÃO existe: nenhum dos 3559 quadros RX da S2 chegou fragmentado
 * (todos completos numa notificação), então não há evidência de como reagrupar `[DESCONHECIDO]`; um fragmento
 * vira [RxError.Truncated] / [RxError.TooShort] / [RxError.BadHeader].
 */
object RxFrameParser {
    const val HEADER_0 = 0x1A
    const val HEADER_1 = 0xA1
    const val FOOTER_0 = 0x1F
    const val FOOTER_1 = 0xF1

    /** Cabeçalho(2) + CMD + TIPO + LEN + CRC(2) + rodapé(2); menor quadro observado na S2 (LEN 0). */
    const val MIN_FRAME_SIZE = 9

    /** LEN é 1 byte (≤255) → limite estrutural do formato. Maior quadro observado na S2: 27 bytes. */
    const val MAX_FRAME_SIZE = MIN_FRAME_SIZE + 255

    fun parse(raw: ByteArray): RxParseResult {
        if (raw.size < MIN_FRAME_SIZE) return RxParseResult.Invalid(RxError.TooShort)
        if (raw.size > MAX_FRAME_SIZE) return RxParseResult.Invalid(RxError.TooLong)
        if (u(raw, 0) != HEADER_0 || u(raw, 1) != HEADER_1) return RxParseResult.Invalid(RxError.BadHeader)
        val len = u(raw, 4)
        val expected = MIN_FRAME_SIZE + len
        if (raw.size < expected) return RxParseResult.Invalid(RxError.Truncated)
        if (raw.size > expected) return RxParseResult.Invalid(RxError.TrailingBytes)
        if (u(raw, expected - 2) != FOOTER_0 || u(raw, expected - 1) != FOOTER_1) return RxParseResult.Invalid(RxError.BadFooter)
        val crcStart = 5 + len
        // Mesma ordem do TX (byte baixo primeiro) `[CONFIRMADO]` para TX; em RX é só informação.
        val received = u(raw, crcStart) or (u(raw, crcStart + 1) shl 8)
        val computed = Crc16Modbus.compute(raw, from = 2, until = crcStart)
        return RxParseResult.Frame(
            RxFrame(
                cmd = u(raw, 2),
                type = u(raw, 3),
                payload = List(len) { u(raw, 5 + it) },
                crcMatches = received == computed,
            ),
        )
    }

    private fun u(b: ByteArray, i: Int) = b[i].toInt() and 0xFF
}

/** CRC-16/Modbus (init 0xFFFF, poli 0xA001) sobre CMD..DADOS `[CONFIRMADO]` para TX (S2). */
object Crc16Modbus {
    fun compute(b: ByteArray, from: Int, until: Int): Int {
        var crc = 0xFFFF
        for (i in from until until) {
            crc = crc xor (b[i].toInt() and 0xFF)
            repeat(8) { crc = if (crc and 1 != 0) (crc ushr 1) xor 0xA001 else crc ushr 1 }
        }
        return crc
    }
}

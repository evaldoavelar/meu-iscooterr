package br.dev.evaldo.meuscooter.data

import br.dev.evaldo.meuscooter.protocol.LockResult
import br.dev.evaldo.meuscooter.protocol.TxCommand
import br.dev.evaldo.meuscooter.protocol.UnlockResult

/** Destino do log de auditoria dos envios ao patinete. Padrão: [None] (nada é registrado). */
fun interface TxAuditLog {
    fun record(event: TxAuditEvent)

    companion object {
        val None = TxAuditLog { }
    }
}

/** Uma linha do log: instante do relógio injetado, categoria e texto. Nunca contém o PIN nem o CRC do quadro Unlock. */
data class TxAuditEvent(val atMs: Long, val category: String, val text: String) {
    /** Formato único: `t=<ms> <CATEGORIA> <texto>`. */
    fun line(): String = "t=$atMs $category $text"
}

/** Formatação do log. O quadro TX do Unlock tem os bytes do PIN e o CRC (que permite recuperar o PIN) mascarados. */
object TxAuditFormat {
    const val MASK = "XX"

    private fun hex(b: Byte) = "%02x".format(b.toInt() and 0xFF)

    /** Quadro `1A A1 CMD TIPO LEN DADOS CRC(2) 1F F1` em hex minúsculo; Unlock: PIN (DADOS[1..]) e CRC viram `XX`. */
    fun txFrame(command: TxCommand, frame: ByteArray): String {
        if (command !is TxCommand.Unlock) return frame.joinToString("") { hex(it) }
        // Falha fechada: tamanho inesperado do Unlock não é registrado em hex (PIN/CRC não localizáveis).
        if (frame.size < 9) return MASK
        val pinFrom = 6
        val crcFrom = frame.size - 4
        return frame.indices.joinToString("") { i -> if (i in pinFrom until crcFrom + 2) MASK else hex(frame[i]) }
    }

    fun rxFrame(value: ByteArray): String = value.joinToString("") { hex(it) }

    fun commandName(command: TxCommand): String = when (command) {
        is TxCommand.Connect -> "CONNECT"
        is TxCommand.Heartbeat -> "HEARTBEAT"
        is TxCommand.Unlock -> "UNLOCK"
        is TxCommand.Lock -> "LOCK"
    }

    fun result(r: UnlockResult): String = when (r) {
        is UnlockResult.Refused -> "Refused:${r.reason}"
        UnlockResult.Unlocked -> "Unlocked"
        UnlockResult.PinRejected -> "PinRejected"
        is UnlockResult.Unconfirmed -> "Unconfirmed:${r.cause}"
    }

    fun result(r: LockResult): String = when (r) {
        is LockResult.Refused -> "Refused:${r.reason}"
        LockResult.Locked -> "Locked"
        LockResult.Rejected -> "PinRejected"
        is LockResult.Unconfirmed -> "Unconfirmed:${r.cause}"
    }
}

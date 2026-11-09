package br.dev.evaldo.meuscooter.domain

import br.dev.evaldo.meuscooter.protocol.CommandRefusal
import br.dev.evaldo.meuscooter.protocol.UnconfirmedCause

/**
 * Códigos de `Ack.Rejected.reason` dos quatro comandos S31 (unidade, modo, limite, tração): levam a recusa e a falta de
 * confirmação do transporte até a UI sem outro canal (o reducer só guarda a `String`). Qualquer outro texto continua
 * sendo "recusado pelo patinete".
 */
object SettingAckCodes {
    const val REJECTED = "setting-rejected"
    private const val REFUSED = "setting-refused:"
    private const val UNCONFIRMED = "setting-unconfirmed:"

    fun refused(r: CommandRefusal) = REFUSED + r.name
    fun unconfirmed(c: UnconfirmedCause) = UNCONFIRMED + c.name
    fun parseRefusal(code: String): CommandRefusal? =
        if (code.startsWith(REFUSED)) CommandRefusal.entries.firstOrNull { it.name == code.removePrefix(REFUSED) } else null
    fun isUnconfirmed(code: String) = code.startsWith(UNCONFIRMED)
}

package br.dev.evaldo.meuscooter.protocol

/**
 * PIN de 4 dígitos do proprietário (etapa 4.4, plano Fase 4 §7.4). Credencial: nunca aparece em `toString`, em
 * mensagens de exceção nem em log; não é `data class` (sem igualdade/hash por valor). Guardado como valores 0..9 num
 * array próprio que [wipe] zera depois do uso. Limite `[ESTÁTICO]`: o texto digitado na UI é `String` (imutável na JVM)
 * e não pode ser apagado da memória; só deixa de ser referenciado.
 */
class Pin private constructor(private val digits: ByteArray) {
    var isWiped: Boolean = false
        private set

    /** Modo 0 fixo + dígitos para Unlock, sem cópia intermediária (`q3/a.java:136-147` `[ESTÁTICO]`). */
    internal fun unlockPayload(): ByteArray {
        check(!isWiped) { "PIN já apagado" }
        return ByteArray(LENGTH + 1).also { digits.copyInto(it, destinationOffset = 1) }
    }

    /** Zera os dígitos; depois disso o PIN não serve para montar quadro. */
    fun wipe() {
        digits.fill(0)
        isWiped = true
    }

    /** Cópia independente (para guardar o PIN depois do desbloqueio, que apaga o original). Quem copia apaga a cópia. */
    fun duplicate(): Pin {
        check(!isWiped) { "PIN já apagado" }
        return Pin(digits.copyOf())
    }

    /**
     * Dígitos ASCII `'0'..'9'` (4 bytes) SÓ para o cofre cifrar (ADR 0006 §5). Nova cópia a cada chamada: quem chama
     * a apaga (`fill(0)`) logo depois de cifrar. Não há API pública inversa que receba bytes (guarda em `TxFrameTest`):
     * para importar, o cofre usa `Pin.parse(CharBuffer.wrap(chars))` e apaga `chars`.
     */
    fun toAsciiBytes(): ByteArray {
        check(!isWiped) { "PIN já apagado" }
        return ByteArray(LENGTH) { (digits[it] + '0'.code).toByte() }
    }

    /** Mesmos dígitos (tempo constante; PIN apagado nunca confere). Para o transporte simulado e testes. */
    fun sameAs(other: Pin): Boolean {
        if (isWiped || other.isWiped) return false
        var diff = 0
        for (i in 0 until LENGTH) diff = diff or (digits[i].toInt() xor other.digits[i].toInt())
        return diff == 0
    }

    override fun toString(): String = MASK

    companion object {
        const val LENGTH = 4
        const val MASK = "****"

        /** `null` se não forem exatamente 4 dígitos ASCII `0`..`9`. */
        fun parse(text: CharSequence): Pin? {
            if (text.length != LENGTH || text.any { it !in '0'..'9' }) return null
            return Pin(ByteArray(LENGTH) { (text[it] - '0').toByte() })
        }

        /** Como [parse], mas lança [IllegalArgumentException] com mensagem que nunca contém o texto recebido. */
        fun of(text: CharSequence): Pin = parse(text) ?: throw IllegalArgumentException("PIN deve ter exatamente 4 dígitos")
    }
}

package br.dev.evaldo.meuscooter.data

import java.util.Base64

/** Formato em disco do PIN cifrado (ADR 0006 §5): Base64 de `[tamanho do IV (1 byte)] ‖ IV ‖ ciphertext`. Só texto cifrado. */
object PinBlob {
    class Parts(val iv: ByteArray, val ciphertext: ByteArray)

    fun encode(iv: ByteArray, ciphertext: ByteArray): String {
        require(iv.size in 1..255) { "IV inválido" }
        val raw = ByteArray(1 + iv.size + ciphertext.size)
        raw[0] = iv.size.toByte()
        iv.copyInto(raw, 1)
        ciphertext.copyInto(raw, 1 + iv.size)
        return Base64.getEncoder().encodeToString(raw)
    }

    /** `null` se estiver corrompido (Base64 inválido, tamanhos incoerentes ou sem texto cifrado). */
    fun decode(text: String): Parts? {
        val raw = runCatching { Base64.getDecoder().decode(text) }.getOrNull() ?: return null
        if (raw.isEmpty()) return null
        val ivLen = raw[0].toInt() and 0xFF
        if (ivLen == 0 || raw.size <= 1 + ivLen) return null
        return Parts(raw.copyOfRange(1, 1 + ivLen), raw.copyOfRange(1 + ivLen, raw.size))
    }
}

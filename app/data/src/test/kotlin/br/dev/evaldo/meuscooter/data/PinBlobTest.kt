package br.dev.evaldo.meuscooter.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Formato do que o cofre guarda em disco: [iv ‖ ciphertext] em Base64 com 1 byte de tamanho do IV. Sem PIN real. */
class PinBlobTest {
    @Test fun roundTrip() {
        val iv = ByteArray(12) { it.toByte() }
        val ct = ByteArray(20) { (it + 100).toByte() }
        val blob = PinBlob.encode(iv, ct)
        val decoded = checkNotNull(PinBlob.decode(blob))
        assertArrayEquals(iv, decoded.iv)
        assertArrayEquals(ct, decoded.ciphertext)
    }

    @Test fun rejectsMalformed() {
        listOf("", "!!!", "AA==", PinBlob.encode(ByteArray(12), ByteArray(0)).let { it.dropLast(8) }).forEach {
            assertNull("deveria recusar '$it'", PinBlob.decode(it))
        }
    }

    @Test fun rejectsAnIvLengthThatDoesNotFit() {
        // 1º byte diz 200, mas só há 3 bytes depois.
        val bad = java.util.Base64.getEncoder().encodeToString(byteArrayOf(200.toByte(), 1, 2, 3))
        assertNull(PinBlob.decode(bad))
    }

    @Test fun emptyCiphertextIsRejected() {
        assertNull(PinBlob.decode(PinBlob.encode(ByteArray(12), ByteArray(0))))
    }

    @Test fun ivLengthIsTheFirstByte() {
        val raw = java.util.Base64.getDecoder().decode(PinBlob.encode(ByteArray(12), ByteArray(5)))
        assertEquals(12, raw[0].toInt())
        assertEquals(1 + 12 + 5, raw.size)
    }
}

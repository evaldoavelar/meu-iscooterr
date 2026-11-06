package br.dev.evaldo.meuscooter.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** PIN sintético 1357 (nunca o real). */
class PinTest {
    @Test fun acceptsExactlyFourDigits() {
        assertTrue(Pin.parse("1357") != null)
        assertTrue(Pin.parse("0000") != null)
        listOf("", "1", "135", "13579", "12a4", " 135", "135 ", "١٢٣٤", "-123", "+123", "12.3").forEach {
            assertNull("deveria recusar '$it'", Pin.parse(it))
        }
    }

    @Test fun toStringIsMasked() {
        val pin = checkNotNull(Pin.parse("1357"))
        assertEquals("****", pin.toString())
        assertEquals("Unlock(pin=****)", TxCommand.Unlock(pin).toString())
    }

    @Test fun ofThrowsWithoutRevealingTheDigits() {
        val e = runCatching { Pin.of("13579") }.exceptionOrNull()
        assertTrue(e is IllegalArgumentException)
        assertFalse(e!!.message.orEmpty().contains("1357"))
        assertFalse(e.stackTraceToString().contains("13579"))
    }

    @Test fun wipeZeroesTheDigitsAndAWipedPinCannotBuildAFrame() {
        val pin = Pin.of("1357")
        pin.wipe()
        assertTrue(pin.isWiped)
        assertEquals("****", pin.toString())
        assertTrue(runCatching { TxFrameBuilder.build(TxCommand.Unlock(pin)) }.isFailure)
    }

    @Test fun sameAsComparesDigitsWithoutExposingThem() {
        assertTrue(Pin.of("1357").sameAs(Pin.of("1357")))
        assertFalse(Pin.of("1357").sameAs(Pin.of("1358")))
        val wiped = Pin.of("1357").also { it.wipe() }
        assertFalse(wiped.sameAs(Pin.of("1357")))
        assertFalse(Pin.of("0000").sameAs(wiped))
    }

    @Test fun pinsAreNotComparableByValue() {
        // Sem data class: igualdade por valor facilitaria vazar o PIN em logs de asserção/coleções.
        assertFalse(Pin.of("1357") == Pin.of("1357"))
    }

    @Test fun duplicateIsIndependentOfTheOriginal() {
        val original = Pin.of("1357")
        val copy = original.duplicate()
        assertTrue(copy.sameAs(Pin.of("1357")))
        original.wipe()
        assertTrue(copy.sameAs(Pin.of("1357")))   // apagar o original não afeta a cópia
        assertEquals("****", copy.toString())
    }

    @Test fun duplicateOfAWipedPinThrowsWithoutRevealingDigits() {
        val pin = Pin.of("1357").also { it.wipe() }
        val e = runCatching { pin.duplicate() }.exceptionOrNull()
        assertTrue(e is IllegalStateException)
        assertFalse(e!!.message.orEmpty().contains("1357"))
    }

    @Test fun asciiBytesRoundTripForTheVaultViaParseOfAWipeableCharBuffer() {
        val bytes = Pin.of("0357").toAsciiBytes()
        assertEquals("0357", String(bytes, Charsets.US_ASCII))
        val chars = CharArray(bytes.size) { bytes[it].toInt().toChar() }
        val pin = checkNotNull(Pin.parse(java.nio.CharBuffer.wrap(chars)))
        chars.fill('\u0000'); bytes.fill(0)
        assertTrue(pin.sameAs(Pin.of("0357")))
    }

    @Test fun asciiBytesOfAWipedPinThrows() {
        val pin = Pin.of("1357").also { it.wipe() }
        assertTrue(runCatching { pin.toAsciiBytes() }.exceptionOrNull() is IllegalStateException)
    }
}

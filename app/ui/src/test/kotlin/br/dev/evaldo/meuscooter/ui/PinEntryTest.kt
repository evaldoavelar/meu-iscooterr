package br.dev.evaldo.meuscooter.ui

import br.dev.evaldo.meuscooter.ui.home.PinEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** S30: auto-envio ao digitar o 4º dígito (decisão do proprietário). Reducer puro; PIN sintético. */
class PinEntryTest {
    private fun press(e: PinEntry, s: String): Pair<PinEntry, List<String>> {
        var cur = e; val out = mutableListOf<String>()
        s.forEach { val (n, c) = cur.press(it); cur = n; c?.let(out::add) }
        return cur to out
    }

    @Test fun threeDigitsDoNotSend() = assertEquals(emptyList<String>(), press(PinEntry(), "135").second)

    @Test fun fourthDigitSendsExactlyOnce() = assertEquals(listOf("1357"), press(PinEntry(), "1357").second)

    @Test fun extraTapsAfterTheSendAreIgnored() = assertEquals(listOf("1357"), press(PinEntry(), "13579024").second)

    @Test fun backspaceThenRetypeSendsTheNewPinOnlyAtFour() {
        val (e, first) = press(PinEntry(), "135")
        val (e2, second) = press(e.backspace(), "2")
        assertEquals(emptyList<String>(), first + second)
        assertEquals(listOf("1327"), press(e2, "7").second)
    }

    @Test fun afterSendingBackspaceCannotReopenTheEntry() {
        val (e, _) = press(PinEntry(), "1357")
        assertEquals(e, e.backspace())
        assertNull(e.press('1').second)
    }

    @Test fun nonDigitsAreIgnored() = assertEquals(emptyList<String>(), press(PinEntry(), "12ab").second)

    @Test fun doubleEventOnTheFourthKeyIsOneSend() {
        val (e, _) = press(PinEntry(), "135")
        val (e2, c1) = e.press('7'); val (_, c2) = e2.press('7')
        assertEquals("1357", c1); assertNull(c2)
    }
}

package br.dev.evaldo.meuscooter

import org.junit.Assert.assertEquals
import org.junit.Test

class SvcLogTest {
    @Test fun eventWithoutFields() = assertEquals("onCreate", formatSvcLogLine("onCreate"))

    @Test fun fieldsInOrderWithNullAsDash() =
        assertEquals("fg ok type=10 err=-", formatSvcLogLine("fg ok", listOf("type" to 10, "err" to null)))

    @Test fun whitespaceBecomesUnderscoreAndEmptyBecomesDash() =
        assertEquals("fg msg=not_allowed_here empty=-", formatSvcLogLine("fg", listOf("msg" to "not allowed\nhere", "empty" to "")))

    @Test fun longValueIsTruncatedTo80() {
        val line = formatSvcLogLine("x", listOf("m" to "a".repeat(200)))
        assertEquals("x m=" + "a".repeat(80), line)
    }
}

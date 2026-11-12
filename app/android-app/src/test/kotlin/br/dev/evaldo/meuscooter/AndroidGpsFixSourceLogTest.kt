package br.dev.evaldo.meuscooter

import java.util.Locale
import org.junit.Test
import org.junit.Assert.assertEquals

class AndroidGpsFixSourceLogTest {
    @Test fun formatsWithSpeed() {
        assertEquals(
            "t=1700000000000 et=5000 lat=-23.5 lon=-46.25 acc=4.5 spd=3.0 brg=90.0",
            formatGpsLogLine(1_700_000_000_000L, 5_000_000_000L, -23.5, -46.25, 4.5f, 3.0f, 90.0f),
        )
    }

    @Test fun noSpeedPrintsDash() {
        assertEquals(
            "t=1 et=0 lat=1.0 lon=2.0 acc=10.0 spd=- brg=0.0",
            formatGpsLogLine(1L, 0L, 1.0, 2.0, 10.0f, null, 0.0f),
        )
    }

    @Test fun usesDecimalPointRegardlessOfDefaultLocale() {
        val old = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("pt-BR"))
            assertEquals("t=1 et=1 lat=1.5 lon=2.5 acc=3.5 spd=1.5 brg=2.5", formatGpsLogLine(1L, 1_000_000L, 1.5, 2.5, 3.5f, 1.5f, 2.5f))
        } finally { Locale.setDefault(old) }
    }
}

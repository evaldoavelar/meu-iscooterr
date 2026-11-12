package br.dev.evaldo.meuscooter.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import br.dev.evaldo.meuscooter.domain.TripRecState
import br.dev.evaldo.meuscooter.domain.TripSummary
import br.dev.evaldo.meuscooter.protocol.Telemetry
import br.dev.evaldo.meuscooter.ui.telemetry.TelemetryScreen
import br.dev.evaldo.meuscooter.ui.theme.AppTheme
import br.dev.evaldo.meuscooter.ui.theme.MeuPatineteTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

/** S47: o rotulo ("Parada" sem patinete desde a S48, "Contando" com patinete) usa a cor `error` do tema sem patinete e o verde `success` com patinete (pixel, como TripBarAlphaTest). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class TelemetryNoScooterColorTest {
    @get:Rule val rule = createComposeRule()

    private fun argb(c: Color) = intArrayOf((c.red * 255).toInt(), (c.green * 255).toInt(), (c.blue * 255).toInt())

    /** Cor do pixel do rotulo mais distante do fundo do cartao (o miolo do traco antialiased); devolve (cor, success, error) do tema. */
    private fun labelColor(dark: Boolean, stale: Boolean): Triple<IntArray, IntArray, IntArray> {
        var success = Color.Unspecified; var error = Color.Unspecified
        rule.setContent {
            MeuPatineteTheme(darkTheme = dark) {
                success = AppTheme.extended.success; error = AppTheme.extended.error
                TelemetryScreen(
                    TelemetryUiMapper().map(Telemetry(receivedAtMs = 1, speedKmh = 18f, scooterSpeedKmh = 18f), TripSummary(2.5f, 760_000, 16.2f, 27f), stale = stale, recState = TripRecState.Running),
                    onBack = {}, onOpenHistory = {},
                )
            }
        }
        rule.waitForIdle()
        val bmp = rule.onRoot().captureToImage().asAndroidBitmap()
        val b = rule.onNodeWithTag("rec-state", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val bg = bmp.getPixel(b.left.toInt() - 4, b.center.y.toInt())
        var best = 0; var bestD = -1
        for (x in b.left.toInt()..b.right.toInt()) for (y in b.top.toInt()..b.bottom.toInt()) {
            val p = bmp.getPixel(x, y)
            val d = abs((p shr 16 and 255) - (bg shr 16 and 255)) + abs((p shr 8 and 255) - (bg shr 8 and 255)) + abs((p and 255) - (bg and 255))
            if (d > bestD) { bestD = d; best = p }
        }
        return Triple(intArrayOf(best shr 16 and 255, best shr 8 and 255, best and 255), argb(success), argb(error))
    }

    /** Tolerancia 40 (soma dos 3 canais): o traco fino do texto e antialiased, o pixel mais forte nao e 100% a cor cheia. */
    private fun near(a: IntArray, b: IntArray) = (0..2).sumOf { abs(a[it] - b[it]) } <= 40
    private fun far(a: IntArray, b: IntArray) = (0..2).sumOf { abs(a[it] - b[it]) } > 100

    private fun check(dark: Boolean) {
        val (off, ok, err) = labelColor(dark, stale = true)
        assertTrue("sem patinete deve ser error: pixel=${off.toList()} error=${err.toList()}", near(off, err) && far(off, ok))
    }

    @Test fun `sem patinete o Contando e vermelho no tema claro`() = check(dark = false)
    @Test fun `sem patinete o Contando e vermelho no tema escuro`() = check(dark = true)

    private fun checkFresh(dark: Boolean) {
        val (on, ok, err) = labelColor(dark, stale = false)
        assertTrue("dark=$dark: pixel=${on.toList()} success=${ok.toList()}", near(on, ok) && far(on, err))
    }

    @Test fun `com patinete o Contando segue verde no tema claro`() = checkFresh(dark = false)
    @Test fun `com patinete o Contando segue verde no tema escuro`() = checkFresh(dark = true)
}

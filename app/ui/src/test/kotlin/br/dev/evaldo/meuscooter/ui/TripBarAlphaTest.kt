package br.dev.evaldo.meuscooter.ui

import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import br.dev.evaldo.meuscooter.domain.TripRecState
import br.dev.evaldo.meuscooter.domain.TripSummary
import br.dev.evaldo.meuscooter.protocol.Telemetry
import br.dev.evaldo.meuscooter.ui.telemetry.TelemetryScreen
import br.dev.evaldo.meuscooter.ui.theme.MeuPatineteTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

/** S47: o alpha 0,38 dos botoes desabilitados e verificado por pixel (captureToImage, como HomeScreenshotTest). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class TripBarAlphaTest {
    @get:Rule val rule = createComposeRule()
    private var rec by mutableStateOf(TripRecState.Running)

    private fun channels(c: Int) = intArrayOf(c shr 16 and 255, c shr 8 and 255, c and 255)

    /** Pixel do centro do glifo do Parar e pixel de fundo da barra (borda esquerda da celula, na mesma altura). */
    private fun sample(): Pair<IntArray, IntArray> {
        val bmp = rule.onRoot().captureToImage().asAndroidBitmap()
        val g = rule.onNodeWithTag("trip-stop-glyph", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val cell = rule.onNodeWithTag("trip-stop", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val y = g.center.y.toInt()
        return channels(bmp.getPixel(g.center.x.toInt(), y)) to channels(bmp.getPixel(cell.left.toInt() + 6, y))
    }

    @Test fun `parar desabilitado e a mistura 0_38 do enabled com o fundo`() {
        rule.setContent {
            MeuPatineteTheme(darkTheme = false) {
                TelemetryScreen(
                    TelemetryUiMapper().map(Telemetry(receivedAtMs = 1, speedKmh = 18f, scooterSpeedKmh = 18f), TripSummary(2.5f, 760_000, 16.2f, 27f), recState = rec),
                    onBack = {}, onOpenHistory = {}, onPlayPause = {}, onStop = {}, onReset = {},
                )
            }
        }
        rule.waitForIdle()
        val (on, bg) = sample()
        rec = TripRecState.Stopped
        rule.waitForIdle()
        val (off, bgOff) = sample()
        val expected = IntArray(3) { Math.round(0.38f * on[it] + 0.62f * bg[it]) }
        // tolerancia 3/255 por canal: arredondamento do alpha (0,38 x 255 = 96,9) e do compositing
        for (i in 0..2) assertTrue("canal $i off=${off[i]} esperado=${expected[i]} on=${on[i]} bg=${bg[i]}", abs(off[i] - expected[i]) <= 3)
        assertTrue("disabled precisa diferir do enabled", (0..2).sumOf { abs(off[it] - on[it]) } > 60)
        assertTrue("fundo estavel", (0..2).all { abs(bgOff[it] - bg[it]) <= 2 })
    }
}

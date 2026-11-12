package br.dev.evaldo.meuscooter.ui

import android.graphics.Bitmap
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import br.dev.evaldo.meuscooter.ui.history.TripDetailScreen
import br.dev.evaldo.meuscooter.ui.theme.MeuPatineteTheme
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** S4/S5: fixture visual independente, transcrita dos seis PNGs aprovados e design/s48.py. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h780dp-xhdpi")
class TripAltimetryScreenTest {
    /**
     * Corte real: altura estourada ou linha mais larga que a caixa. `hasVisualOverflow` não serve:
     * em Text com largura ajustada ao conteúdo, o Compose marca didOverflowWidth porque a caixa
     * (94 px) é menor que a largura de layout (288 px), sem nenhum texto cortado.
     */
    private fun TextLayoutResult.clipped() =
        didOverflowHeight || (0 until lineCount).any { getLineRight(it) > size.width + 0.5f }

    @get:Rule val rule = createComposeRule()
    private val filled = TripAltimetryUi(
        "+18 m", "−12 m", "742 m", "754 m", "0 km", "4,8 km",
        listOf(listOf(742, 746, 751, 747, 754, 750, 747, 749, 748).mapIndexed { i, h ->
            AltitudeProfilePoint(i / 8f, (h - 742) / 12f)
        }),
    )

    private fun verify(dark: Boolean, scale: Float = 1f, hasAltitude: Boolean = true) {
        val detail = TripDetailUi("Hoje, 18:42", "4,8 km", "21 min", "14 km/h", "27 km/h",
            if (hasAltitude) "+18 m" else "Sem leitura", "24 Wh/km", "82% → 76%", altimetry = filled.takeIf { hasAltitude })
        rule.setContent {
            MeuPatineteTheme(darkTheme = dark) {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, scale)) {
                    TripDetailScreen(detail, emptyList(), {}, onNotice = {})
                }
            }
        }
        val theme = if (dark) "escuro" else "claro"
        val name = if (!hasAltitude) "sem-leitura" else if (scale == 2f) "preenchida-fonte2" else "preenchida"
        val card = rule.onNodeWithTag("trip-altimetry").performScrollTo().assertIsDisplayed()
        if (hasAltitude) {
            val positions = listOf("gain", "loss", "min", "max").map { key ->
                val node = rule.onNodeWithTag("trip-altimetry-$key").fetchSemanticsNode()
                val layouts = mutableListOf<TextLayoutResult>()
                node.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts)
                assertFalse("$key cortado", layouts.single().clipped())
                assertEquals(1, layouts.single().lineCount)
                assertEquals(16f, layouts.single().layoutInput.style.fontSize.value, 0.01f)
                node.boundsInRoot
            }
            listOf("label-gain", "label-loss", "label-min", "label-max", "axis-start", "axis-end", "axis-min", "axis-max").forEach { tag ->
                val layouts = mutableListOf<TextLayoutResult>()
                rule.onNodeWithTag("trip-altimetry-$tag").fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts)
                assertFalse("$tag cortado", layouts.single().clipped())
            }
            if (scale == 2f) {
                assertEquals(1, positions.map { it.left }.distinct().size)
                assertTrue(positions.zipWithNext().all { (a, b) -> b.top > a.bottom })
            } else {
                assertEquals(positions[0].top, positions[1].top, 1f)
                assertEquals(positions[2].top, positions[3].top, 1f)
            }
            val profile = rule.onNodeWithTag("trip-altimetry-profile").assertIsDisplayed().captureToImage().asAndroidBitmap()
            // S5: verifica traço e preenchimento da fixture do PNG, sem calibrar limiar pela implementação.
            val cyan = if (dark) 0x00E5FF else 0x00A9C0
            fun near(a: Int, b: Int) = listOf(16, 8, 0).all { abs((a shr it and 255) - (b shr it and 255)) <= 8 }
            // Em xhdpi o traço de 2 dp tem 4 px; margem de 3 px cobre rasterização/antialiasing.
            val heights = listOf(742, 746, 751, 747, 754, 750, 747, 749, 748)
            for (i in 0 until 8) {
                val x = (2 + (i + 0.5) / 8 * (profile.width - 4)).roundToInt()
                val midHeight = (heights[i] + heights[i + 1]) / 2.0
                val y = (2 + (754 - midHeight) / 12 * (profile.height - 4)).roundToInt()
                assertTrue("trecho $i do perfil ausente", (-3..3).any { dy ->
                    near(profile.getPixel(x, (y + dy).coerceIn(0, profile.height - 1)), cyan)
                })
            }
            val area = if (dark) 0x113444 else 0xE0F5F7
            assertTrue("preenchimento a 12% ausente", near(profile.getPixel(profile.width / 2, profile.height * 9 / 10), area))
        } else {
            rule.onNodeWithTag("trip-altimetry-empty").assertIsDisplayed()
            rule.onNodeWithTag("trip-altimetry-profile").assertDoesNotExist()
            rule.onNodeWithTag("trip-altimetry-gain").assertDoesNotExist()
        }
        val bitmap = card.captureToImage().asAndroidBitmap()
        File("build/screenshots/$theme").mkdirs()
        File("build/screenshots/$theme/altimetria-v1-$name-card.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        rule.onNodeWithText("82% → 76%").performScrollTo().assertIsDisplayed()
        val layouts = mutableListOf<TextLayoutResult>()
        rule.onNodeWithText("82% → 76%").fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts)
        assertFalse("bateria cortada", layouts.single().clipped())
        rule.onNodeWithText("Trajetos e viagens ficam salvos só no seu celular.").performScrollTo().assertIsDisplayed()
    }

    @Test fun `preenchida claro`() = verify(false)
    @Test fun `preenchida escuro`() = verify(true)
    @Test fun `sem leitura claro`() = verify(false, hasAltitude = false)
    @Test fun `sem leitura escuro`() = verify(true, hasAltitude = false)
    @Test fun `fonte 2 claro sem corte`() = verify(false, 2f)
    @Test fun `fonte 2 escuro sem corte`() = verify(true, 2f)
}

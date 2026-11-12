package br.dev.evaldo.meuscooter.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import br.dev.evaldo.meuscooter.domain.GeoPoint
import br.dev.evaldo.meuscooter.domain.TileId
import br.dev.evaldo.meuscooter.domain.TileLoader
import br.dev.evaldo.meuscooter.domain.TileResult
import br.dev.evaldo.meuscooter.ui.history.LocalMapTiles
import br.dev.evaldo.meuscooter.ui.history.MapTiles
import br.dev.evaldo.meuscooter.ui.history.RouteMap
import br.dev.evaldo.meuscooter.ui.theme.MeuPatineteTheme
import java.io.File
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** S5: geometria e cores dos controles vs os oito PNGs S48 aprovados; mapa sintético, sem rede.
 * Ruas esquemáticas do protótipo não são comparadas a tiles. Capturas completas do mapa ficam em build/screenshots/s49.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h780dp-xhdpi")
class RouteMapZoomScreenshotTest {
    @get:Rule val rule = createComposeRule()
    private var frame by mutableStateOf("ajustado")
    private var dark by mutableStateOf(false)

    @Test fun `quatro estados nos dois temas preservam controles e escala dos PNGs aprovados`() {
        // A latitude da fixture dá ~100 m em 78 dp no zoom inicial e ~50 m no zoom 2×.
        val route = listOf(GeoPoint(-74.0, 0.0), GeoPoint(-74.0, 0.004))
        val loader = object : TileLoader {
            override suspend fun load(tile: TileId) = TileResult.Loaded(byteArrayOf(1), false)
            override fun invalidate(tile: TileId) = Unit
        }
        val tiles = MapTiles(loader, "© colaboradores do OpenStreetMap", { ImageBitmap(2, 2) }, Dispatchers.Unconfined)
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, if (frame.endsWith("fonte2")) 2f else 1f), LocalMapTiles provides tiles) {
                MeuPatineteTheme(darkTheme = dark) {
                    key(frame, dark) {
                        Column(Modifier.padding(horizontal = 16.dp)) {
                            RouteMap(route, Modifier.fillMaxWidth().height(260.dp).testTag("zoom-frame"))
                        }
                    }
                }
            }
        }
        for (isDark in listOf(false, true)) for (state in listOf("ajustado", "aproximado", "recentrar", "aproximado-fonte2")) {
            rule.runOnIdle { frame = state; dark = isDark }
            if (state != "ajustado") rule.onNodeWithTag("map-zoom-in").performClick()
            if (state == "recentrar") rule.onNodeWithTag("route-map").performTouchInput {
                swipe(Offset(100f, 200f), Offset(160f, 250f), 300)
            }
            rule.mainClock.advanceTimeBy(160)
            rule.onNodeWithText(if (state == "ajustado") "100 m" else "50 m").assertExists()
            val shot = rule.onNodeWithTag("zoom-frame").captureToImage().asAndroidBitmap()
            val theme = if (isDark) "escuro" else "claro"
            val name = "mapa-zoom-v1-$state-$theme"
            val out = File("build/screenshots/s49/$name.png").also { it.parentFile?.mkdirs() }
            out.outputStream().use { shot.compress(Bitmap.CompressFormat.PNG, 100, it) }
            val ref = requireNotNull(BitmapFactory.decodeFile("../../design/mockups/$name.png"))
            assertEquals(656, shot.width)
            assertEquals(520, shot.height)
            val mapTop = if (state.endsWith("fonte2")) 296 else 260
            // Pontos internos dos fundos de +, − e centralizar, longe de ícones, bordas e textos.
            for (y in listOf(64, 160, 272)) {
                val x = 560
                val expected = ref.getPixel(32 + x, mapTop + y)
                val actual = shot.getPixel(x, y)
                assertTrue("$name controle y=$y", listOf(16, 8, 0).all {
                    kotlin.math.abs((expected shr it and 255) - (actual shr it and 255)) <= 3
                })
            }
            val bounds = rule.onNodeWithTag("map-recenter").fetchSemanticsNode().boundsInRoot
            val map = rule.onNodeWithTag("zoom-frame").fetchSemanticsNode().boundsInRoot
            assertEquals(96f, bounds.width, 0.1f)
            assertEquals(map.right - 16f, bounds.right, 0.1f)
            assertEquals(map.top + 224f, bounds.top, 0.1f)
            if (state != "ajustado") rule.onNodeWithTag("map-recenter").performClick().assertIsNotEnabled()
        }
    }
}

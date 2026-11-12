package br.dev.evaldo.meuscooter.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
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
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** S4: gestos no mapa dentro de uma tela rolável, sem rede e sem obter localização. */
@RunWith(RobolectricTestRunner::class)
// Gráficos nativos: ImageBitmap real exige Bitmap nativo no Robolectric.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h780dp-xhdpi")
class RouteMapZoomTest {
    @get:Rule val rule = createComposeRule()
    // Fixture no equador: o zoom inicial é limitado a 16, com tiles do nível 17 após ampliar.
    private val route = listOf(GeoPoint(0.0, 0.0), GeoPoint(0.0, 0.002))
    private val requested = mutableListOf<TileId>()
    private var scrollValue = { 0 }

    private fun show(fontScale: Float = 1f) {
        val loader = object : TileLoader {
            override suspend fun load(tile: TileId): TileResult {
                requested += tile
                return TileResult.Loaded(byteArrayOf(1), false)
            }
            override fun invalidate(tile: TileId) = Unit
        }
        val tiles = MapTiles(loader, "© colaboradores do OpenStreetMap", decode = { ImageBitmap(2, 2) }, decodeDispatcher = Dispatchers.Unconfined)
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale), LocalMapTiles provides tiles) {
                MeuPatineteTheme(darkTheme = false) {
                    val scroll = rememberScrollState()
                    scrollValue = { scroll.value }
                    Column(Modifier.verticalScroll(scroll)) {
                        RouteMap(route, Modifier.fillMaxWidth().height(260.dp))
                        Spacer(Modifier.fillMaxWidth().height(1500.dp).testTag("outside-map"))
                    }
                }
            }
        }
        rule.waitUntil(5_000) { requested.isNotEmpty() }
    }

    /** Avança o relógio virtual além da permanência mínima de 120 ms antes de cada pedido de tile. */
    private fun advancePastTileDelay() {
        rule.waitForIdle() // aplica a recomposição do gesto antes de correr o relógio virtual
        rule.mainClock.advanceTimeBy(1_000)
        rule.waitForIdle()
    }

    @Test fun `botoes acessiveis dobram zoom e restauram o trajeto inteiro`() {
        show()
        val recenter = rule.onNodeWithContentDescription("Centralizar trajeto inteiro")
        recenter.assertIsNotEnabled()
        rule.onNodeWithContentDescription("Ampliar mapa").assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp).performClick()
        recenter.assertIsEnabled()
        rule.onNodeWithContentDescription("Reduzir mapa").performClick()
        recenter.assertIsNotEnabled()
        rule.onNodeWithTag("map-zoom-in").performClick()
        recenter.performClick().assertIsNotEnabled()
    }

    @Test fun `duplo toque e pinca aproximam e arrasto nao rola a tela`() {
        show()
        val map = rule.onNodeWithTag("route-map")
        map.performTouchInput { doubleClick(center) }
        rule.onNodeWithTag("map-recenter").assertIsEnabled().performClick()
        map.performTouchInput {
            pinch(start0 = Offset(center.x - 20f, center.y), end0 = Offset(center.x - 70f, center.y),
                start1 = Offset(center.x + 20f, center.y), end1 = Offset(center.x + 70f, center.y))
        }
        rule.onNodeWithTag("map-recenter").assertIsEnabled().performClick()
        map.performTouchInput { swipe(Offset(50f, 150f), Offset(50f, 50f), 300) }
        rule.onNodeWithTag("map-recenter").assertIsEnabled()
        rule.runOnIdle { assertEquals(0, scrollValue()) }
        rule.onNodeWithTag("outside-map").performTouchInput { swipe(Offset(50f, 300f), Offset(50f, 50f), 300) }
        rule.runOnIdle { assertTrue(scrollValue() > 0) }
    }

    @Test fun `fonte dois mantem escala atribuicao e controles inteiros`() {
        show(fontScale = 2f)
        rule.onNodeWithTag("map-zoom-in").performClick()
        // Os tiles do novo nível só são pedidos após a permanência mínima de 120 ms.
        rule.waitUntil(5_000) { rule.onAllNodesWithTag("map-attribution").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("map-scale").assertIsDisplayed()
        rule.onNodeWithText("© colaboradores do OpenStreetMap").assertIsDisplayed()
        listOf("map-zoom-in", "map-zoom-out", "map-recenter").forEach {
            rule.onNodeWithTag(it).assertIsDisplayed().assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
        }
        val node = rule.onNodeWithTag("map-attribution").fetchSemanticsNode()
        val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        node.config[androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
        assertFalse(layouts.single().hasVisualOverflow)
        val recenter = rule.onNodeWithTag("map-recenter").fetchSemanticsNode().boundsInRoot
        assertTrue("atribuição não deve cobrir o controle", node.boundsInRoot.top >= recenter.bottom)
    }

    @Test fun `pan pequeno reutiliza os tiles e zoom carrega somente o nivel visivel`() {
        show()
        rule.waitForIdle()
        val initial = requested.toSet()
        assertTrue(initial.isNotEmpty())
        rule.onNodeWithTag("route-map").performTouchInput { swipe(Offset(100f, 200f), Offset(160f, 200f), 300) }
        rule.onNodeWithTag("map-recenter").assertIsEnabled()
        rule.runOnIdle { assertEquals(requested.distinct(), requested) }
        rule.onNodeWithTag("map-recenter").performClick()
        rule.onNodeWithTag("map-zoom-in").performClick()
        advancePastTileDelay()
        assertTrue(requested.any { it.zoom == initial.first().zoom + 1 })
        assertTrue(requested.all { it.zoom in initial.first().zoom..initial.first().zoom + 1 })
        val count = requested.size
        rule.onNodeWithTag("map-recenter").performClick()
        rule.runOnIdle { assertEquals(count, requested.size) }
    }
}


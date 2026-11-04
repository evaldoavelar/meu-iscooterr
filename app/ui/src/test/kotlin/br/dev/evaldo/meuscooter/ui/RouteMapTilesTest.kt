package br.dev.evaldo.meuscooter.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import br.dev.evaldo.meuscooter.domain.GeoPoint
import br.dev.evaldo.meuscooter.domain.TileId
import br.dev.evaldo.meuscooter.domain.TileLoader
import br.dev.evaldo.meuscooter.domain.TileResult
import br.dev.evaldo.meuscooter.ui.history.LocalMapTiles
import br.dev.evaldo.meuscooter.ui.history.MapTiles
import br.dev.evaldo.meuscooter.ui.history.RouteMap
import br.dev.evaldo.meuscooter.ui.theme.MeuPatineteTheme
import java.util.Base64
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** S4 com carregador falso (sem rede). Atribuição do OSM é obrigação da licença: sempre que houver tile desenhado. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
@OptIn(ExperimentalCoroutinesApi::class)
class RouteMapTilesTest {
    @get:Rule val rule = createComposeRule()
    private val decodeScheduler = TestCoroutineScheduler()
    private val decodeDispatcher = StandardTestDispatcher(decodeScheduler)

    private val route = listOf(GeoPoint(-23.55, -46.63), GeoPoint(-23.54, -46.62), GeoPoint(-23.53, -46.60))
    private val attribution = "© colaboradores do OpenStreetMap"
    private val png = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==")

    private class FakeLoader(val result: (TileId) -> TileResult) : TileLoader {
        val requested = mutableListOf<TileId>()
        val invalidated = mutableListOf<TileId>()
        override fun invalidate(tile: TileId) { invalidated += tile }
        override suspend fun load(tile: TileId): TileResult { requested += tile; return result(tile) }
    }

    private fun show(loader: TileLoader?, thumb: Boolean = false, decode: ((ByteArray) -> ImageBitmap?)? = null) = rule.setContent {
        MeuPatineteTheme(darkTheme = false) {
            CompositionLocalProvider(LocalMapTiles provides loader?.let {
                if (decode != null) MapTiles(it, attribution, decode, decodeDispatcher)
                else MapTiles(it, attribution, decodeDispatcher = decodeDispatcher)
            }) {
                RouteMap(route, if (thumb) Modifier.size(72.dp) else Modifier.fillMaxWidth().height(260.dp), thumb = thumb)
            }
        }
    }

    private fun finishDecoding() {
        // Compose first queues every decode; the test then executes them all, without a real worker thread.
        rule.runOnIdle { decodeScheduler.runCurrent() }
        rule.waitForIdle()
    }

    private fun waitFor(tag: String) {
        finishDecoding()
        rule.waitUntil(15_000) { rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun `com tiles desenhados mostra a atribuicao do OSM e o traçado continua visivel`() {
        show(FakeLoader { TileResult.Loaded(png, stale = false) })
        waitFor("map-attribution")
        rule.onNodeWithText(attribution).assertIsDisplayed()
        rule.onNodeWithTag("route-map").assertIsDisplayed()
        rule.onAllNodesWithTag("map-offline").assertCountIs0()
    }

    @Test fun `sem internet o traçado fica sobre fundo neutro com aviso discreto e sem atribuicao`() {
        show(FakeLoader { TileResult.Unavailable })
        waitFor("map-offline")
        rule.onNodeWithText("Mapa indisponível sem internet").assertIsDisplayed()
        rule.onNodeWithTag("route-map").assertIsDisplayed()
        rule.onAllNodesWithTag("map-attribution").assertCountIs0()
    }

    @Test fun `enquanto os tiles carregam nao ha atribuicao nem aviso e a UI responde`() {
        val gate = CompletableDeferred<Unit>()
        val loader = object : TileLoader { override suspend fun load(tile: TileId): TileResult { gate.await(); return TileResult.Loaded(png, false) }
            override fun invalidate(tile: TileId) { } }
        show(loader)
        rule.onNodeWithTag("route-map").assertIsDisplayed()
        rule.onAllNodesWithTag("map-attribution").assertCountIs0()
        rule.onAllNodesWithTag("map-offline").assertCountIs0()
        rule.runOnIdle { gate.complete(Unit) }
        waitFor("map-attribution")
    }

    @Test fun `bytes que nao decodificam viram indisponivel e sao descartados do cache`() {
        val loader = FakeLoader { TileResult.Loaded(byteArrayOf(1, 2, 3), stale = false) }
        show(loader, decode = { null })
        rule.onAllNodesWithTag("map-offline").assertCountIs0()
        rule.runOnIdle { assertTrue(loader.invalidated.isEmpty()) }
        waitFor("map-offline")
        rule.onAllNodesWithTag("map-attribution").assertCountIs0()
        rule.runOnIdle {
            assertTrue(loader.requested.isNotEmpty())
            assertEquals(loader.requested.toSet(), loader.invalidated.toSet())
            assertEquals(loader.requested.size, loader.invalidated.size)
        }
    }

    @Test fun `tile e realmente desenhado sob o traçado (pixel do canto vem do tile)`() {
        val red = ImageBitmap(2, 2).also { Canvas(it).drawRect(Rect(0f, 0f, 2f, 2f), Paint().apply { color = Color.Red }) }
        val loader = FakeLoader { TileResult.Loaded(png, false) }
        var decoded = 0
        show(loader, decode = { decoded++; red })
        rule.onAllNodesWithTag("map-attribution").assertCountIs0()
        waitFor("map-attribution")
        rule.runOnIdle { assertEquals(loader.requested.size, decoded) }
        val px = rule.onNodeWithTag("route-map").captureToImage().toPixelMap()[40, 40]
        assertEquals(Color.Red, px)
    }

    @Test fun `ao mudar o tamanho da area a atribuicao some ate os novos tiles desenharem`() {
        var block = false
        val loader = object : TileLoader {
            override suspend fun load(tile: TileId): TileResult { if (block) CompletableDeferred<Unit>().await(); return TileResult.Loaded(png, false) }
            override fun invalidate(tile: TileId) { }
        }
        var h by mutableStateOf(260.dp)
        rule.setContent {
            MeuPatineteTheme(darkTheme = false) {
                CompositionLocalProvider(LocalMapTiles provides MapTiles(loader, attribution)) { RouteMap(route, Modifier.fillMaxWidth().height(h)) }
            }
        }
        waitFor("map-attribution")
        block = true
        rule.runOnIdle { h = 200.dp }
        rule.waitUntil(15_000) { rule.onAllNodesWithTag("map-attribution").fetchSemanticsNodes().isEmpty() }
    }

    @Test fun `rotation cancels obsolete requests and only replacement tiles update the map`() {
        val oldGate = CompletableDeferred<TileResult>()
        val newGate = CompletableDeferred<TileResult>()
        val oldRequests = mutableListOf<TileId>()
        val cancelled = mutableListOf<TileId>()
        val newRequests = mutableListOf<TileId>()
        var rotated = false
        var height by mutableStateOf(260.dp)
        var decoded = 0
        val bitmap = ImageBitmap(2, 2)
        val loader = object : TileLoader {
            override suspend fun load(tile: TileId): TileResult {
                if (rotated) {
                    newRequests += tile
                    return newGate.await()
                }
                oldRequests += tile
                try {
                    return oldGate.await()
                } catch (e: CancellationException) {
                    cancelled += tile
                    throw e
                }
            }
            override fun invalidate(tile: TileId) { error("No corrupt tile in this fixture") }
        }
        rule.setContent {
            MeuPatineteTheme(darkTheme = false) {
                CompositionLocalProvider(LocalMapTiles provides MapTiles(loader, attribution,
                    decode = { decoded++; bitmap }, decodeDispatcher = decodeDispatcher)) {
                    RouteMap(route, Modifier.fillMaxWidth().height(height))
                }
            }
        }
        rule.runOnIdle {
            assertTrue(oldRequests.isNotEmpty())
            rotated = true
            height = 200.dp
        }
        rule.runOnIdle {
            assertEquals(oldRequests.toSet(), cancelled.toSet())
            assertTrue(newRequests.isNotEmpty())
            oldGate.complete(TileResult.Unavailable)
        }
        finishDecoding()
        rule.onAllNodesWithTag("map-attribution").assertCountIs0()
        rule.onAllNodesWithTag("map-offline").assertCountIs0()
        rule.runOnIdle { newGate.complete(TileResult.Loaded(png, false)) }
        waitFor("map-attribution")
        rule.onAllNodesWithTag("map-offline").assertCountIs0()
        rule.runOnIdle { assertEquals(newRequests.size, decoded) }
    }

    @Test fun `tocar na atribuicao abre a pagina de copyright do OSM`() {
        val opened = mutableListOf<String>()
        val handler = object : androidx.compose.ui.platform.UriHandler { override fun openUri(uri: String) { opened += uri } }
        rule.setContent {
            MeuPatineteTheme(darkTheme = false) {
                CompositionLocalProvider(
                    androidx.compose.ui.platform.LocalUriHandler provides handler,
                    LocalMapTiles provides MapTiles(FakeLoader { TileResult.Loaded(png, false) }, attribution),
                ) { RouteMap(route, Modifier.fillMaxWidth().height(260.dp)) }
            }
        }
        waitFor("map-attribution")
        rule.onNodeWithText(attribution).performClick()
        assertEquals(listOf("https://www.openstreetmap.org/copyright"), opened)
    }

    @Test fun `miniatura nunca pede tiles e nao tem atribuicao`() {
        val loader = FakeLoader { TileResult.Loaded(png, false) }
        show(loader, thumb = true)
        rule.onNodeWithTag("route-map").assertIsDisplayed()
        rule.waitForIdle()
        assertTrue(loader.requested.isEmpty())
        rule.onAllNodesWithTag("map-attribution").assertCountIs0()
    }

    @Test fun `sem provedor configurado nao ha tiles nem atribuicao`() {
        show(null)
        rule.onNodeWithTag("route-map").assertIsDisplayed()
        rule.onAllNodesWithTag("map-attribution").assertCountIs0()
    }

    @Test fun `pede apenas os tiles do enquadramento do trajeto`() {
        val loader = FakeLoader { TileResult.Unavailable }
        show(loader)
        waitFor("map-offline")
        assertTrue(loader.requested.isNotEmpty())
        assertEquals(loader.requested.distinct(), loader.requested)
        assertTrue(loader.requested.size <= 12)
    }

    private fun androidx.compose.ui.test.SemanticsNodeInteractionCollection.assertCountIs0() = assertCountEquals(0)
}

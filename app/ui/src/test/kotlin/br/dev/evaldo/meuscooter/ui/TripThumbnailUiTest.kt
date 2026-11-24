package br.dev.evaldo.meuscooter.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.unit.Density
import br.dev.evaldo.meuscooter.domain.GeoPoint
import br.dev.evaldo.meuscooter.domain.TileId
import br.dev.evaldo.meuscooter.domain.TileLoader
import br.dev.evaldo.meuscooter.domain.TileResult
import br.dev.evaldo.meuscooter.domain.Trip
import br.dev.evaldo.meuscooter.ui.history.HistoryScreen
import br.dev.evaldo.meuscooter.ui.history.LocalMapTiles
import br.dev.evaldo.meuscooter.ui.history.MapTiles
import br.dev.evaldo.meuscooter.ui.theme.MeuPatineteTheme
import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** S52: cartão do Histórico com e sem miniatura de mapa (variante A aprovada); a miniatura é decorativa e nunca usa a rede. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class TripThumbnailUiTest {
    @get:Rule val rule = createComposeRule()

    private val zone = ZoneOffset.UTC
    private val now = ZonedDateTime.of(2026, 11, 3, 20, 0, 0, 0, zone).toInstant().toEpochMilli()
    private val trip = Trip(7, ZonedDateTime.of(2026, 11, 3, 8, 15, 0, 0, zone).toInstant().toEpochMilli(), 0.6f, 5 * 60_000L, 7f, 26f)
    private val route = listOf(GeoPoint(-23.55, -46.63), GeoPoint(-23.553, -46.627), GeoPoint(-23.556, -46.624))

    private class Loader(val cached: Boolean, val online: Boolean = false) : TileLoader {
        val network = mutableListOf<TileId>()
        var cachedReads = 0
        override suspend fun load(tile: TileId): TileResult {
            network += tile
            return if (online) TileResult.Loaded(byteArrayOf(1), false) else TileResult.Unavailable
        }
        override suspend fun loadCached(tile: TileId): TileResult { cachedReads++; return if (cached) TileResult.Loaded(byteArrayOf(1), false) else TileResult.Unavailable }
        override fun invalidate(tile: TileId) = Unit
    }

    private fun show(loader: Loader?, fontScale: Float = 1f, route: List<GeoPoint> = this.route) = rule.setContent {
        MeuPatineteTheme(darkTheme = true) {
            val d = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(d.density, fontScale),
                LocalMapTiles provides loader?.let { MapTiles(it, "attr", decode = { ImageBitmap(4, 4) }, decodeDispatcher = Dispatchers.Unconfined) },
            ) { HistoryScreen(HistoryUiMapper(zone).map(listOf(trip), now), onBack = {}, onOpenTrip = {}, routeOf = { route }) }
        }
    }

    @Test fun `com tiles em cache o cartao mostra mapa desfocado sob o trajeto, sem rede`() {
        val loader = Loader(cached = true)
        show(loader)
        rule.waitUntil(5_000) { rule.onAllNodesWithTag("thumb-map", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("thumb-map", useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithTag("route-map", useUnmergedTree = true).assertIsDisplayed()
        assertTrue(loader.network.isEmpty())
        assertEquals(4, loader.cachedReads)
        rule.onNodeWithText("0,6 km · 5 min").assertIsDisplayed()
    }

    @Test fun `sem cache e com rede a miniatura baixa os tiles e mostra o mapa`() {
        val loader = Loader(cached = false, online = true)
        show(loader)
        rule.waitUntil(5_000) { rule.onAllNodesWithTag("thumb-map", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("thumb-map", useUnmergedTree = true).assertIsDisplayed()
        assertEquals(4, loader.network.size)
    }

    @Test fun `sem cache e sem rede volta ao fundo liso com o trajeto, sem spinner ou erro`() {
        val loader = Loader(cached = false)
        show(loader)
        rule.waitForIdle()
        rule.onAllNodesWithTag("thumb-map", useUnmergedTree = true).assertCountEquals(0)
        rule.onNodeWithTag("route-map", useUnmergedTree = true).assertIsDisplayed()
        rule.onAllNodesWithTag("map-offline", useUnmergedTree = true).assertCountEquals(0)
        rule.onNodeWithText("Hoje, 08:15").assertIsDisplayed()
    }

    @Test fun `sem provedor de tiles o cartao fica como antes`() {
        show(null)
        rule.waitForIdle()
        rule.onAllNodesWithTag("thumb-map", useUnmergedTree = true).assertCountEquals(0)
        rule.onNodeWithTag("route-map", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test fun `viagem sem percurso nao pede tiles da rota`() {
        val loader = Loader(cached = true)
        show(loader, route = emptyList())
        rule.waitForIdle()
        rule.onAllNodesWithTag("thumb-map", useUnmergedTree = true).assertCountEquals(0)
        assertEquals(0, loader.cachedReads)
    }

    @Test fun `fonte 2_0 mantem a miniatura 72 dp e o texto do cartao`() {
        show(Loader(cached = true), fontScale = 2f)
        rule.waitUntil(5_000) { rule.onAllNodesWithTag("thumb-map", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        val box = rule.onNodeWithTag("route-map", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertEquals(72f * rule.density.density, box.width, 1f)   // 72 dp fixos, a fonte não altera
        rule.onNodeWithText("Hoje, 08:15").assertIsDisplayed()
    }
}

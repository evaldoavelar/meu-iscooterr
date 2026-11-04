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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** S4 com carregador falso (sem rede). Atribuição do OSM é obrigação da licença: sempre que houver tile desenhado. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class RouteMapTilesTest {
    @get:Rule val rule = createComposeRule()

    private val route = listOf(GeoPoint(-23.55, -46.63), GeoPoint(-23.54, -46.62), GeoPoint(-23.53, -46.60))
    private val attribution = "© colaboradores do OpenStreetMap"
    private val png = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==")

    private class FakeLoader(val result: (TileId) -> TileResult) : TileLoader {
        val requested = mutableListOf<TileId>()
        override suspend fun load(tile: TileId): TileResult { requested += tile; return result(tile) }
    }

    private fun show(loader: TileLoader?, thumb: Boolean = false) = rule.setContent {
        MeuPatineteTheme(darkTheme = false) {
            CompositionLocalProvider(LocalMapTiles provides loader?.let { MapTiles(it, attribution) }) {
                RouteMap(route, if (thumb) Modifier.size(72.dp) else Modifier.fillMaxWidth().height(260.dp), thumb = thumb)
            }
        }
    }

    private fun waitFor(tag: String) = rule.waitUntil(15_000) { rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }

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
        val loader = object : TileLoader { override suspend fun load(tile: TileId): TileResult { gate.await(); return TileResult.Loaded(png, false) } }
        show(loader)
        rule.onNodeWithTag("route-map").assertIsDisplayed()
        rule.onAllNodesWithTag("map-attribution").assertCountIs0()
        rule.onAllNodesWithTag("map-offline").assertCountIs0()
        rule.runOnIdle { gate.complete(Unit) }
        waitFor("map-attribution")
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

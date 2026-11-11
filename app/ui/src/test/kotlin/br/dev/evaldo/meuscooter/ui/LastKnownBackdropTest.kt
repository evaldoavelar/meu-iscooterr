package br.dev.evaldo.meuscooter.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.unit.Density
import br.dev.evaldo.meuscooter.domain.GeoPoint
import br.dev.evaldo.meuscooter.domain.LastKnownLocation
import br.dev.evaldo.meuscooter.domain.LastKnownLocationSource
import br.dev.evaldo.meuscooter.domain.TileId
import br.dev.evaldo.meuscooter.domain.TileLoader
import br.dev.evaldo.meuscooter.domain.TileResult
import br.dev.evaldo.meuscooter.domain.Trip
import br.dev.evaldo.meuscooter.ui.history.BLURRED_MAP_DESCRIPTION
import br.dev.evaldo.meuscooter.ui.history.HistoryScreen
import br.dev.evaldo.meuscooter.ui.history.LocalLastKnownLocationSource
import br.dev.evaldo.meuscooter.ui.history.LocalMapTiles
import br.dev.evaldo.meuscooter.ui.history.MapTiles
import br.dev.evaldo.meuscooter.ui.history.TripDetailScreen
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

/** S45 A/B: viagem sem percurso mostra tiles desfocados da última posição conhecida; sem dado, fallback neutro. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class LastKnownBackdropTest {
    @get:Rule val rule = createComposeRule()

    private val zone = ZoneOffset.UTC
    private val now = ZonedDateTime.of(2026, 11, 3, 20, 0, 0, 0, zone).toInstant().toEpochMilli()
    private val mapper = HistoryUiMapper(zone)
    private val trip = Trip(2, ZonedDateTime.of(2026, 11, 3, 8, 15, 0, 0, zone).toInstant().toEpochMilli(), 0f, 3 * 60_000L, 0f, 0f)
    private val here = LastKnownLocation(GeoPoint(-23.55, -46.63))

    private class FakeLoader : TileLoader {
        val requested = mutableListOf<TileId>()
        override suspend fun load(tile: TileId): TileResult { requested += tile; return TileResult.Loaded(byteArrayOf(1), stale = false) }
        override fun invalidate(tile: TileId) = Unit
    }

    private fun tiles(loader: TileLoader, decoded: Boolean = true) =
        MapTiles(loader, "© colaboradores do OpenStreetMap", decode = { if (decoded) ImageBitmap(4, 4) else null }, decodeDispatcher = Dispatchers.Unconfined)

    private fun show(loc: LastKnownLocation?, mapTiles: MapTiles?, fontScale: Float = 1f, detail: Boolean = true) = rule.setContent {
        MeuPatineteTheme(darkTheme = false) {
            val d = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(d.density, fontScale),
                LocalMapTiles provides mapTiles,
                LocalLastKnownLocationSource provides LastKnownLocationSource { loc },
            ) {
                if (detail) TripDetailScreen(mapper.detail(trip, now), trip.route, onBack = {})
                else HistoryScreen(mapper.map(listOf(trip), now), onBack = {}, onOpenTrip = {}, onDeleteTrip = {})
            }
        }
    }

    @Test fun `detalhe com coordenada e tiles mostra mapa desfocado, atribuicao e cartao sem linha extra`() {
        val loader = FakeLoader()
        show(here, tiles(loader))
        rule.waitForIdle()
        rule.onNodeWithTag("map-attribution").assertIsDisplayed()
        rule.onNodeWithText("© colaboradores do OpenStreetMap").assertIsDisplayed()
        rule.onNodeWithText("Sem percurso gravado").assertIsDisplayed()
        rule.onNodeWithText("GPS sem leitura").assertIsDisplayed()
        rule.onNodeWithText("Sem localização para mostrar o mapa").assertDoesNotExist()
        rule.onAllNodesWithContentDescription(BLURRED_MAP_DESCRIPTION).assertCountEquals(1)
        assertEquals(setOf(TileId(14, 6069, 9295), TileId(14, 6070, 9295)), loader.requested.toSet())
        assertEquals(2, loader.requested.size)
    }

    @Test fun `detalhe sem coordenada mostra fallback com linha extra e nunca chama o carregador`() {
        val loader = FakeLoader()
        show(null, tiles(loader))
        rule.waitForIdle()
        rule.onNodeWithText("Sem percurso gravado").assertIsDisplayed()
        rule.onNodeWithText("Sem localização para mostrar o mapa").assertIsDisplayed()
        rule.onAllNodesWithContentDescription(BLURRED_MAP_DESCRIPTION).assertCountEquals(0)
        rule.onNodeWithTag("map-attribution").assertDoesNotExist()
        assertEquals(emptyList<TileId>(), loader.requested)
    }

    @Test fun `detalhe com coordenada mas sem MapTiles mostra fallback com linha extra`() {
        show(here, null)
        rule.waitForIdle()
        rule.onNodeWithText("Sem localização para mostrar o mapa").assertIsDisplayed()
        rule.onAllNodesWithContentDescription(BLURRED_MAP_DESCRIPTION).assertCountEquals(0)
    }

    @Test fun `detalhe com tiles que nao decodificam cai no fallback`() {
        show(here, tiles(FakeLoader(), decoded = false))
        rule.waitForIdle()
        rule.onNodeWithText("Sem localização para mostrar o mapa").assertIsDisplayed()
        rule.onNodeWithTag("map-attribution").assertDoesNotExist()
    }

    @Test fun `detalhe fonte 2_0 mantem o cartao e a linha extra sem corte`() {
        show(null, tiles(FakeLoader()), fontScale = 2f)
        rule.waitForIdle()
        val screen = rule.onRoot().getBoundsInRoot()
        for (n in listOf(rule.onNodeWithTag("route-empty"), rule.onNodeWithText("Sem percurso gravado"), rule.onNodeWithText("Sem localização para mostrar o mapa"))) {
            n.assertIsDisplayed()
            val b = n.getBoundsInRoot()
            assertTrue("fora da tela: $b", b.left >= screen.left && b.top >= screen.top && b.right <= screen.right && b.bottom <= screen.bottom)
        }
        val box = rule.onNodeWithTag("route-empty").getBoundsInRoot()
        for (t in listOf("Sem percurso gravado", "Sem localização para mostrar o mapa")) {
            val b = rule.onNodeWithText(t).getBoundsInRoot()
            assertTrue("$t corta no cartão: $b vs $box", b.left >= box.left && b.right <= box.right && b.top >= box.top && b.bottom <= box.bottom)
        }
    }

    @Test fun `miniatura com coordenada pede tiles e mostra pino sem texto extra`() {
        val loader = FakeLoader()
        show(here, tiles(loader), detail = false)
        rule.waitForIdle()
        rule.onNodeWithTag("thumb-pin", useUnmergedTree = true).assertIsDisplayed()
        rule.onAllNodesWithContentDescription(BLURRED_MAP_DESCRIPTION, useUnmergedTree = true).assertCountEquals(1)
        rule.onNodeWithTag("map-attribution", useUnmergedTree = true).assertDoesNotExist()
        assertEquals(listOf(TileId(14, 6069, 9295)), loader.requested)
    }

    @Test fun `miniatura sem coordenada mostra so o pino e nao carrega tiles`() {
        val loader = FakeLoader()
        show(null, tiles(loader), detail = false)
        rule.waitForIdle()
        rule.onNodeWithTag("thumb-pin", useUnmergedTree = true).assertIsDisplayed()
        rule.onAllNodesWithContentDescription(BLURRED_MAP_DESCRIPTION, useUnmergedTree = true).assertCountEquals(0)
        assertEquals(emptyList<TileId>(), loader.requested)
    }

    @Test fun `viagem com percurso nao consulta a ultima posicao`() {
        var calls = 0
        val withRoute = trip.copy(distanceKm = 1f, route = listOf(GeoPoint(-23.55, -46.63), GeoPoint(-23.54, -46.62)))
        rule.setContent {
            MeuPatineteTheme(darkTheme = false) {
                CompositionLocalProvider(LocalLastKnownLocationSource provides LastKnownLocationSource { calls++; here }) {
                    TripDetailScreen(mapper.detail(withRoute, now), withRoute.route, onBack = {})
                }
            }
        }
        rule.waitForIdle()
        rule.onNodeWithTag("route-empty").assertDoesNotExist()
        assertEquals(0, calls)
    }
}

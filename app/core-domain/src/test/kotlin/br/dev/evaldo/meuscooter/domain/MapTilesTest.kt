package br.dev.evaldo.meuscooter.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** S1. Valores conhecidos do esquema "slippy map" do OSM (z/x/y): (0,0) z1 → 1/1; Greenwich (51,4779; 0) z10 → 512/340. */
class MapTilesTest {
    @Test fun `tile do ponto 0,0 no zoom 1`() {
        assertEquals(TileId(1, 1, 1), TileMath.tileFor(GeoPoint(0.0, 0.0), 1))
    }

    @Test fun `tile de Greenwich no zoom 10`() {
        assertEquals(TileId(10, 512, 340), TileMath.tileFor(GeoPoint(51.4779, 0.0), 10))
    }

    @Test fun `longitude 180 nao estoura o indice do tile`() {
        assertEquals(1, TileMath.tileFor(GeoPoint(0.0, 180.0), 1).x)
    }

    @Test fun `fonte OSM monta a URL oficial, atribuicao e User-Agent proprio`() {
        val osm = OsmTileSource("0.2.0")
        assertEquals("https://tile.openstreetmap.org/10/512/340.png", osm.urlFor(TileId(10, 512, 340)))
        assertEquals("© colaboradores do OpenStreetMap", osm.attribution)
        assertEquals("Meu iScooter/0.2.0 (uso pessoal)", osm.userAgent)
    }

    @Test fun `provedor e selecionavel por id e o padrao e o OSM`() {
        assertEquals("Meu iScooter/1.0 (uso pessoal)", TileSources.create(TileProvider.OSM, "1.0").userAgent)
        assertEquals(TileProvider.OSM, TileProvider.DEFAULT)
    }

    @Test fun `cache vale 30 dias`() {
        val p = TileCachePolicy()
        val day = 86_400_000L
        assertTrue(p.isFresh(storedAtMs = 0, nowMs = 30 * day))
        assertFalse(p.isFresh(storedAtMs = 0, nowMs = 30 * day + 1))
    }

    @Test fun `despejo remove expirados e depois os menos recentes ate caber no limite`() {
        val p = TileCachePolicy(maxAgeMs = 100, maxBytes = 100)
        val entries = listOf(
            CacheEntry("velho", bytes = 10, storedAtMs = 0, lastAccessMs = 5),
            CacheEntry("a", bytes = 40, storedAtMs = 950, lastAccessMs = 960),
            CacheEntry("b", bytes = 40, storedAtMs = 950, lastAccessMs = 990),
            CacheEntry("c", bytes = 40, storedAtMs = 950, lastAccessMs = 970),
        )
        // agora=1000: "velho" expirou (1000-0 > 100); restam 120 bytes > 100 → sai o menos acessado ("a")
        assertEquals(listOf("velho", "a"), p.evict(entries, nowMs = 1_000))
    }

    @Test fun `rota projetada cabe no retangulo com margem e mantem o aspecto de latitude e longitude`() {
        val route = listOf(GeoPoint(0.0, 0.0), GeoPoint(1.0, 2.0))
        val pts = RouteProjection.project(route, width = 200f, height = 100f, padding = 10f)
        // escala igual nos dois eixos: min((200-20)/2, (100-20)/1) = 80 px/°; largura usada 160 → centraliza (x 20..180);
        // norte em cima: lat 0..1 → y 90..10
        assertEquals(20f, pts.first().x, 0.001f); assertEquals(90f, pts.first().y, 0.001f)
        assertEquals(180f, pts.last().x, 0.001f); assertEquals(10f, pts.last().y, 0.001f)
    }

    @Test fun `rota vazia ou de um ponto nao gera segmentos`() {
        assertEquals(emptyList<ProjectedPoint>(), RouteProjection.project(emptyList(), 100f, 100f, 5f))
        assertEquals(1, RouteProjection.project(listOf(GeoPoint(1.0, 1.0)), 100f, 100f, 5f).size)
    }
}

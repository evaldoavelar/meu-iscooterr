package br.dev.evaldo.meuscooter.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S1. Valores derivados à mão da projeção Web Mercator do OSM: no equador ny = 0,5; lon -180 → nx 0; lon 0 → nx 0,5.
 * Rota (0,-180)→(0,0) em 356×100 com margem 50: área útil 256 px para 0,5 de mundo → escala 512 px (zoom 1,0).
 */
class MapViewportTest {
    private val route = listOf(GeoPoint(0.0, -180.0), GeoPoint(0.0, 0.0))
    private val vp = MapViewport.fit(route, width = 356f, height = 100f, padding = 50f)!!

    @Test fun `zoom e escolhido para a rota caber na area util`() {
        assertEquals(1.0, vp.zoom, 1e-9)
    }

    @Test fun `pontos da rota caem nas margens`() {
        assertEquals(50f, vp.project(route[0]).x, 0.01f); assertEquals(50f, vp.project(route[0]).y, 0.01f)
        assertEquals(306f, vp.project(route[1]).x, 0.01f)
    }

    @Test fun `tiles cobrem a area visivel com posicoes e tamanho na tela`() {
        val tiles = vp.tiles().associateBy { it.tile }
        assertEquals(setOf(TileId(1, 0, 0), TileId(1, 1, 0), TileId(1, 0, 1), TileId(1, 1, 1)), tiles.keys)
        val a = tiles.getValue(TileId(1, 0, 0)); assertEquals(50f, a.left, 0.01f); assertEquals(-206f, a.top, 0.01f); assertEquals(256f, a.size, 0.01f)
        val d = tiles.getValue(TileId(1, 1, 1)); assertEquals(306f, d.left, 0.01f); assertEquals(50f, d.top, 0.01f)
    }

    @Test fun `ponto unico usa o zoom maximo e rota vazia nao tem mapa`() {
        assertEquals(16.0, MapViewport.fit(listOf(GeoPoint(-23.55, -46.63)), 200f, 200f, 10f)!!.zoom, 1e-9)
        assertNull(MapViewport.fit(emptyList(), 200f, 200f, 10f))
    }

    @Test fun `zoom fracionario usa tiles do zoom inteiro abaixo, ampliados`() {
        // 0,5 de mundo em 384 px úteis → escala 768 = 256·2^1,585 → tiles z1 com 384 px
        val v = MapViewport.fit(route, width = 484f, height = 100f, padding = 50f)!!
        assertEquals(1.585, v.zoom, 1e-3)
        assertEquals(384f, v.tiles().first().size, 0.01f)
        assertEquals(1, v.tiles().first().tile.zoom)
    }

    @Test fun `pior caso em tela xxhdpi (1080x682 px) pede no maximo 24 tiles`() {
        val routes = listOf(
            route, listOf(GeoPoint(-23.55, -46.63), GeoPoint(-23.53, -46.60)), listOf(GeoPoint(0.0, 0.0), GeoPoint(0.0, 0.0001)),
            listOf(GeoPoint(51.0, 0.0), GeoPoint(52.0, 1.0)),
        )
        routes.forEach { assertTrue(MapViewport.fit(it, 1080f, 682f, 63f)!!.tiles().size <= 24) }
    }

    @Test fun `large viewports keep full coverage with at most 24 requests`() {
        // A point at the equator fixes z16. At 256 px per tile, 3840x2160 would need 16x10 tiles.
        // The request budget comes from TASK-S15-B / HANDOFF-MAP-OSM, not the implementation.
        val point = listOf(GeoPoint(0.0, 0.0))
        listOf(3840f to 2160f, 2160f to 3840f, 8192f to 8192f).forEach { (width, height) ->
            val placements = MapViewport.fit(point, width, height, 24f)!!.tiles()
            assertTrue("request count ${placements.size} for $width x $height", placements.size in 1..24)
            assertEquals(placements.size, placements.map { it.tile }.distinct().size)
            // Every visible tile must be present, rather than truncating a list to meet the budget.
            for (row in 0..8) for (column in 0..8) {
                val x = 0.5f + (width - 1f) * column / 8
                val y = 0.5f + (height - 1f) * row / 8
                assertTrue("gap at $x,$y", placements.any {
                    x >= it.left && x < it.left + it.size && y >= it.top && y < it.top + it.size
                })
            }
        }
    }
}

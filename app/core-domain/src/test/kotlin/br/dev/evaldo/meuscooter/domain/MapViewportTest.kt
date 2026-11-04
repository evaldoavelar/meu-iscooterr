package br.dev.evaldo.meuscooter.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}

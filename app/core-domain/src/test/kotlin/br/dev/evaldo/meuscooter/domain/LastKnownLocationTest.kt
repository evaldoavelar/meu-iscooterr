package br.dev.evaldo.meuscooter.domain

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class LastKnownLocationTest {
    private val center = GeoPoint(-23.55, -46.63)

    @Test fun `toString nao vaza coordenadas`() {
        val s = LastKnownLocation(center).toString()
        assertEquals("LastKnownLocation", s)
        assertFalse(s.contains("23"))
    }

    @Test fun `fonte None devolve null`() = runBlocking { assertNull(LastKnownLocationSource.None.lastKnown()) }

    @Test fun `tile do centro no zoom 14`() {
        assertEquals(TileId(14, 6069, 9295), TileMath.tileFor(center, 14))
    }

    @Test fun `miniatura 72dp a densidade 3 usa 1 a 4 tiles do mesmo zoom incluindo o do centro`() {
        val p = LastKnownMap.placements(center, 216f, 216f, 3f)
        assertEquals(true, p.size in 1..4)
        assertEquals(setOf(14), p.map { it.tile.zoom }.toSet())
        assertEquals(true, TileId(14, 6069, 9295) in p.map { it.tile })
    }

    @Test fun `detalhe 360x260dp a densidade 3 usa no maximo 4 tiles`() {
        val p = LastKnownMap.placements(center, 1080f, 780f, 3f)
        assertEquals(true, p.size in 1..4)
    }

    @Test fun `mesmo centro e mesmo tile em tamanhos diferentes reaproveita o tile central`() {
        val a = LastKnownMap.placements(center, 216f, 216f, 3f).map { it.tile }
        val b = LastKnownMap.placements(center, 1080f, 780f, 3f).map { it.tile }
        assertEquals(true, TileId(14, 6069, 9295) in a && TileId(14, 6069, 9295) in b)
    }

    @Test fun `tile de 256 virtuais vira 512 px a densidade 1`() {
        val p = LastKnownMap.placements(center, 100f, 100f, 1f)
        assertEquals(true, p.all { it.size == 512f })
    }
}

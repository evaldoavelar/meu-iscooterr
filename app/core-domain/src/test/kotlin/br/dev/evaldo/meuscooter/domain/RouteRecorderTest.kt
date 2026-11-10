package br.dev.evaldo.meuscooter.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteRecorderTest {
    private val open = RouteGate(saveRoute = true, permissionGranted = true, tripActive = true)
    // 0,0001 grau de latitude no equador ~ 11,1 m (valor de referência geodésico, independente do código)
    private fun fix(i: Int, acc: Float = 5f) = GpsFix(-23.0 + i * 0.0001, -46.0, acc, i * 1_000L)

    @Test fun `grava ponto valido com portao aberto`() {
        val r = RouteRecorder()
        assertTrue(r.offer(fix(0), open))
        assertEquals(listOf(GeoPoint(-23.0, -46.0)), r.points)
    }

    @Test fun `saveRoute desligado nao grava`() {
        val r = RouteRecorder()
        assertFalse(r.offer(fix(0), open.copy(saveRoute = false)))
        assertTrue(r.points.isEmpty())
    }

    @Test fun `sem permissao ou sem viagem em curso nao grava`() {
        val r = RouteRecorder()
        assertFalse(r.offer(fix(0), open.copy(permissionGranted = false)))
        assertFalse(r.offer(fix(1), open.copy(tripActive = false)))
        assertTrue(r.points.isEmpty())
    }

    @Test fun `amostra nula nao grava`() {
        val r = RouteRecorder()
        assertFalse(r.offer(null, open))
        assertTrue(r.points.isEmpty())
    }

    @Test fun `precisao pior que o limite e descartada`() {
        val r = RouteRecorder(maxAccuracyM = 30f)
        assertFalse(r.offer(fix(0, acc = 30.1f), open))
        assertTrue(r.offer(fix(1, acc = 30f), open))
        assertEquals(1, r.points.size)
    }

    @Test fun `ponto a menos da distancia minima e descartado`() {
        val r = RouteRecorder(minDistanceM = 10.0)
        assertTrue(r.offer(fix(0), open))
        assertFalse(r.offer(GpsFix(-23.0 + 0.00005, -46.0, 5f, 1), open))   // ~5,5 m
        assertTrue(r.offer(fix(1), open))                                      // ~11,1 m do primeiro
        assertEquals(2, r.points.size)
    }

    @Test fun `limite de pontos para a gravacao`() {
        val r = RouteRecorder(maxPoints = 3)
        (0..9).forEach { r.offer(fix(it), open) }
        assertEquals(3, r.points.size)
    }

    @Test fun `portao fechado no meio preserva o que ja foi gravado e reset limpa`() {
        val r = RouteRecorder()
        r.offer(fix(0), open); r.offer(fix(1), open)
        r.offer(fix(2), open.copy(saveRoute = false))
        assertEquals(2, r.points.size)
        r.reset()
        assertTrue(r.points.isEmpty())
    }

    @Test fun `points devolve copia`() {
        val r = RouteRecorder()
        r.offer(fix(0), open)
        val p = r.points
        r.offer(fix(1), open)
        assertEquals(1, p.size)
    }

    @Test fun `haversine bate com referencia geodesica`() {
        // 1 grau de latitude = 111,195 km com R=6371 km
        assertEquals(111_195.0, distanceMeters(GeoPoint(0.0, 0.0), GeoPoint(1.0, 0.0)), 10.0)
        assertEquals(0.0, distanceMeters(GeoPoint(5.0, 5.0), GeoPoint(5.0, 5.0)), 1e-9)
    }

    @Test fun `gpsFixOf valida coordenadas e precisao`() {
        assertEquals(GpsFix(1.0, 2.0, 5f, 9L), gpsFixOf(true, 5f, 1.0, 2.0, 9L))
        assertNull(gpsFixOf(false, 5f, 1.0, 2.0, 9L))
        assertNull(gpsFixOf(true, Float.NaN, 1.0, 2.0, 9L))
        assertNull(gpsFixOf(true, 5f, 91.0, 2.0, 9L))
        assertNull(gpsFixOf(true, 5f, 1.0, Double.NaN, 9L))
    }

    @Test fun `toString do GpsFix nao expoe coordenadas`() {
        val s = GpsFix(-23.123456, -46.654321, 5f, 1L).toString()
        assertFalse(s.contains("23.12")); assertFalse(s.contains("46.65"))
    }

    @Test fun `store em memoria grava le apaga e limpa`() {
        val s = InMemoryRouteStore()
        s.write(1, listOf(GeoPoint(1.0, 2.0))); s.write(2, listOf(GeoPoint(3.0, 4.0)))
        assertEquals(listOf(GeoPoint(1.0, 2.0)), s.read(1))
        s.delete(1); assertTrue(s.read(1).isEmpty())
        s.clear(); assertTrue(s.read(2).isEmpty())
    }
}
